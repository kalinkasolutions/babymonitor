package ch.kalinka.babymonitor.integration

import ch.kalinka.babymonitor.device.deviceIdOf
import ch.kalinka.babymonitor.net.serverAuthHeaders
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A phone talking to a real relay: its own P-256 key, signed in the same way the app signs in.
 * Built on plain JVM classes because the app's own key lives in the Android keystore.
 */
class TestPhone(private val baseUrl: String = Backend.Url) {
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    val publicKey: String = Base64.getEncoder().encodeToString(keys.public.encoded)
    val deviceId: String = deviceIdOf(publicKey)

    private val json = Json { ignoreUnknownKeys = true }

    fun authHeaders(now: Long = System.currentTimeMillis()): Map<String, String> =
        serverAuthHeaders("", publicKey, now) { bytes ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(bytes)
                sign()
            }
        }

    fun connectHub(): Hub {
        val hub = HubConnectionBuilder.create("$baseUrl/hubs/devices")
            .withHeaders(authHeaders())
            .build()

        val received = LinkedBlockingQueue<String>()
        val presence = LinkedBlockingQueue<String>()
        hub.on("signal", { payload: String -> received.add(payload) }, String::class.java)
        hub.on("devicePresence", { payload: String -> presence.add(payload) }, String::class.java)
        hub.start().blockingAwait(20, TimeUnit.SECONDS)
        return Hub(hub, received, presence, json)
    }

    /** The HTTP status of the relay credentials request. */
    fun iceServersStatus(headers: Map<String, String> = authHeaders()): Int =
        OkHttpClient().newCall(
            Request.Builder()
                .url("$baseUrl/api/turn/credentials")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        ).execute().use { it.code }
}

object Backend {
    /** Where the relay under test is. Override with BABYMONITOR_URL. */
    val Url: String = System.getenv("BABYMONITOR_URL") ?: "http://127.0.0.1:5199"

    fun isRunning(): Boolean = runCatching {
        OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url("$Url/api/status").build())
            .execute().use { it.isSuccessful }
    }.getOrDefault(false)
}

/** One phone's hub connection, and whatever it has been sent. */
class Hub(
    private val connection: HubConnection,
    private val received: LinkedBlockingQueue<String>,
    private val presence: LinkedBlockingQueue<String>,
    private val json: Json
) {
    /** Answers whether the other phone was connected to take it. */
    fun signal(toDeviceId: String, kind: String, body: String): Boolean =
        connection.invoke(
            Boolean::class.java,
            "Signal",
            """{"toDeviceId":"$toDeviceId","kind":"$kind","body":${json.encodeToString(body)}}"""
        ).blockingGet()

    fun watch(vararg deviceIds: String): List<String> =
        connection.invoke(Array<String>::class.java, "Watch", deviceIds).blockingGet().toList()

    fun nextSignal(seconds: Long = 5): Signal? =
        received.poll(seconds, TimeUnit.SECONDS)?.let { json.decodeFromString<Signal>(it) }

    fun nextPresence(seconds: Long = 5): Presence? =
        presence.poll(seconds, TimeUnit.SECONDS)?.let { json.decodeFromString<Presence>(it) }

    fun close() = connection.stop().blockingAwait(5, TimeUnit.SECONDS)
}

@kotlinx.serialization.Serializable
data class Signal(
    val fromDeviceId: String = "",
    val toDeviceId: String = "",
    val kind: String = "",
    val body: String = ""
)

@kotlinx.serialization.Serializable
data class Presence(val deviceId: String = "", val isOnline: Boolean = false)
