package ch.kalinka.babymonitor.net

import android.content.Context
import ch.kalinka.babymonitor.device.DeviceIdentity
import ch.kalinka.babymonitor.log.AppLog
import ch.kalinka.babymonitor.log.LogLevel
import ch.kalinka.babymonitor.log.uploadLevelFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Either a decoded body or a message fit to put in front of the user. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failure(val message: String) : ApiResult<Nothing>
}

/** Refused means the server will never take that batch; Failed means try it again later. */
sealed interface LogUpload {
    /** [level] is what the server wants uploaded from now on; null from a server too old to say. */
    data class Sent(val level: LogLevel?) : LogUpload
    data object Refused : LogUpload
    data object Failed : LogUpload
}

/** The relay's plain HTTP calls: relay credentials, a check that the settings work, and log upload. */
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
        serverAuthHeaders(DeviceIdentity.publicKey(), System.currentTimeMillis(), DeviceIdentity::sign)

    suspend fun iceServers(): ApiResult<IceServersDto> =
        get("api/turn/credentials", authHeaders(), "The server did not accept this phone's signature.") {
            json.decodeFromString(it)
        }

    /** Whether the address answers. */
    suspend fun check(): ApiResult<Unit> =
        get("api/status", emptyMap(), "The server refused the check.") { }

    /** Never logs anything itself: a line about each upload would be uploaded too, forever. */
    suspend fun uploadLogs(body: String): LogUpload = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext LogUpload.Failed
        }

        try {
            val request = Request.Builder()
                .url("$baseUrl/api/logs")
                .apply { authHeaders().forEach { (name, value) -> header(name, value) } }
                .post(body.toRequestBody(JsonMediaType))
                .build()

            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> LogUpload.Sent(uploadLevelFrom(response.body.string()))
                    response.code == 400 || response.code == 413 -> LogUpload.Refused
                    else -> LogUpload.Failed
                }
            }
        } catch (_: IOException) {
            LogUpload.Failed
        } catch (_: IllegalArgumentException) {
            LogUpload.Failed
        }
    }

    private suspend fun <T> get(
        path: String,
        headers: Map<String, String>,
        refused: String,
        decode: (String) -> T
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext ApiResult.Failure("No server set.")
        }

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
                        AppLog.w(Tag, "$route refused with 401: $refused")
                        ApiResult.Failure(refused)
                    }

                    !response.isSuccessful -> {
                        val message = problemMessage(body, response.code)
                        AppLog.w(Tag, "$route refused with ${response.code}: $message")
                        ApiResult.Failure(message)
                    }

                    else -> {
                        AppLog.i(Tag, "$route ${response.code}")
                        ApiResult.Ok(decode(body))
                    }
                }
            }
        } catch (e: IOException) {
            AppLog.w(Tag, "$route could not reach $baseUrl", e)
            ApiResult.Failure("Cannot reach the server at $baseUrl.")
        } catch (e: IllegalArgumentException) {
            AppLog.e(Tag, "$route has an unusable server address: $baseUrl", e)
            ApiResult.Failure("That server address is not valid.")
        }
    }

    private fun problemMessage(body: String, code: Int): String =
        runCatching { json.decodeFromString<ProblemDetails>(body).message() }
            .getOrElse { "Request failed ($code)." }

    private companion object {
        const val Tag = "ApiClient"
        val JsonMediaType = "application/json".toMediaType()
    }
}
