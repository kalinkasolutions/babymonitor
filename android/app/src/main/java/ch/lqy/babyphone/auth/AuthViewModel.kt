package ch.lqy.babyphone.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.lqy.babyphone.device.DeviceIdentity
import ch.lqy.babyphone.device.LocalSession
import ch.lqy.babyphone.device.readBattery
import ch.lqy.babyphone.net.ApiClient
import ch.lqy.babyphone.net.ApiResult
import ch.lqy.babyphone.net.HeartbeatRequest
import ch.lqy.babyphone.net.LoginRequest
import ch.lqy.babyphone.net.RegisterDeviceRequest
import ch.lqy.babyphone.net.RegisterRequest
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
     * Asks the server whether the stored cookie is still good. A server that cannot be reached
     * lands on the login screen rather than an error: the address may simply need changing, and
     * that field is on the login screen.
     */
    /**
     * Starts using the app with no account at all. Everything the server did has a local answer:
     * the identity is the key already in the keystore, pairing is the scan itself, and the phones
     * find each other on the WiFi. What it gives up is everything that has to leave the house.
     */
    fun continueWithoutAccount() {
        local.enabled = true
        _state.value = AuthState.SignedIn(local.name)
    }

    fun restoreSession() {
        if (local.enabled) {
            _state.value = AuthState.SignedIn(local.name)
            return
        }

        viewModelScope.launch {
            _state.value = AuthState.Checking
            when (val result = api.status()) {
                is ApiResult.Ok ->
                    if (result.value.authenticated) signedIn(result.value.username)
                    else _state.value = AuthState.SignedOut()

                is ApiResult.Failure -> _state.value = AuthState.SignedOut()
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
                is ApiResult.Ok -> signIn(email, password)
                is ApiResult.Failure -> _error.value = result.message
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
        local.enabled = false
        _state.value = AuthState.SignedOut()
    }

    fun forgetSession() {
        // Whichever way this phone was being used, it is being put down now.
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
                is ApiResult.Failure -> _error.value = result.message
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
                    _state.value = AuthState.TwoFactorRequired
                } else {
                    signedIn(result.value.username)
                }

            is ApiResult.Failure -> _error.value = result.message
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
        registerThisDevice()
        _state.value = AuthState.SignedIn(username)
    }

    private suspend fun registerThisDevice() {
        val request = RegisterDeviceRequest(
            name = DeviceIdentity.defaultName(),
            publicKey = DeviceIdentity.publicKey()
        )

        // A failure here is not a failed sign-in — the account is fine and the device list simply
        // stays stale until the next launch, so it is not surfaced as a login error.
        val registered = api.registerDevice(request) as? ApiResult.Ok ?: return
        api.deviceId = registered.value.id

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
}
