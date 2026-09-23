package ch.kalinka.babymonitor.integration

import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One account talking to a real backend, over the same two connections the app uses: cookie-authed
 * HTTP and a hub connection carrying this phone's device header.
 *
 * Deliberately not built on [ch.kalinka.babymonitor.net.ApiClient] — that one needs an Android Context
 * for its cookie store, and the point here is to exercise the server from a plain JVM test.
 */
class Backend(private val baseUrl: String) {
    private val jar = mutableMapOf<String, Cookie>()

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .cookieJar(object : okhttp3.CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { jar[it.name] = it }
            }

            override fun loadForRequest(url: HttpUrl) = jar.values.toList()
        })
        .build()

    val json = Json { ignoreUnknownKeys = true }

    fun logIn(email: String, password: String) {
        post("api/auth/login", """{"email":"$email","password":"$password"}""")
    }

    /** Registration is idempotent on the key, so a re-run reuses the same row. */
    fun registerDevice(name: String, publicKey: String): String =
        post("api/devices", """{"name":"$name","publicKey":"$publicKey"}""").field("id")

    fun pairingCode(deviceId: String): String =
        post("api/pairing/code", "{}", deviceId).field("code")

    fun claim(code: String, deviceId: String) {
        post("api/pairing/claim", """{"code":"$code","proof":""}""", deviceId)
    }

    /** Whether this account can see that phone at all, which is what a link decides. */
    fun canSee(deviceId: String): Boolean =
        json.parseToJsonElement(get("api/devices")).jsonArray
            .any { it.jsonObject["id"]?.jsonPrimitive?.content == deviceId }

    /** The account a visible device belongs to, which is what a link is dropped by. */
    fun ownerOf(deviceId: String): String =
        json.parseToJsonElement(get("api/devices")).jsonArray
            .map { it.jsonObject }
            .first { it["id"]?.jsonPrimitive?.content == deviceId }["ownerId"]!!
            .jsonPrimitive.content

    fun unlinkFrom(userId: String) {
        delete("api/pairing/link/$userId")
    }

    /** A hub connection that names its device, which is what lets it be signalled at all. */
    fun connectHub(deviceId: String): Hub {
        val hub = HubConnectionBuilder.create("$baseUrl/hubs/devices")
            .withHeader("Cookie", jar.values.joinToString("; ") { "${it.name}=${it.value}" })
            .withHeader("X-Device-Id", deviceId)
            .build()

        val received = LinkedBlockingQueue<String>()
        hub.on("signal", { payload: String -> received.add(payload) }, String::class.java)
        hub.start().blockingAwait(20, TimeUnit.SECONDS)
        return Hub(hub, received, json)
    }

    private fun get(path: String): String =
        client.newCall(Request.Builder().url("$baseUrl/$path").build()).execute().use { response ->
            check(response.isSuccessful) { "GET $path failed: ${response.code}" }
            response.body.string()
        }

    private fun post(path: String, body: String, deviceId: String? = null): String =
        client.newCall(
            Request.Builder()
                .url("$baseUrl/$path")
                .post(body.toRequestBody("application/json".toMediaType()))
                .apply { deviceId?.let { header("X-Device-Id", it) } }
                .build()
        ).execute().use { response ->
            check(response.isSuccessful) { "POST $path failed: ${response.code} ${response.body.string()}" }
            response.body.string()
        }

    private fun delete(path: String) {
        client.newCall(Request.Builder().url("$baseUrl/$path").delete().build()).execute().use { response ->
            check(response.isSuccessful) { "DELETE $path failed: ${response.code}" }
        }
    }

    private fun String.field(name: String): String =
        json.parseToJsonElement(this).jsonObject[name]!!.jsonPrimitive.content

    companion object {
        /** Where the backend under test is. Set BABYMONITOR_URL to point at another one. */
        val Url: String = System.getenv("BABYMONITOR_URL") ?: "http://127.0.0.1:5199"

        fun isRunning(): Boolean = runCatching {
            OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("$Url/api/auth/status").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}

/** One phone's hub connection, and whatever it has been sent. */
class Hub(
    private val connection: HubConnection,
    private val received: LinkedBlockingQueue<String>,
    private val json: Json
) {
    /** Answers whether the other phone was connected to take it. */
    fun signal(toDeviceId: String, kind: String, body: String): Boolean =
        connection.invoke(
            Boolean::class.java,
            "Signal",
            """{"toDeviceId":"$toDeviceId","kind":"$kind","body":${json.encodeToString(body)}}"""
        ).blockingGet()

    fun nextSignal(seconds: Long = 5): Signal? =
        received.poll(seconds, TimeUnit.SECONDS)?.let { json.decodeFromString<Signal>(it) }

    fun close() = connection.stop().blockingAwait(5, TimeUnit.SECONDS)
}

@kotlinx.serialization.Serializable
data class Signal(
    val fromDeviceId: String = "",
    val toDeviceId: String = "",
    val kind: String = "",
    val body: String = ""
)
