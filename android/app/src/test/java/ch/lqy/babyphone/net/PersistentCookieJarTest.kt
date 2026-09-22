package ch.lqy.babyphone.net

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentCookieJarTest {
    private val url = "https://baby.kalinka-work.lqy.ch/api/auth/status".toHttpUrl()

    /** The real Set-Cookie the backend issues: persistent, secure, httponly, host-only, path=/. */
    private fun authCookie(expiresAt: Long) = Cookie.Builder()
        .name(".AspNetCore.Identity.Application")
        .value("CfDJ8ExampleProtectedPayload")
        .hostOnlyDomain("baby.kalinka-work.lqy.ch")
        .path("/")
        .expiresAt(expiresAt)
        .secure()
        .httpOnly()
        .build()

    @Test
    fun `survives a restart, because the store is all that carries state`() {
        val store = InMemoryCookieStore()
        val year = NOW + 365L * 24 * 60 * 60 * 1000

        PersistentCookieJar(store, now = { NOW })
            .saveFromResponse(url, listOf(authCookie(year)))

        // A brand new jar over the same store is exactly what a relaunch or a reinstall looks like.
        val loaded = PersistentCookieJar(store, now = { NOW }).loadForRequest(url)

        assertEquals(1, loaded.size)
        assertEquals(".AspNetCore.Identity.Application", loaded[0].name)
        assertEquals("CfDJ8ExampleProtectedPayload", loaded[0].value)
        assertTrue(loaded[0].secure)
        assertTrue(loaded[0].httpOnly)
    }

    @Test
    fun `a logout cookie removes the stored one`() {
        val store = InMemoryCookieStore()
        val jar = PersistentCookieJar(store, now = { NOW })
        jar.saveFromResponse(url, listOf(authCookie(NOW + 100_000)))

        jar.saveFromResponse(url, listOf(authCookie(NOW - 1)))

        assertTrue(jar.loadForRequest(url).isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `an expired cookie is dropped on read`() {
        val store = InMemoryCookieStore()
        PersistentCookieJar(store, now = { NOW }).saveFromResponse(url, listOf(authCookie(NOW + 1_000)))

        val later = PersistentCookieJar(store, now = { NOW + 2_000 })

        assertTrue(later.loadForRequest(url).isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `cookies do not leak to a different server`() {
        val store = InMemoryCookieStore()
        val jar = PersistentCookieJar(store, now = { NOW })
        jar.saveFromResponse(url, listOf(authCookie(NOW + 100_000)))

        val other = jar.loadForRequest("https://elsewhere.example.com/api/auth/status".toHttpUrl())

        assertTrue(other.isEmpty())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

private class InMemoryCookieStore : CookieStore {
    private val entries = mutableMapOf<String, String>()

    override fun all(): Map<String, String> = entries.toMap()

    override fun write(entries: Map<String, String>, remove: Collection<String>) {
        this.entries.putAll(entries)
        remove.forEach(this.entries::remove)
    }

    override fun clear() = entries.clear()
}
