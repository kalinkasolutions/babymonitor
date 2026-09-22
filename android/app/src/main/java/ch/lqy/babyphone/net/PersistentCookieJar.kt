package ch.lqy.babyphone.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Stores the auth cookie across process death and reinstalls, so a phone that has been sitting in
 * a nursery for a week is still signed in. OkHttp's default jar is in-memory only.
 *
 * Entries are keyed by host, so pointing the app at a different server does not resurrect the
 * cookie from the old one.
 */
class PersistentCookieJar(
    private val store: CookieStore,
    private val now: () -> Long = System::currentTimeMillis
) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val keep = mutableMapOf<String, String>()
        val drop = mutableListOf<String>()

        for (cookie in cookies) {
            val key = keyOf(url.host, cookie.name)
            // A logout arrives as the same cookie with an expiry in the past; remove it rather than
            // storing a tombstone that every later read would have to filter out.
            if (cookie.expiresAt <= now()) {
                drop += key
            } else {
                keep[key] = cookie.toString()
            }
        }

        store.write(keep, drop)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val prefix = "${url.host}|"
        val expired = mutableListOf<String>()

        val cookies = store.all()
            .filterKeys { it.startsWith(prefix) }
            .mapNotNull { (key, value) ->
                val cookie = Cookie.parse(url, value)
                if (cookie == null || cookie.expiresAt <= now()) {
                    expired += key
                    null
                } else {
                    cookie
                }
            }

        if (expired.isNotEmpty()) {
            store.write(emptyMap(), expired)
        }
        return cookies
    }

    fun clear() = store.clear()

    /**
     * The cookies for [url] as a single header value. The SignalR client builds its own HTTP
     * stack and cannot be given this jar, so the header has to be handed over explicitly.
     */
    fun headerFor(url: HttpUrl): String =
        loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }

    private fun keyOf(host: String, name: String) = "$host|$name"
}
