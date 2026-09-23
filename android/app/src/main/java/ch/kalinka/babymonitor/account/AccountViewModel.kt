package ch.kalinka.babymonitor.account

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.kalinka.babymonitor.net.AccountDto
import ch.kalinka.babymonitor.net.ApiClient
import ch.kalinka.babymonitor.net.ApiResult
import ch.kalinka.babymonitor.net.ChangeEmailRequest
import ch.kalinka.babymonitor.net.ChangePasswordRequest
import ch.kalinka.babymonitor.net.InviteRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val api = ApiClient(application)

    private val _account = MutableStateFlow<AccountDto?>(null)
    val account: StateFlow<AccountDto?> = _account.asStateFlow()

    private val _page = MutableStateFlow(AccountPage.Overview)
    val page: StateFlow<AccountPage> = _page.asStateFlow()

    private val _twoFactor = MutableStateFlow<TwoFactorState>(TwoFactorState.Idle)
    val twoFactor: StateFlow<TwoFactorState> = _twoFactor.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Raised when the session ends here — deleting the account, or signing out everywhere. */
    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        whileBusy {
            when (val result = api.account()) {
                is ApiResult.Ok -> _account.value = result.value
                is ApiResult.Failure -> _error.value = result.message
            }
        }
    }

    fun open(page: AccountPage) {
        _error.value = null
        _notice.value = null
        if (page == AccountPage.Overview) {
            _twoFactor.value = TwoFactorState.Idle
        }
        _page.value = page
    }

    fun updateUsername(username: String) {
        whileBusy {
            when (val result = api.updateUsername(username.trim())) {
                is ApiResult.Ok -> {
                    _account.value = result.value
                    done("Display name changed.")
                }

                is ApiResult.Failure -> _error.value = result.message
            }
        }
    }

    fun changePassword(current: String, new: String) {
        whileBusy {
            when (val result = api.changePassword(ChangePasswordRequest(current, new))) {
                is ApiResult.Ok -> done("Password changed.")
                is ApiResult.Failure -> _error.value = result.message
            }
        }
    }

    fun requestEmailChange(email: String, password: String) {
        whileBusy {
            when (val result = api.requestEmailChange(ChangeEmailRequest(email.trim(), password))) {
                // The address does not move until the link in that mail is followed, so the
                // account shown here is deliberately not refreshed.
                is ApiResult.Ok -> done("Check $email for a confirmation link.")
                is ApiResult.Failure -> _error.value = result.message
            }
        }
    }

    fun invite(email: String, username: String) {
        whileBusy {
            when (val result = api.invite(InviteRequest(email.trim(), username.trim()))) {
                is ApiResult.Ok -> done("Invitation sent to $email.")
                is ApiResult.Failure -> _error.value = result.message
            }
        }
    }

    fun startTwoFactorSetup() {
        whileBusy {
            when (val result = api.twoFactorSetup()) {
                is ApiResult.Ok -> _twoFactor.value = TwoFactorState.AwaitingCode(result.value)
                is ApiResult.Failure -> _error.value = result.message
            }
        }
    }

    fun enableTwoFactor(code: String) {
        whileBusy {
            when (val result = api.enableTwoFactor(code.trim())) {
                is ApiResult.Ok -> {
                    _twoFactor.value = TwoFactorState.ShowingRecoveryCodes(result.value.codes)
                    reload()
                }

                is ApiResult.Failure -> _error.value = result.message
            }
        }
    }

    fun disableTwoFactor(password: String) {
        whileBusy {
            when (val result = api.disableTwoFactor(password)) {
                is ApiResult.Ok -> {
                    _twoFactor.value = TwoFactorState.Idle
                    reload()
                    _notice.value = "Two-factor authentication is off."
                }

                is ApiResult.Failure -> _error.value = result.message
            }
        }
    }

    fun regenerateRecoveryCodes(password: String) {
        whileBusy {
            when (val result = api.regenerateRecoveryCodes(password)) {
                is ApiResult.Ok -> {
                    _twoFactor.value = TwoFactorState.ShowingRecoveryCodes(result.value.codes)
                    reload()
                }

                is ApiResult.Failure -> _error.value = result.message
            }
        }
    }

    fun dismissRecoveryCodes() {
        _twoFactor.value = TwoFactorState.Idle
        _page.value = AccountPage.Overview
    }

    fun signOutEverywhere() {
        whileBusy {
            when (val result = api.signOutEverywhere()) {
                // Deliberately includes this phone, so the session here is over too.
                is ApiResult.Ok -> {
                    Log.i(Tag, "Signed out everywhere; every phone on this account is now signed out")
                    api.clearSession()
                    _signedOut.value = true
                }

                is ApiResult.Failure -> {
                    Log.w(Tag, "Could not sign out everywhere: ${result.message}")
                    _error.value = result.message
                }
            }
        }
    }

    fun deleteAccount(password: String) {
        whileBusy {
            when (val result = api.deleteAccount(password)) {
                is ApiResult.Ok -> {
                    Log.i(Tag, "Account deleted")
                    api.clearSession()
                    _signedOut.value = true
                }

                is ApiResult.Failure -> {
                    Log.w(Tag, "Could not delete the account: ${result.message}")
                    _error.value = result.message
                }
            }
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private suspend fun reload() {
        (api.account() as? ApiResult.Ok)?.let { _account.value = it.value }
    }

    /** Back to the overview with a confirmation message. */
    private fun done(message: String) {
        _notice.value = message
        _page.value = AccountPage.Overview
    }

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
        const val Tag = "AccountViewModel"
    }
}
