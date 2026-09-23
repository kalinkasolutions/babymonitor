package ch.kalinka.babymonitor.auth

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.kalinka.babymonitor.device.DeviceIdentity
import ch.kalinka.babymonitor.device.LocalSession
import ch.kalinka.babymonitor.media.CallCenter
import ch.kalinka.babymonitor.device.readBattery
import ch.kalinka.babymonitor.net.ApiClient
import ch.kalinka.babymonitor.net.ApiResult
import ch.kalinka.babymonitor.net.HeartbeatRequest
import ch.kalinka.babymonitor.net.LoginRequest
import ch.kalinka.babymonitor.net.RegisterDeviceRequest
import ch.kalinka.babymonitor.net.RegisterRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val api = ApiClient(application)
    private val local = LocalSession(application)

    private val _state = MutableStateFlow<AuthState>(AuthState.Checking)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var serverUrl: String
        get() = api.baseUrl
        set(value) {
            api.baseUrl = value
        }

    init {
        restoreSession()
    }

    /**
     * Starts using the app with no account at all. Everything the server did has a local answer:
     * the identity is the key already in the keystore, pairing is the scan itself, and the phones
     * find each other on the WiFi. What it gives up is everything that has to leave the house.
     */
    fun continueWithoutAccount() {
        Log.i(Tag, "Using the app without an account; nothing will leave this network")
        CallCenter.reset()
        local.enabled = true
        _state.value = AuthState.SignedIn(local.name)
    }

    /**
     * Asks the server whether the stored cookie is still good. A server that cannot be reached
     * lands on the login screen rather than an error: the address may simply need changing, and
     * that field is on the login screen.
     */
    fun restoreSession() {
        if (local.enabled) {
            _state.value = AuthState.SignedIn(local.name)
            return
        }

        viewModelScope.launch {
            _state.value = AuthState.Checking
            when (val result = api.status()) {
                is ApiResult.Ok ->
                    if (result.value.authenticated) {
                        Log.i(Tag, "The stored session is still good")
                        signedIn(result.value.username)
                    } else {
                        Log.i(Tag, "The stored session has expired or was ended elsewhere")
                        _state.value = AuthState.SignedOut()
                    }

                is ApiResult.Failure -> {
                    // Not necessarily signed out — the address may simply be wrong, which is a
                    // field on the screen this lands on.
                    Log.w(Tag, "Could not check the session: ${result.message}")
                    _state.value = AuthState.SignedOut()
                }
            }
        }
    }

    fun login(email: String, password: String) {
        whileBusy { signIn(email, password) }
    }

    fun register(email: String, username: String, password: String) {
        whileBusy {
            when (val result = api.register(RegisterRequest(email.trim(), username.trim(), password))) {
                // Registration does not sign anyone in, so go straight on and log in with the
                // credentials the server just accepted.
                is ApiResult.Ok -> {
                    Log.i(Tag, "Registered a new account; signing in with it")
                    signIn(email, password)
                }

                is ApiResult.Failure -> {
                    Log.w(Tag, "Registration refused: ${result.message}")
                    _error.value = result.message
                }
            }
        }
    }

    fun logout() {
        whileBusy {
            api.logout()
            api.clearSession()
            _state.value = AuthState.SignedOut()
        }
    }

    /** The session ended elsewhere in the app — the account was deleted, or signed out everywhere. */
    fun leaveLocalMode() {
        CallCenter.reset()
        local.enabled = false
        _state.value = AuthState.SignedOut()
    }

    fun forgetSession() {
        Log.i(Tag, "Leaving the session")

        // Whichever way this phone was being used, it is being put down now.
        CallCenter.reset()
        local.enabled = false

        _error.value = null
        _state.value = AuthState.SignedOut()
    }

    fun showRegister(show: Boolean) {
        _error.value = null
        _state.value = AuthState.SignedOut(showRegister = show)
    }

    fun dismissError() {
        _error.value = null
    }

    fun submitTwoFactorCode(code: String) {
        whileBusy {
            when (val result = api.loginTwoFactor(code.trim())) {
                is ApiResult.Ok -> signedIn(result.value.username)
                is ApiResult.Failure -> {
                    Log.w(Tag, "Two-factor code refused: ${result.message}")
                    _error.value = result.message
                }
            }
        }
    }

    /** Abandons a sign-in waiting on a code and goes back to the password screen. */
    fun cancelTwoFactor() {
        _error.value = null
        _state.value = AuthState.SignedOut()
    }

    private suspend fun signIn(email: String, password: String) {
        when (val result = api.login(LoginRequest(email.trim(), password))) {
            is ApiResult.Ok ->
                if (result.value.requiresTwoFactor) {
                    Log.i(Tag, "Password accepted; waiting on a two-factor code")
                    _state.value = AuthState.TwoFactorRequired
                } else {
                    signedIn(result.value.username)
                }

            is ApiResult.Failure -> {
                // The address is never logged: a failed sign-in is the one line most likely to be
                // read by somebody who should not be reading it.
                Log.w(Tag, "Sign-in refused: ${result.message}")
                _error.value = result.message
            }
        }
    }

    /**
     * Enters the signed-in state, registering this phone on the way in.
     *
     * Registration belongs here rather than on the Devices screen: a phone that signs in and goes
     * straight to pairing would otherwise have no code to show and would be missing from the
     * other phone's list. It is idempotent on the identity key, so doing it on every sign-in and
     * every session restore costs one request and keeps the name and last-seen time current.
     */
    private suspend fun signedIn(username: String) {
        Log.i(Tag, "Signed in at ${api.baseUrl}")

        // A session that starts is as much of a change as one that ends: the phones from the last
        // one are not this one's.
        CallCenter.reset()
        registerThisDevice()
        _state.value = AuthState.SignedIn(username)
    }

    private suspend fun registerThisDevice() {
        val request = RegisterDeviceRequest(
            name = DeviceIdentity.defaultName(),
            publicKey = DeviceIdentity.publicKey()
        )

        // A failure here is not a failed sign-in — the account is fine and the device list simply
        // stays stale until the next launch, so it is not surfaced as a login error. It is worth a
        // line, though: a phone missing from the other one's list is exactly this, hours later.
        val registered = api.registerDevice(request) as? ApiResult.Ok ?: return run {
            Log.e(Tag, "Signed in, but this phone could not register itself; it will be missing from the device list")
        }

        api.deviceId = registered.value.id
        Log.i(Tag, "Registered as device ${registered.value.id} (${registered.value.keyFingerprint})")

        val battery = readBattery(getApplication())
        api.heartbeat(registered.value.id, HeartbeatRequest(battery.percent, battery.isCharging))
    }

    /** Runs [block] with the busy flag held and any previous error cleared. */
    private fun whileBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        const val Tag = "AuthViewModel"
    }
}
