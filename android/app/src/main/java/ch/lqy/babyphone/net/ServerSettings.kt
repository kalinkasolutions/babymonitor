package ch.lqy.babyphone.net

import android.content.Context

/**
 * Where the backend lives. Kept editable on the login screen because the address differs per
 * install — a LAN address at home, a public hostname from outside — and there is no sensible
 * default to hard-code.
 */
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, normalise(value)).apply()

    private companion object {
        const val KEY_BASE_URL = "baseUrl"

        // The dev backend behind nginx. Its certificate is from a private CA, so the phone has to
        // have that CA installed as a user certificate — the network security config trusts user
        // anchors for exactly this. Editable on the login screen for anything else.
        const val DEFAULT_BASE_URL = "https://baby.kalinka-work.lqy.ch"

        fun normalise(value: String) = value.trim().trimEnd('/')
    }
}
