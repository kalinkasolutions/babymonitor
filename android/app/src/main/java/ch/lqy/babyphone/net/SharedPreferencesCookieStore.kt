package ch.lqy.babyphone.net

import android.content.Context
import androidx.core.content.edit

class SharedPreferencesCookieStore(context: Context) : CookieStore {
    private val prefs = context.getSharedPreferences("cookies", Context.MODE_PRIVATE)

    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    override fun write(entries: Map<String, String>, remove: Collection<String>) {
        prefs.edit {
            entries.forEach { (key, value) -> putString(key, value) }
            remove.forEach(::remove)
        }
    }

    override fun clear() = prefs.edit { clear() }
}
