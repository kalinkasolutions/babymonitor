package ch.kalinka.babymonitor.net

import ch.kalinka.babymonitor.log.AppLog
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

/** Something the relay said, unprompted. */
sealed interface DeviceEvent {
    /** One signalling message from another phone, on the way to a media connection. */
    data class Signal(val signal: SignalMessage) : DeviceEvent

    /** A watched phone connected to the relay or dropped off it. */
    data class Presence(val deviceId: String, val online: Boolean) : DeviceEvent
}

@kotlinx.serialization.Serializable
private data class DevicePresenceDto(val deviceId: String = "", val isOnline: Boolean = false)

/**
 * The connection to the relay, when there is one. Signals and presence arrive here as they
 * happen, so neither phone has to keep asking.
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

    /** The phones whose presence this one wants to hear about. Sent again on every reconnect. */
    @Volatile
    private var watched: List<String> = emptyList()

    private val connecting = AtomicBoolean(false)

    fun connect() {
        if (!api.isConfigured || connection?.connectionState == HubConnectionState.CONNECTED) {
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

    fun watch(deviceIds: List<String>) {
        if (deviceIds == watched) {
            return
        }

        watched = deviceIds
        scope.launch { sendWatch() }
    }

    /**
     * Keeps trying until there is a connection or nobody wants one any more. The Java SignalR
     * client has no automatic reconnect, and a phone on a shelf overnight loses this socket often.
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
                        AppLog.i(TAG, "Relay connected")
                        sendWatch()
                        return@launch
                    }

                    // Disposed before the next try, or a long outage piles these up.
                    runCatching { hub.stop().blockingAwait() }
                    AppLog.w(TAG, "Relay could not connect; retrying in ${wait}ms")
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(LongestRetryMillis)
                }
            } finally {
                connecting.set(false)
            }
        }
    }

    private fun sendWatch() {
        val hub = connection?.takeIf { it.connectionState == HubConnectionState.CONNECTED } ?: return
        val ids = watched
        runCatching { hub.invoke(Array<String>::class.java, HUB_WATCH, ids.toTypedArray()).blockingGet() }
            .onSuccess { online ->
                val here = online.toSet()
                ids.forEach { emit(DeviceEvent.Presence(it, it in here)) }
            }
            .onFailure { AppLog.w(TAG, "Could not ask the relay who is online", it) }
    }

    // Built fresh for every attempt: the signature in the headers is only good for a few minutes.
    private fun build(): HubConnection = HubConnectionBuilder
        .create("${api.baseUrl}/hubs/devices")
        .withHeaders(api.authHeaders())
        .build()
        .apply {
            // Decoded with the same serializer as everything else, not the client's own Gson.
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
                    AppLog.w(TAG, "Signal could not be sent", it)
                    SignalResult.Refused(hubRefusal(it))
                }
            )
    }

    private fun emit(event: DeviceEvent) {
        scope.launch { _events.emit(event) }
    }

    private companion object {
        const val TAG = "DeviceStream"
        const val EVENT_SIGNAL = "signal"
        const val EVENT_PRESENCE = "devicePresence"
        const val HUB_SIGNAL = "Signal"
        const val HUB_WATCH = "Watch"
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
