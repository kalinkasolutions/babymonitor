package ch.lqy.babyphone.net

import android.util.Log
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/** What became of a signalling message handed to the hub. */
sealed interface SignalResult {
    /** The other phone had a connection to take it. */
    data object Delivered : SignalResult

    /** The hub took it and nobody was there: that phone is asleep, or its app is closed. */
    data object NotListening : SignalResult

    /** This phone has no connection to the server, so nothing was sent at all. */
    data object Offline : SignalResult

    /** The server refused it, and said why. */
    data class Refused(val reason: String) : SignalResult
}

/** Something the backend said, unprompted. */
sealed interface DeviceEvent {
    data class Changed(val device: DeviceDto) : DeviceEvent
    data class Removed(val deviceId: String) : DeviceEvent

    /** Accounts were linked or unlinked, so the whole visible set changed. */
    data object LinksChanged : DeviceEvent

    /** A scan finished. Reaches the phone that showed the code as well as the one that read it. */
    data class PairingCompleted(val completed: PairingCompletedDto) : DeviceEvent

    /** One signalling message from another phone, on the way to a media connection. */
    data class Signal(val signal: SignalMessage) : DeviceEvent

    /** A phone arrived or dropped off. */
    data class Presence(val deviceId: String, val online: Boolean) : DeviceEvent
}

@kotlinx.serialization.Serializable
private data class DevicePresenceDto(val deviceId: String = "", val isOnline: Boolean = false)

/**
 * The live feed of device changes. Nothing in this app asks the server "has anything happened?" —
 * the server says so, which is the only way a battery reading or a phone going offline can reach
 * the other handset promptly without draining both of them.
 */
class DeviceStream(private val api: ApiClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    private var connection: HubConnection? = null

    /** Whether a connection is wanted at all, which is what tells a drop from a disconnect. */
    @Volatile
    private var wanted = false

    private val connecting = AtomicBoolean(false)

    fun connect() {
        if (connection?.connectionState == HubConnectionState.CONNECTED) {
            return
        }

        wanted = true
        openWithRetry()
    }

    fun disconnect() {
        wanted = false
        val hub = connection ?: return
        connection = null
        scope.launch { runCatching { hub.stop().blockingAwait() } }
    }

    /**
     * Keeps trying until there is a connection or nobody wants one any more.
     *
     * The Java SignalR client has no automatic reconnect of its own, and a phone left on a shelf
     * overnight loses this socket to a sleeping radio, a roaming access point or a backend
     * restart. Without this it never comes back: the device feed goes quiet and a call can no
     * longer be answered, with nothing on screen to say so.
     */
    private fun openWithRetry() {
        if (!connecting.compareAndSet(false, true)) {
            return
        }

        scope.launch {
            try {
                var wait = FirstRetryMillis
                while (wanted && connection?.connectionState != HubConnectionState.CONNECTED) {
                    val hub = build()
                    connection = hub
                    if (runCatching { hub.start().blockingAwait() }.isSuccess) {
                        return@launch
                    }

                    Log.w(TAG, "Device feed could not connect; retrying in ${wait}ms")
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(LongestRetryMillis)
                }
            } finally {
                connecting.set(false)
            }
        }
    }

    private fun build(): HubConnection = HubConnectionBuilder
        .create("${api.baseUrl}/hubs/devices")
        .withHeader("Cookie", api.cookieHeader())
        // The device header is what lets the hub address this phone on its own rather than only
        // as one of the account's: an offer is for one phone, and without this there is no way
        // to say which.
        .apply { api.deviceId?.let { withHeader(ApiClient.DEVICE_ID_HEADER, it) } }
        .build()
        .apply {
            // The payloads arrive as JSON text and are decoded with the same serializer as the
            // REST responses, rather than letting the SignalR client's own Gson reflect over
            // Kotlin types.
            on(EVENT_DEVICE_CHANGED, { payload: String ->
                emit(DeviceEvent.Changed(json.decodeFromString(payload)))
            }, String::class.java)

            on(EVENT_DEVICE_REMOVED, { deviceId: String ->
                emit(DeviceEvent.Removed(deviceId))
            }, String::class.java)

            on(EVENT_LINKS_CHANGED, { emit(DeviceEvent.LinksChanged) })

            on(EVENT_PAIRING_COMPLETED, { payload: String ->
                emit(DeviceEvent.PairingCompleted(json.decodeFromString(payload)))
            }, String::class.java)

            on(EVENT_SIGNAL, { payload: String ->
                emit(DeviceEvent.Signal(json.decodeFromString(payload)))
            }, String::class.java)

            on(EVENT_PRESENCE, { payload: String ->
                val presence = json.decodeFromString<DevicePresenceDto>(payload)
                emit(DeviceEvent.Presence(presence.deviceId, presence.isOnline))
            }, String::class.java)

            onClosed { if (wanted) openWithRetry() }
        }

    /**
     * Hands one signalling message to the hub for another phone.
     *
     * Every way this can go wrong is reported as itself. They look identical from the call site
     * and mean completely different things: a phone that is asleep, a phone this account may not
     * call, and this phone having quietly lost its own connection are three different problems,
     * and telling a user the last one is the first sends them to the wrong room.
     */
    suspend fun sendSignal(message: SignalMessage): SignalResult = withContext(Dispatchers.IO) {
        val hub = connection
        if (hub == null || hub.connectionState != HubConnectionState.CONNECTED) {
            return@withContext SignalResult.Offline
        }

        runCatching { hub.invoke(Boolean::class.java, HUB_SIGNAL, json.encodeToString(message)).blockingGet() }
            .fold(
                onSuccess = { if (it) SignalResult.Delivered else SignalResult.NotListening },
                onFailure = {
                    Log.w(TAG, "Signal could not be sent", it)
                    SignalResult.Refused(hubRefusal(it))
                }
            )
    }

    private fun emit(event: DeviceEvent) {
        scope.launch { _events.emit(event) }
    }

    private companion object {
        const val TAG = "DeviceStream"
        const val EVENT_DEVICE_CHANGED = "deviceChanged"
        const val EVENT_DEVICE_REMOVED = "deviceRemoved"
        const val EVENT_LINKS_CHANGED = "linksChanged"
        const val EVENT_PAIRING_COMPLETED = "pairingCompleted"
        const val EVENT_SIGNAL = "signal"
        const val EVENT_PRESENCE = "devicePresence"
        const val HUB_SIGNAL = "Signal"
        /** Quick at first, because most drops are a radio waking up. */
        const val FirstRetryMillis = 1_000L
        const val LongestRetryMillis = 30_000L
    }
}

/**
 * The readable part of a hub failure. The client wraps the server's message inside one of its
 * own, and an unknown method means the other end is older than this app — a deployment to fix,
 * not a phone to go and wake up.
 */
fun hubRefusal(error: Throwable): String {
    val raw = error.message.orEmpty()
    return when {
        raw.contains("does not exist", ignoreCase = true) ->
            "This server cannot connect calls yet. It is older than this app."

        raw.contains(HUB_EXCEPTION_MARKER) -> raw.substringAfter(HUB_EXCEPTION_MARKER).trim()
        raw.isNotBlank() -> raw
        else -> "The server refused the call."
    }
}

private const val HUB_EXCEPTION_MARKER = "HubException:"
