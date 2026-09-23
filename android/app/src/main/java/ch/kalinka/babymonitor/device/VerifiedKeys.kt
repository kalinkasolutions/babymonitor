package ch.kalinka.babymonitor.device

import android.content.Context
import androidx.core.content.edit

/**
 * The keys this phone has confirmed by scanning them off another phone's screen.
 *
 * Kept locally and never sent anywhere: the whole point is to have a record the backend cannot
 * edit. If the server later reports a different key for a device that was pinned here, something
 * is wrong and the app says so rather than connecting anyway.
 */
class VerifiedKeys(context: Context) {
    private val prefs = context.getSharedPreferences("verified-keys", Context.MODE_PRIVATE)

    fun pinned(deviceId: String): String? = prefs.getString(deviceId, null)

    fun pin(deviceId: String, publicKey: String) = prefs.edit { putString(deviceId, publicKey) }

    fun forget(deviceId: String) = prefs.edit { remove(deviceId) }
}
