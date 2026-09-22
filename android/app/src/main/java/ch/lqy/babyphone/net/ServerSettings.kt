package ch.lqy.babyphone.net

import android.content.Context
import ch.lqy.babyphone.BuildConfig

/**
 * Where the backend lives. Kept editable on the login screen because the address differs per
 * install — a LAN address at home, a public hostname from outside — and there is no sensible
 * default to hard-code.
 */
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    /** Empty until somebody says where it is, which on a fresh install is nowhere. */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, normalise(value)).apply()

    private companion object {
        const val KEY_BASE_URL = "baseUrl"

        /**
         * A development build points at the development backend, because that is where it is
         * every time. A release build points nowhere: there is no address that is right for
         * somebody else's house, and guessing one would only fail later and less clearly.
         */
        val DEFAULT_BASE_URL = if (BuildConfig.DEBUG) "https://baby.kalinka-work.lqy.ch" else ""

        /** Accepts what people type: a bare hostname is meant as https, and a trailing / is noise. */
        fun normalise(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            return when {
                trimmed.isEmpty() -> ""
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> "https://$trimmed"
            }
        }
    }
}
