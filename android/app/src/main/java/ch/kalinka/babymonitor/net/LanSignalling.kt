package ch.kalinka.babymonitor.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Finding the other phone on the same WiFi and handing it a signalling message directly, with no
 * server in the middle.
 *
 * The media was never going through the backend; this is the last thing that was. With it, two
 * phones in one house keep working through an internet outage — which is the failure a monitor
 * is most likely to meet and least able to excuse.
 *
 * Every message carries a signature from the sender's identity key, checked against the key this
 * phone confirmed by scanning the other's screen. Anything that fails that check is dropped
 * without being read: on a local socket there is nothing else to go on.
 */
class LanSignalling(
    private val context: Context,
    private val thisDeviceId: () -> String?,

    /** The key this phone has confirmed for that device, or null if it has not. */
    private val confirmedKeyFor: (String) -> String?,
    private val onSignal: (SignalMessage) -> Unit,

    /**
     * A message from a phone whose key is not confirmed. Only pairing arrives this way, and only
     * because it carries its own proof: everything else is dropped where the signature fails.
     */
    private val onUnverified: (SignalMessage) -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val peers = ConcurrentHashMap<String, InetSocketAddress>()
    private val inFlight = Semaphore(MaxConcurrentReads)

    private var nsd: NsdManager? = null
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    /** The phones reachable on this network right now. */
    fun reachable(deviceId: String): Boolean = peers.containsKey(deviceId)

    fun start() {
        val device = thisDeviceId() ?: return
        if (server != null) {
            return
        }

        runCatching {
            val socket = ServerSocket(0)
            server = socket
            nsd = context.getSystemService(NsdManager::class.java)
            listen(socket)
            register(device, socket.localPort)
            discover()
            Log.i(Tag, "Listening for phones on this network as $device, port ${socket.localPort}")
        }.onFailure { Log.w(Tag, "No local signalling on this network", it) }
    }

    fun stop() {
        Log.i(Tag, "Leaving the local network")
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        runCatching { server?.close() }
        registration = null
        discovery = null
        server = null
        peers.clear()
    }

    /**
     * Hands one already-signed message straight to the other phone. False when it is not on this
     * network, or would not take it — the caller falls back to the server, which is what it did
     * before. The signature travels as it was made, so the same message is what the other phone
     * would have received over the hub.
     */
    suspend fun send(message: SignalMessage): Boolean = withContext(Dispatchers.IO) {
        val address = peers[message.toDeviceId] ?: return@withContext false

        runCatching {
            Socket().use { socket ->
                socket.connect(address, ConnectTimeout)
                socket.getOutputStream().writer().use { writer ->
                    writer.write(json.encodeToString(message))
                    writer.write("\n")
                    writer.flush()
                }
            }
            true
        }.getOrElse {
            // Gone from the network without saying so; the server is the fallback for that.
            peers.remove(message.toDeviceId)
            false
        }
    }

    private fun listen(socket: ServerSocket) {
        scope.launch {
            while (!socket.isClosed) {
                val accepted = runCatching { socket.accept() }.getOrNull() ?: break

                // Anyone on the WiFi can open one of these, and nothing has been authenticated
                // yet at this point. A cap on how many are being read at once, so a phone in a
                // nursery cannot be held down by a pile of connections that never say anything.
                if (!inFlight.tryAcquire()) {
                    Log.w(Tag, "Too many local connections at once; dropping one unread")
                    runCatching { accepted.close() }
                    continue
                }

                launch {
                    try {
                        receive(accepted)
                    } finally {
                        inFlight.release()
                    }
                }
            }
        }
    }

    private fun receive(socket: Socket) {
        runCatching {
            socket.use {
                // Both guards are against the same thing: an unauthenticated stranger on the WiFi.
                // Without the timeout a connection that opens and never writes holds this
                // coroutine for ever, and without the cap a sender that never sends a newline
                // grows a String until the process dies.
                it.soTimeout = ReadTimeout
                val line = it.getInputStream().readLine(MaxEnvelopeBytes) ?: return
                val message = json.decodeFromString<SignalMessage>(line)

                // The whole authentication of this path. A phone whose key was never confirmed
                // cannot be told apart from anybody else on the WiFi, so it is not listened to.
                // Naming a different sender only picks a different key to fail against, so the
                // sender is still whoever signed it rather than whoever the message says.
                val trust = message.trust(confirmedKeyFor(message.fromDeviceId))
                if (!trust.isVerified) {
                    if (message.kind == SignalKinds.Pair) {
                        // The one thing a stranger may say, because it arrives with the proof
                        // that it was read off this phone's own screen a moment ago.
                        onUnverified(message)
                    } else {
                        Log.w(
                            Tag,
                            "Dropped a ${message.kind} from ${message.fromDeviceId}: ${trust::class.simpleName}"
                        )
                    }

                    return
                }

                onSignal(message)
            }
        }
    }

    private fun register(deviceId: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "babymonitor"
            serviceType = ServiceType
            setPort(port)
            setAttribute(DeviceAttribute, deviceId)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(Tag, "Could not announce this phone on the network: $code")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }

        registration = listener
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                // Keyed by the device id out of the TXT record, which is how it went in. The
                // service name is "babymonitor" for every phone — matching on it removed nothing,
                // so a phone that left the network stayed listed as reachable.
                val device = info.attributes[DeviceAttribute]?.decodeToString()
                if (device != null) {
                    peers.remove(device)
                    Log.i(Tag, "$device left this network")
                    return
                }

                // Resolution has usually been forgotten by the time a service goes away, leaving
                // no attributes to read. Falling back to the address keeps the list honest.
                val host = info.host?.hostAddress ?: return
                peers.entries.removeIf { it.value.address?.hostAddress == host }
            }

            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(Tag, "Could not look for phones on the network: $code")
            }

            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }

        discovery = listener
        nsd?.discoverServices(ServiceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        nsd?.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val device = resolved.attributes[DeviceAttribute]?.decodeToString() ?: return
                    if (device == thisDeviceId()) {
                        return
                    }

                    val host: InetAddress = resolved.host ?: return
                    peers[device] = InetSocketAddress(host, resolved.port)
                    Log.i(Tag, "Found $device on this network at ${host.hostAddress}")
                }

                override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
            }
        )
    }

    private companion object {
        const val Tag = "LanSignalling"
        const val ServiceType = "_babymonitor._tcp."
        const val DeviceAttribute = "device"
        const val ConnectTimeout = 2_000

        /** Long enough for a phone on the same WiFi to finish a sentence, short enough to give up on. */
        const val ReadTimeout = 5_000

        /** The same ceiling the hub puts on a signal: room for an SDP with video, and no more. */
        const val MaxEnvelopeBytes = 32 * 1024

        /** More than two phones pairing could ever need at once. */
        const val MaxConcurrentReads = 8
    }
}
