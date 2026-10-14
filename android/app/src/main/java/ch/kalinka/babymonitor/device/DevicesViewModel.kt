package ch.kalinka.babymonitor.device

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.kalinka.babymonitor.log.AppLog
import ch.kalinka.babymonitor.media.CallCenter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * A row in the device list. [status] is what the row says under the name: for this phone whether
 * it is the monitor, for the others whether they can be watched.
 */
data class DeviceRow(val phone: Phone, val status: String, val isThisPhone: Boolean)

class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val household = Household(application)
    private val calls = CallCenter.of(application)

    private val thisPhone = Phone(household.deviceId, household.name, DeviceIdentity.publicKey())

    /** This phone first, then the paired ones. */
    val devices: StateFlow<List<DeviceRow>> =
        combine(calls.others, calls.online, calls.availability, calls.armed) { others, online, availability, armed ->
            val thisRow = DeviceRow(
                thisPhone,
                status = if (armed) "Set up as the monitor" else "Not set up as a monitor",
                isThisPhone = true
            )
            listOf(thisRow) + others.map {
                DeviceRow(it, Reachability.of(it.id in online, availability[it.id]).line(it.name), isThisPhone = false)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() = calls.refresh()

    fun forget(deviceId: String) {
        AppLog.i(Tag, "Forgetting device $deviceId")
        household.forget(deviceId)
        calls.refresh()
    }

    private companion object {
        const val Tag = "DevicesViewModel"
    }
}
