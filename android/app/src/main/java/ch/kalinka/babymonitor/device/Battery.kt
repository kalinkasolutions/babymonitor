package ch.kalinka.babymonitor.device

import android.content.Context
import android.os.BatteryManager

/** What this phone would report in a heartbeat. */
data class BatteryState(val percent: Int?, val isCharging: Boolean)

fun readBattery(context: Context): BatteryState {
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        ?: return BatteryState(null, false)

    val percent = manager
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        .takeIf { it in 0..100 }

    return BatteryState(percent, manager.isCharging)
}
