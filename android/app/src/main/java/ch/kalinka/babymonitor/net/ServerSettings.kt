package ch.kalinka.babymonitor.net

import android.content.Context
import androidx.core.content.edit
import ch.kalinka.babymonitor.BuildConfig

/**
 * The optional relay server: its address, and the key that also unlocks its home relay. Without a
 * server the phones still work, but only on the same WiFi; without the key everything but the home
 * relay works.
 */
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(BaseUrl, DefaultBaseUrl) ?: DefaultBaseUrl
        set(value) = prefs.edit { putString(BaseUrl, normaliseUrl(value)) }

    var key: String
        get() = prefs.getString(Key, "").orEmpty()
        set(value) = prefs.edit { putString(Key, value.trim()) }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank()

    private companion object {
        const val BaseUrl = "baseUrl"
        const val Key = "key"

        // A release build points nowhere: no address is right for somebody else's house.
        val DefaultBaseUrl = if (BuildConfig.DEBUG) "https://baby.kalinka-work.lqy.ch" else ""
    }
}

/** Accepts what people type: a bare hostname is meant as https, and a trailing / is noise. */
fun normaliseUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    return when {
        trimmed.isEmpty() -> ""
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        else -> "https://$trimmed"
    }
}
