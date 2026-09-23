package ch.kalinka.babymonitor.device

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.kalinka.babymonitor.net.ApiClient
import ch.kalinka.babymonitor.net.ApiResult
import ch.kalinka.babymonitor.net.DeviceDto
import ch.kalinka.babymonitor.net.DeviceEvent
import ch.kalinka.babymonitor.net.DeviceStream
import ch.kalinka.babymonitor.media.CallCenter
import ch.kalinka.babymonitor.net.HeartbeatRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val LocalOwner = "local"

class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val api = ApiClient(application)
    private val verifiedKeys = VerifiedKeys(application)
    private val local = LocalSession(application)
    private val calls = CallCenter.of(application)
    private val stream = DeviceStream(api)

    private val _devices = MutableStateFlow<List<DeviceListItem>>(emptyList())
    val devices: StateFlow<List<DeviceListItem>> = _devices.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Read from the keystore once: it cannot change while the app is running. */
    private val thisPhoneKey by lazy { DeviceIdentity.publicKey() }

    init {
        refresh()
        if (!local.enabled) {
            listen()
        }
    }

    /**
     * Keeps the list current from the server's own announcements. Nothing here ever asks whether
     * something changed — a battery reading from the other phone has to arrive on its own, or
     * both handsets would be awake all night asking.
     */
    private fun listen() {
        stream.connect()
        viewModelScope.launch {
            stream.events.collect { event ->
                when (event) {
                    is DeviceEvent.Changed -> apply(event.device)
                    is DeviceEvent.Removed -> _devices.value =
                        _devices.value.filterNot { it.device.id == event.deviceId }

                    // Both mean the visible set changed rather than one device in it, so re-read
                    // the lot — a finished pairing is exactly what this screen is waiting to show.
                    DeviceEvent.LinksChanged, is DeviceEvent.PairingCompleted -> load()

                    // Somebody else's business: signalling is between two phones and the monitor
                    // screen is where it is answered.
                    is DeviceEvent.Signal -> Unit

                    // A phone arriving or leaving changes whether it can answer, which this list
                    // shows next to the battery.
                    is DeviceEvent.Presence -> applyPresence(event.deviceId, event.online)
                }
            }
        }
    }

    private fun applyPresence(deviceId: String, online: Boolean) {
        _devices.value = _devices.value.map {
            if (it.device.id == deviceId) it.copy(device = it.device.copy(isOnline = online)) else it
        }
    }

    private fun apply(device: DeviceDto) {
        val mine = myOwnerId(_devices.value.map { it.device })
        val updated = _devices.value.filterNot { it.device.id == device.id } + toItem(device, mine)
        _devices.value = ordered(updated)
    }

    /**
     * Whose account this is, worked out from the one row this phone can identify by its own key.
     * Whether a phone is mine decides whether it can be removed on its own or only by dropping a
     * whole account, so it is not left to a field that arrives in a broadcast.
     */
    private fun myOwnerId(devices: List<DeviceDto>): String? =
        devices.firstOrNull { it.publicKey == thisPhoneKey }?.ownerId

    override fun onCleared() {
        stream.disconnect()
        super.onCleared()
    }

    /** Registration happens on sign-in, so this only has to report a fresh battery reading. */
    fun refresh() {
        whileBusy {
            if (!local.enabled) {
                api.deviceId?.let { sendHeartbeat(it) }
            }

            load()
        }
    }

    /** The string both phones show, for the two people to check against each other. */
    fun safetyNumberFor(device: DeviceDto): String =
        SafetyNumber.of(DeviceIdentity.publicKey(), device.publicKey)

    /**
     * Records that the comparison matched. Pinning the key the backend reported is sound
     * precisely because the strings agreeing is what proves it reported the real one.
     */
    fun confirmByComparison(device: DeviceDto) {
        Log.i(Tag, "Confirmed device ${device.id} by comparing safety numbers")
        verifiedKeys.pin(device.id, device.publicKey)
        _devices.value = ordered(_devices.value.map {
            if (it.device.id == device.id) it.copy(trust = KeyTrust.Confirmed) else it
        })
    }

    fun rename(deviceId: String, name: String) {
        whileBusy {
            when (val result = api.renameDevice(deviceId, name.trim())) {
                is ApiResult.Ok -> load()
                is ApiResult.Failure -> {
                    Log.w(Tag, "Could not rename device $deviceId: ${result.message}")
                    _error.value = result.message
                }
            }
        }
    }

    /**
     * Someone else's phone is not ours to remove — dropping the link to their account is what
     * takes it out of the list, and it takes their other phones with it.
     */
    fun unlink(ownerId: String) {
        whileBusy {
            when (val result = api.unlinkAccount(ownerId)) {
                is ApiResult.Ok -> {
                    Log.i(Tag, "Unlinked account $ownerId; its phones are out of the list")
                    load()
                }

                is ApiResult.Failure -> {
                    Log.w(Tag, "Could not unlink account $ownerId: ${result.message}")
                    _error.value = result.message
                }
            }
        }
    }

    fun revoke(deviceId: String) {
        if (local.enabled) {
            Log.i(Tag, "Forgetting device $deviceId")
            local.forget(deviceId)
            verifiedKeys.forget(deviceId)
            whileBusy { load() }
            return
        }

        whileBusy {
            when (val result = api.revokeDevice(deviceId)) {
                is ApiResult.Ok -> {
                    // Revoking this phone leaves it unregistered rather than pointing at a row
                    // that no longer exists; the next launch registers it again.
                    if (deviceId == api.deviceId) {
                        api.deviceId = null
                    }
                    // Drop the pin too: a device that comes back is a new registration and has
                    // to be scanned again rather than inheriting the old verdict.
                    verifiedKeys.forget(deviceId)
                    Log.i(Tag, "Revoked device $deviceId and dropped its pinned key")
                    load()
                }

                is ApiResult.Failure -> {
                    Log.w(Tag, "Could not revoke device $deviceId: ${result.message}")
                    _error.value = result.message
                }
            }
        }
    }

    private suspend fun sendHeartbeat(deviceId: String) {
        val battery = readBattery(getApplication())
        api.heartbeat(deviceId, HeartbeatRequest(battery.percent, battery.isCharging))
    }

    private suspend fun load() {
        // With no account there is no server to ask. The phones this one has scanned are the
        // phones there are, and the WiFi says which of them are answering.
        if (local.enabled) {
            val mine = local.peers().map { it.copy(isOnline = calls.onThisNetwork(it.id)) }
            _devices.value = ordered(mine.map { toItem(it, LocalOwner) } + thisPhone())
            return
        }

        when (val result = api.devices()) {
            is ApiResult.Ok -> {
                val mine = myOwnerId(result.value)
                val items = ordered(result.value.map { toItem(it, mine) })
                _devices.value = items

                // A key that changed after being confirmed is the one thing in this list worth
                // finding in a log afterwards, so it is named rather than counted.
                items.filter { it.trust == KeyTrust.Changed }
                    .forEach { Log.e(Tag, "Device ${it.device.id} reports a different key from the one confirmed") }
            }

            is ApiResult.Failure -> {
                Log.w(Tag, "Could not load the device list: ${result.message}")
                _error.value = result.message
            }
        }
    }

    private fun toItem(device: DeviceDto, myOwnerId: String?): DeviceListItem {
        // Which row is this phone is settled here, against the key in this phone's own keystore,
        // and never by the flag the server sets: a trust verdict the backend can hand out is not
        // a trust verdict. This phone's own key needs no confirming — it never travelled anywhere.
        val isThisPhone = device.publicKey == thisPhoneKey
        val trust = if (isThisPhone) {
            KeyTrust.Confirmed
        } else {
            keyTrustOf(verifiedKeys.pinned(device.id), device.publicKey)
        }
        // Not the flag from the server: a device change is broadcast to every account that can
        // see it, and one described for its owner would tell this phone it owns somebody else's.
        val mine = myOwnerId?.let { device.ownerId == it } ?: device.isMine
        return DeviceListItem(device, trust, isThisPhone, mine)
    }

    /**
     * This phone first, then the rest of yours, then everybody else's kept together by owner —
     * and within any of those, the ones heard from most recently first, so the registration a
     * reinstall left behind sinks below the phone that is actually in use.
     */
    private fun ordered(items: List<DeviceListItem>) =
        items.sortedWith(
            compareByDescending<DeviceListItem> { it.isThisPhone }
                .thenByDescending { it.isMine }
                .thenBy { it.device.ownerName }
                .thenByDescending { it.device.lastSeenAt.orEmpty() }
        )

    /** This phone, which no server described because there is none. */
    private fun thisPhone(): DeviceListItem {
        val battery = readBattery(getApplication())
        val device = DeviceDto(
            id = local.deviceId,
            name = local.name,
            isMine = true,
            isOnline = true,
            ownerId = LocalOwner,
            ownerName = "This household",
            publicKey = thisPhoneKey,
            keyFingerprint = fingerprintOf(thisPhoneKey),
            batteryPercent = battery.percent,
            isCharging = battery.isCharging
        )

        return DeviceListItem(device, KeyTrust.Confirmed, isThisPhone = true, isMine = true)
    }

    private fun whileBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        const val Tag = "DevicesViewModel"
    }
}
