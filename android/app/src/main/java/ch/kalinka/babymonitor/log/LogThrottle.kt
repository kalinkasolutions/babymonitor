package ch.kalinka.babymonitor.log

/** Lets the first [limit] lines per key through in each [windowMillis], and holds back the rest. */
class LogThrottle(private val limit: Int, private val windowMillis: Long) {
    private class Window(val startedAt: Long) {
        var count = 0
    }

    private val windows = HashMap<String, Window>()

    @Synchronized
    fun allows(key: String, nowMillis: Long): Boolean {
        val window = windows[key]
            ?.takeIf { nowMillis - it.startedAt < windowMillis }
            ?: Window(nowMillis).also { windows[key] = it }
        window.count++
        return window.count <= limit
    }
}
