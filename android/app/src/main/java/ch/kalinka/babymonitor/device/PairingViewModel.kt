package ch.kalinka.babymonitor.device

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.kalinka.babymonitor.log.AppLog
import ch.kalinka.babymonitor.media.CallCenter
import ch.kalinka.babymonitor.net.PairRequest
import ch.kalinka.babymonitor.net.ServerSettings
import ch.kalinka.babymonitor.net.SignalKinds
import ch.kalinka.babymonitor.net.SignalMessage
import ch.kalinka.babymonitor.net.trust
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pairing by scanning. The scanner reads the other phone's key off its screen, then tells that
 * phone who it is, signed with the secret from the QR code, so one scan confirms both.
 */
class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val household = Household(application)
    private val server = ServerSettings(application)
    private val calls = CallCenter.of(application)
    private val json = Json { ignoreUnknownKeys = true }

    private val _outcome = MutableStateFlow<ScanOutcome>(ScanOutcome.None)
    val outcome: StateFlow<ScanOutcome> = _outcome.asStateFlow()

    /** Set from the camera's analysis thread, so it has to be atomic rather than a plain flag. */
    private val pairing = AtomicBoolean(false)

    // Kept in memory only. A proof made with it shows the sender really read this screen.
    private val secret = PairingProof.newSecret()

    val qrPayload: String = PairingPayload(
        deviceId = household.deviceId,
        publicKey = DeviceIdentity.publicKey(),
        name = household.name,
        secret = secret,
        server = server.baseUrl,
        serverKey = server.key
    ).encode()

    /** Whether the pairing message can only travel over the WiFi. */
    val wifiOnly: Boolean = !server.isConfigured

    init {
        listenForScanner()
    }

    fun onScanned(text: String) {
        val payload = PairingPayload.decode(text)
        if (payload == null) {
            // The camera reads whatever is in front of it, so this is routine.
            AppLog.i(Tag, "Read a QR code that is not a Babymonitor pairing code")
            _outcome.value = ScanOutcome.NotAPairingCode
            return
        }

        if (_outcome.value !is ScanOutcome.None || !pairing.compareAndSet(false, true)) {
            // The analyzer keeps delivering frames while a pairing is in flight.
            return
        }

        adoptServer(payload.server, payload.serverKey)
        viewModelScope.launch {
            try {
                tellThemWhoScanned(payload)
            } finally {
                pairing.set(false)
            }
        }
    }

    /**
     * Kept trying for a while, because mDNS or the relay may not have noticed the other phone
     * yet. The pairing only counts once that phone has heard, or the list fills with phones that
     * have never heard of this one.
     */
    private suspend fun tellThemWhoScanned(payload: PairingPayload) {
        val request = PairRequest(
            deviceId = household.deviceId,
            name = household.name,
            publicKey = DeviceIdentity.publicKey(),
            proof = PairingProof.sign(payload.secret, household.deviceId, DeviceIdentity.publicKey()),
            server = server.baseUrl,
            serverKey = server.key
        )
        val message = SignalMessage(
            toDeviceId = payload.deviceId,
            kind = SignalKinds.Pair,
            body = json.encodeToString(request)
        )

        val name = payload.name.ifBlank { "The other phone" }
        AppLog.i(Tag, "Scanned ${payload.deviceId}; telling it who scanned")
        repeat(PairingAttempts) {
            if (calls.sendPairing(message)) {
                AppLog.i(Tag, "Paired with ${payload.deviceId}")
                household.remember(Phone(payload.deviceId, name, payload.publicKey))
                calls.refresh()
                _outcome.value = ScanOutcome.Confirmed(name)
                return
            }

            delay(PairingRetry)
        }

        AppLog.w(Tag, "Gave up reaching ${payload.deviceId} after $PairingAttempts tries; nothing was paired")
        _outcome.value = ScanOutcome.Failed(
            if (wifiOnly) {
                "$name could not be reached. Both phones need the app open on the same WiFi. " +
                    "Nothing was paired."
            } else {
                "$name could not be reached on the WiFi or through the server. Keep the app " +
                    "open on both phones and try again. Nothing was paired."
            }
        )
    }

    /** The other half of a scan: the phone that scanned this screen says who it is. */
    private fun listenForScanner() {
        viewModelScope.launch {
            calls.pairings.collect { signal ->
                val request = runCatching { json.decodeFromString<PairRequest>(signal.body) }
                    .getOrNull() ?: return@collect
                val name = request.name.ifBlank { "The other phone" }

                // The proof shows the sender read this screen. The signature, checked with the
                // key it just proved, covers the rest of the message, the server settings included.
                val proved = PairingProof.verify(secret, request.deviceId, request.publicKey, request.proof) &&
                    deviceIdOf(request.publicKey) == request.deviceId &&
                    signal.trust(request.publicKey).isVerified

                if (!proved) {
                    AppLog.w(Tag, "${request.deviceId} claimed to have scanned this phone but could not prove it")
                    _outcome.value = ScanOutcome.ProofFailed(name)
                    return@collect
                }

                AppLog.i(Tag, "${request.deviceId} proved it read this screen; pairing")
                household.remember(Phone(request.deviceId, name, request.publicKey))
                adoptServer(request.server, request.serverKey)
                calls.refresh()
                _outcome.value = ScanOutcome.ConfirmedByScanner(name)
            }
        }
    }

    /**
     * Takes the other phone's relay settings where this one has none, so only one needs typing in:
     * its server when this phone has no server, its key when both use the same server.
     */
    private fun adoptServer(url: String, key: String) {
        if (url.isBlank()) {
            return
        }

        val adoptUrl = !server.isConfigured
        val adoptKey = key.isNotBlank() && server.key.isBlank() && (adoptUrl || server.baseUrl == url)
        if (!adoptUrl && !adoptKey) {
            return
        }

        if (adoptUrl) {
            AppLog.i(Tag, "Using the server the other phone uses: $url")
            server.baseUrl = url
        }
        if (adoptKey) {
            AppLog.i(Tag, "Using the server key the other phone uses")
            server.key = key
        }
        calls.reconnect()
    }

    fun dismissOutcome() {
        _outcome.value = ScanOutcome.None
    }

    private companion object {
        const val Tag = "PairingViewModel"

        /** Long enough for mDNS to notice the other phone, which is seconds rather than instant. */
        const val PairingAttempts = 30
        const val PairingRetry = 500L
    }
}
