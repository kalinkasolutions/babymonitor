package ch.kalinka.babymonitor.device

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.kalinka.babymonitor.media.CallCenter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A row in the device list. */
data class DeviceRow(val phone: Phone, val online: Boolean, val isThisPhone: Boolean)

class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val household = Household(application)
    private val calls = CallCenter.of(application)

    private val thisPhone = Phone(household.deviceId, household.name, DeviceIdentity.publicKey())

    /** This phone first, then the paired ones. */
    val devices: StateFlow<List<DeviceRow>> = combine(calls.others, calls.online) { others, online ->
        listOf(DeviceRow(thisPhone, online = true, isThisPhone = true)) +
            others.map { DeviceRow(it, online = it.id in online, isThisPhone = false) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() = calls.refresh()

    fun forget(deviceId: String) {
        Log.i(Tag, "Forgetting device $deviceId")
        household.forget(deviceId)
        calls.refresh()
    }

    private companion object {
        const val Tag = "DevicesViewModel"
    }
}
