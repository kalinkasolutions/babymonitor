package ch.kalinka.babymonitor.device

import android.content.Context
import androidx.core.content.edit

/**
 * The id the backend gave this phone. Remembered so every later request can say which device it
 * is; the identity key is what actually proves anything, this is only a label.
 */
class DeviceRegistration(context: Context) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_DEVICE_ID) else putString(KEY_DEVICE_ID, value) }

    private companion object {
        const val KEY_DEVICE_ID = "deviceId"
    }
}
