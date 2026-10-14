package ch.kalinka.babymonitor.net

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.kalinka.babymonitor.media.CallCenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What became of the last check of the server settings. */
sealed interface ServerCheck {
    data object NotChecked : ServerCheck
    data object Checking : ServerCheck
    data object Works : ServerCheck
    data class Fails(val reason: String) : ServerCheck
}

class ServerViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = ServerSettings(application)
    private val api = ApiClient(application)
    private val calls = CallCenter.of(application)

    val url: String get() = settings.baseUrl

    private val _check = MutableStateFlow<ServerCheck>(ServerCheck.NotChecked)
    val check: StateFlow<ServerCheck> = _check.asStateFlow()

    /** Saves and then checks, so a typo shows up straight away rather than at night. */
    fun save(url: String) {
        settings.baseUrl = url
        calls.reconnect()

        if (!settings.isConfigured) {
            _check.value = ServerCheck.NotChecked
            return
        }

        _check.value = ServerCheck.Checking
        viewModelScope.launch {
            _check.value = when (val result = api.check()) {
                is ApiResult.Ok -> ServerCheck.Works
                is ApiResult.Failure -> ServerCheck.Fails(result.message)
            }
        }
    }
}
