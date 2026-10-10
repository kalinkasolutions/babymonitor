package ch.kalinka.babymonitor.net

import android.content.Context
import android.util.Log
import ch.kalinka.babymonitor.device.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Either a decoded body or a message fit to put in front of the user. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failure(val message: String) : ApiResult<Nothing>
}

/** The relay's two plain HTTP calls: relay credentials, and a check that the settings work. */
class ApiClient(context: Context) {
    private val settings = ServerSettings(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val baseUrl: String
        get() = settings.baseUrl

    val isConfigured: Boolean
        get() = settings.isConfigured

    /** Signed fresh for every request or connection, because the server only accepts recent ones. */
    fun authHeaders(): Map<String, String> =
        serverAuthHeaders(settings.key, DeviceIdentity.publicKey(), System.currentTimeMillis(), DeviceIdentity::sign)

    suspend fun iceServers(): ApiResult<IceServersDto> =
        get("api/turn/credentials", authHeaders()) { json.decodeFromString(it) }

    /** Whether the address answers and the key is accepted, told apart so the user knows which to fix. */
    suspend fun check(): ApiResult<Unit> =
        get("api/status/key", mapOf("X-Server-Key" to settings.key)) { }

    private suspend fun <T> get(
        path: String,
        headers: Map<String, String>,
        decode: (String) -> T
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext ApiResult.Failure("No server set.")
        }

        // Logs the route and the status only: the headers carry the server key.
        val route = "GET /$path"
        try {
            val request = Request.Builder()
                .url("$baseUrl/$path")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .get()
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                when {
                    response.code == 401 -> {
                        Log.w(Tag, "$route refused: wrong server key or signature")
                        ApiResult.Failure("The server did not accept the server key.")
                    }

                    !response.isSuccessful -> {
                        val message = problemMessage(body, response.code)
                        Log.w(Tag, "$route refused with ${response.code}: $message")
                        ApiResult.Failure(message)
                    }

                    else -> {
                        Log.i(Tag, "$route ${response.code}")
                        ApiResult.Ok(decode(body))
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(Tag, "$route could not reach $baseUrl", e)
            ApiResult.Failure("Cannot reach the server at $baseUrl.")
        } catch (e: IllegalArgumentException) {
            Log.e(Tag, "$route has an unusable server address: $baseUrl", e)
            ApiResult.Failure("That server address is not valid.")
        }
    }

    private fun problemMessage(body: String, code: Int): String =
        runCatching { json.decodeFromString<ProblemDetails>(body).message() }
            .getOrElse { "Request failed ($code)." }

    private companion object {
        const val Tag = "ApiClient"
    }
}
