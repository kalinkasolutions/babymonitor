package ch.lqy.babyphone.device

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.lqy.babyphone.net.ApiClient
import ch.lqy.babyphone.net.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What this phone knows about how it would reach the other one. */
class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    private val api = ApiClient(application)
    private val local = LocalSession(application)

    private val _ice = MutableStateFlow<IceConfig?>(null)
    val ice: StateFlow<IceConfig?> = _ice.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var serverUrl: String
        get() = api.baseUrl
        set(value) {
            api.baseUrl = value
        }

    init {
        refresh()
    }

    fun refresh() {
        // Nothing to fetch with no account: two phones on one WiFi reach each other on their own
        // addresses, which is the only path there is without a server to ask about the others.
        if (local.enabled) {
            return
        }

        viewModelScope.launch {
            when (val result = api.iceServers()) {
                is ApiResult.Ok -> _ice.value = IceConfig(result.value)
                is ApiResult.Failure -> _error.value = result.message
            }
        }
    }
}
