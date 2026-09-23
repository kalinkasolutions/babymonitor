package ch.kalinka.babymonitor.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.serialization.Serializable

/**
 * What the phone in the room sends about the room and itself, on the data channel beside the
 * media. Either half may be absent: how loud it is arrives several times a second, and a battery
 * reading only when it changes, so sending both together would be mostly repetition.
 */
@Serializable
data class RoomReport(val status: PhoneStatus? = null, val noise: Float? = null)

/** How the phone in the room is doing, as it would show in its own status bar. */
@Serializable
data class PhoneStatus(
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val network: Network = Network.None,

    /** Nought to four, as bars. Null when the system will not say. */
    val bars: Int? = null
) {
    enum class Network { None, Wifi, Mobile }
}

/**
 * Watches this phone's own battery and network, and says so when they change.
 *
 * Android broadcasts a battery change on every percent and every plug, which is exactly the news
 * the other end wants and far more often than anybody was polling for it. Only changes are passed
 * on: a monitor running all night should not be chattering about a number that has not moved.
 */
class StatusMonitor(private val context: Context, private val onChange: (PhoneStatus) -> Unit) {
    private var status = PhoneStatus()
    private var running = false

    /** What this phone last worked out about itself, for anything that needs to say it again. */
    val current: PhoneStatus get() = status

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { update(status.copy(batteryPercent = it.percent(), charging = it.charging())) }
        }
    }

    private val connectivity = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            update(status.copy(network = capabilities.network(), bars = capabilities.bars()))
        }

        override fun onLost(network: Network) {
            update(status.copy(network = PhoneStatus.Network.None, bars = null))
        }
    }

    fun start() {
        if (running) {
            return
        }

        running = true

        // The battery broadcast is sticky, so registering hands back the current state at once
        // and there is no gap where the other phone shows nothing.
        val current = context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        current?.let { status = status.copy(batteryPercent = it.percent(), charging = it.charging()) }

        connectivityManager()?.let {
            it.registerDefaultNetworkCallback(connectivity)
            val capabilities = it.getNetworkCapabilities(it.activeNetwork)
            status = status.copy(
                network = capabilities?.network() ?: PhoneStatus.Network.None,
                bars = capabilities?.bars()
            )
        }

        onChange(status)
    }

    fun stop() {
        if (!running) {
            return
        }

        running = false
        runCatching { context.unregisterReceiver(battery) }
        runCatching { connectivityManager()?.unregisterNetworkCallback(connectivity) }
    }

    private fun update(next: PhoneStatus) {
        if (next == status) {
            return
        }

        status = next
        onChange(next)
    }

    private fun connectivityManager() = context.getSystemService(ConnectivityManager::class.java)

    private fun Intent.percent(): Int? {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) null else level * 100 / scale
    }

    private fun Intent.charging(): Boolean =
        getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }

    private fun NetworkCapabilities.network(): PhoneStatus.Network = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> PhoneStatus.Network.Wifi
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> PhoneStatus.Network.Mobile
        else -> PhoneStatus.Network.None
    }

    /** dBm to bars, the same rough steps every status bar uses. Unreported means no bars shown. */
    private fun NetworkCapabilities.bars(): Int? = when (val dbm = signalStrength) {
        NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED -> null
        else -> when {
            dbm >= -55 -> 4
            dbm >= -66 -> 3
            dbm >= -77 -> 2
            dbm >= -88 -> 1
            else -> 0
        }
    }
}
