package ch.lqy.babyphone.net

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
import java.io.BufferedReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

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
        }.onFailure { Log.w(Tag, "No local signalling on this network", it) }
    }

    fun stop() {
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        runCatching { server?.close() }
        registration = null
        discovery = null
        server = null
        peers.clear()
    }

    /**
     * Hands one message straight to the other phone. False when it is not on this network, or
     * would not take it — the caller falls back to the server, which is what it did before.
     */
    suspend fun send(message: SignalMessage): Boolean = withContext(Dispatchers.IO) {
        val address = peers[message.toDeviceId] ?: return@withContext false
        val from = thisDeviceId() ?: return@withContext false
        val payload = json.encodeToString(message)

        val signature = runCatching {
            java.util.Base64.getEncoder()
                .encodeToString(ch.lqy.babyphone.device.DeviceIdentity.sign(payload.toByteArray()))
        }.getOrNull() ?: return@withContext false

        runCatching {
            Socket().use { socket ->
                socket.connect(address, ConnectTimeout)
                socket.getOutputStream().writer().use { writer ->
                    writer.write(json.encodeToString(LanEnvelope(from, payload, signature)))
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
                launch { receive(accepted) }
            }
        }
    }

    private fun receive(socket: Socket) {
        runCatching {
            socket.use {
                val line = it.getInputStream().bufferedReader().use(BufferedReader::readLine)
                    ?: return
                val envelope = json.decodeFromString<LanEnvelope>(line)

                // The whole authentication of this path. A phone whose key was never confirmed
                // cannot be told apart from anybody else on the WiFi, so it is not listened to.
                val message = json.decodeFromString<SignalMessage>(envelope.signal)
                if (!envelope.isSignedBy(confirmedKeyFor(envelope.from))) {
                    if (message.kind == SignalKinds.Pair) {
                        // The one thing a stranger may say, because it arrives with the proof
                        // that it was read off this phone's own screen a moment ago.
                        onUnverified(message.copy(fromDeviceId = envelope.from))
                    } else {
                        Log.w(Tag, "Dropped a local signal that was not signed by a confirmed key")
                    }

                    return
                }

                // The sender is whoever signed it, never whoever the message says.
                onSignal(message.copy(fromDeviceId = envelope.from))
            }
        }
    }

    private fun register(deviceId: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "babyphone"
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
                peers.entries.removeIf { it.key == info.serviceName }
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
        const val ServiceType = "_babyphone._tcp."
        const val DeviceAttribute = "device"
        const val ConnectTimeout = 2_000
    }
}
