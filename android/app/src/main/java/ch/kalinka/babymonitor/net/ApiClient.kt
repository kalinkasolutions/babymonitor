package ch.kalinka.babymonitor.net

import android.content.Context
import android.util.Log
import ch.kalinka.babymonitor.device.DeviceRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

class ApiClient(context: Context) {
    private val cookieJar = PersistentCookieJar(SharedPreferencesCookieStore(context.applicationContext))
    private val settings = ServerSettings(context.applicationContext)
    private val registration = DeviceRegistration(context.applicationContext)

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        // Tells the server which of the account's phones is calling, so it can mark one entry in
        // the device list as this one. Not a credential — the cookie is.
        .addInterceptor { chain ->
            val deviceId = registration.deviceId
            val request = if (deviceId == null) {
                chain.request()
            } else {
                chain.request().newBuilder().header(DEVICE_ID_HEADER, deviceId).build()
            }
            chain.proceed(request)
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    var baseUrl: String
        get() = settings.baseUrl
        set(value) {
            settings.baseUrl = value
        }

    suspend fun register(request: RegisterRequest): ApiResult<Unit> =
        post("api/auth/register", json.encodeToString(request)) { Unit }

    suspend fun login(request: LoginRequest): ApiResult<LoginResponse> =
        post("api/auth/login", json.encodeToString(request)) { json.decodeFromString(it) }

    suspend fun loginTwoFactor(code: String): ApiResult<LoginResponse> =
        post("api/auth/login-2fa", json.encodeToString(TwoFactorLoginRequest(code))) {
            json.decodeFromString(it)
        }

    suspend fun logout(): ApiResult<Unit> = post("api/auth/logout", "{}") { Unit }

    suspend fun status(): ApiResult<AuthStatus> = get("api/auth/status") { json.decodeFromString(it) }

    suspend fun account(): ApiResult<AccountDto> = get("api/account") { json.decodeFromString(it) }

    suspend fun updateUsername(username: String): ApiResult<AccountDto> =
        put("api/account/username", json.encodeToString(UpdateUsernameRequest(username))) {
            json.decodeFromString(it)
        }

    suspend fun changePassword(request: ChangePasswordRequest): ApiResult<Unit> =
        post("api/account/password", json.encodeToString(request)) { Unit }

    suspend fun requestEmailChange(request: ChangeEmailRequest): ApiResult<Unit> =
        post("api/account/email", json.encodeToString(request)) { Unit }

    suspend fun signOutEverywhere(): ApiResult<Unit> =
        post("api/account/sign-out-everywhere", "{}") { Unit }

    suspend fun twoFactorSetup(): ApiResult<TwoFactorSetupDto> =
        get("api/account/2fa/setup") { json.decodeFromString(it) }

    suspend fun enableTwoFactor(code: String): ApiResult<RecoveryCodesDto> =
        post("api/account/2fa/enable", json.encodeToString(CodeRequest(code))) { json.decodeFromString(it) }

    suspend fun disableTwoFactor(password: String): ApiResult<Unit> =
        post("api/account/2fa/disable", json.encodeToString(PasswordRequest(password))) { Unit }

    suspend fun regenerateRecoveryCodes(password: String): ApiResult<RecoveryCodesDto> =
        post("api/account/2fa/recovery-codes", json.encodeToString(PasswordRequest(password))) {
            json.decodeFromString(it)
        }

    suspend fun deleteAccount(password: String): ApiResult<Unit> =
        delete("api/account", json.encodeToString(PasswordRequest(password))) { Unit }

    suspend fun devices(): ApiResult<List<DeviceDto>> =
        get("api/devices") { json.decodeFromString(it) }

    suspend fun registerDevice(request: RegisterDeviceRequest): ApiResult<DeviceDto> =
        post("api/devices", json.encodeToString(request)) { json.decodeFromString(it) }

    suspend fun heartbeat(deviceId: String, request: HeartbeatRequest): ApiResult<Unit> =
        post("api/devices/$deviceId/heartbeat", json.encodeToString(request)) { Unit }

    suspend fun revokeDevice(deviceId: String): ApiResult<Unit> =
        delete("api/devices/$deviceId", "{}") { Unit }

    suspend fun pairingCode(): ApiResult<PairingCodeDto> =
        post("api/pairing/code", "{}") { json.decodeFromString(it) }

    /** Drops the link to another account, which takes their phones out of the list. */
    suspend fun unlinkAccount(ownerId: String): ApiResult<Unit> =
        delete("api/pairing/link/$ownerId", "{}") { Unit }

    suspend fun claimPairing(code: String, proof: String): ApiResult<DeviceDto> =
        post("api/pairing/claim", json.encodeToString(ClaimPairingRequest(code, proof))) {
            json.decodeFromString(it)
        }

    /** The id the backend gave this phone, remembered across launches. */
    var deviceId: String?
        get() = registration.deviceId
        set(value) {
            registration.deviceId = value
        }

    suspend fun invite(request: InviteRequest): ApiResult<Unit> =
        post("api/auth/invite", json.encodeToString(request)) { Unit }

    suspend fun iceServers(): ApiResult<IceServersDto> =
        get("api/turn/credentials") { json.decodeFromString(it) }

    /** The auth cookie header, for the hub connection which cannot share this client's jar. */
    fun cookieHeader(): String =
        baseUrl.toHttpUrlOrNull()?.let { cookieJar.headerFor(it) }.orEmpty()

    /** Forgets the session locally. Used when the server says the cookie is no longer good. */
    fun clearSession() = cookieJar.clear()

    /**
     * The address, or nothing. A blank one is a real state — a fresh install, or somebody who
     * cleared the field — and building a request from it throws before any error handling can
     * catch it, which is a crash where a sentence would do.
     */
    private fun urlFor(path: String): String? =
        baseUrl.takeIf { it.isNotBlank() }?.let { "$it/$path" }

    private fun <T> noServer(): ApiResult<T> =
        ApiResult.Failure("No server address. Set one, or use the app without an account.")

    private suspend fun <T> get(path: String, decode: (String) -> T): ApiResult<T> {
        val url = urlFor(path) ?: return noServer()
        return execute(Request.Builder().url(url).get().build(), decode)
    }

    private suspend fun <T> put(path: String, body: String, decode: (String) -> T): ApiResult<T> {
        val url = urlFor(path) ?: return noServer()
        return execute(
            Request.Builder().url(url).put(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
            decode
        )
    }

    private suspend fun <T> delete(path: String, body: String, decode: (String) -> T): ApiResult<T> {
        val url = urlFor(path) ?: return noServer()
        return execute(
            Request.Builder().url(url).delete(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
            decode
        )
    }

    private suspend fun <T> post(path: String, body: String, decode: (String) -> T): ApiResult<T> {
        val url = urlFor(path) ?: return noServer()
        return execute(
            Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
            decode
        )
    }

    /**
     * Every outcome is logged, because the message handed back is written for somebody standing in
     * a nursery and deliberately says less than the truth: "cannot reach the server" is the same
     * sentence for a typo, a flat WiFi and a backend that is down. The log is where the difference
     * survives. Only the method, the path and the status are recorded — never a body, which is
     * where the passwords and the tokens are.
     */
    private suspend fun <T> execute(request: Request, decode: (String) -> T): ApiResult<T> =
        withContext(Dispatchers.IO) {
            val route = "${request.method} ${request.url.encodedPath}"
            try {
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val message = problemMessage(body, response.code)
                        Log.w(Tag, "$route refused with ${response.code}: $message")
                        return@use ApiResult.Failure(message)
                    }

                    Log.i(Tag, "$route ${response.code}")
                    ApiResult.Ok(decode(body))
                }
            } catch (e: IOException) {
                // Wrong address, server down, no WiFi — all the same thing to someone standing in
                // a nursery, so say the one useful thing rather than surfacing the exception.
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

    companion object {
        private const val Tag = "ApiClient"

        /** Also sent on the hub connection, which is how the server can address one phone. */
        const val DEVICE_ID_HEADER = "X-Device-Id"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
