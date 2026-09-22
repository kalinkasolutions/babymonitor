package ch.lqy.babyphone.net

/**
 * Where the cookie jar keeps its entries. Split out so the jar's logic is plain Kotlin and can be
 * tested on the JVM; [SharedPreferencesCookieStore] is the only implementation the app uses.
 */
interface CookieStore {
    fun all(): Map<String, String>
    fun write(entries: Map<String, String>, remove: Collection<String>)
    fun clear()
}
