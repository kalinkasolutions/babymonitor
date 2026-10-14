package ch.kalinka.babymonitor.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogThrottleTest {
    @Test
    fun `lets the first lines through and holds back the rest`() {
        val throttle = LogThrottle(limit = 2, windowMillis = 1_000)

        val allowed = (1..5).map { throttle.allows("connection.cc", nowMillis = 0) }

        assertEquals(listOf(true, true, false, false, false), allowed)
    }

    @Test
    fun `each key has its own allowance`() {
        val throttle = LogThrottle(limit = 1, windowMillis = 1_000)
        throttle.allows("connection.cc", nowMillis = 0)

        assertTrue(throttle.allows("turn_port.cc", nowMillis = 0))
    }

    @Test
    fun `a new window starts the count again`() {
        val throttle = LogThrottle(limit = 1, windowMillis = 1_000)
        throttle.allows("connection.cc", nowMillis = 0)

        assertTrue(throttle.allows("connection.cc", nowMillis = 1_000))
    }
}
