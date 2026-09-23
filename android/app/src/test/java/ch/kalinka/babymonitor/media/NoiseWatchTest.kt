package ch.kalinka.babymonitor.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The thing standing between a parent and being woken for nothing. Every case here is a sound a
 * real room makes.
 */
class NoiseWatchTest {
    @Test
    fun `the louder it is, the less of it is needed`() {
        val settings = NoiseAlarmSettings(threshold = 0.4f, seconds = 25)

        val atThreshold = settings.secondsFor(0.4f)
        val crying = settings.secondsFor(0.7f)
        val shriek = settings.secondsFor(1f)

        assertEquals(25f, atThreshold, 0.1f)
        assertEquals(NoiseAlarmSettings.Floor, shriek, 0.1f)
        assertTrue("crying should sit between the two, not at either end", crying in 4f..12f)
    }

    @Test
    fun `below the threshold nothing ever counts`() {
        assertEquals(Float.MAX_VALUE, NoiseAlarmSettings().secondsFor(0.2f), 0f)
    }

    @Test
    fun `a door closing is not an alarm`() {
        val (_, fired) = feed(NoiseWatch(), loud = 1.0f, forMillis = 600)

        assertFalse(fired)
    }

    @Test
    fun `a shriek is an alarm within seconds`() {
        val (_, fired) = feed(NoiseWatch(), loud = 1.0f, forMillis = 2_400)

        assertTrue(fired)
    }

    @Test
    fun `grizzling at the threshold has to keep going`() {
        val (_, early) = feed(NoiseWatch(), loud = 0.42f, forMillis = 10_000)
        val (_, eventually) = feed(NoiseWatch(), loud = 0.42f, forMillis = 30_000)

        assertFalse("ten seconds of almost nothing is not an alarm", early)
        assertTrue("half a minute of it is", eventually)
    }

    @Test
    fun `crying with breaths in it still trips it`() {
        var watch = NoiseWatch()
        var fired = false
        var now = 0L

        // A second of crying, then half a second of breath, over and over.
        repeat(20) {
            repeat(5) {
                now += 200
                watch.on(0.8f, now).let { (next, alarm) -> watch = next; fired = fired || alarm }
            }
            repeat(2) {
                now += 200
                watch.on(0.1f, now).let { (next, alarm) -> watch = next; fired = fired || alarm }
            }
        }

        assertTrue(fired)
    }

    @Test
    fun `a quiet room never trips it, however long it goes on`() {
        val (_, fired) = feed(NoiseWatch(), loud = 0.2f, forMillis = 300_000)

        assertFalse(fired)
    }

    @Test
    fun `one crying fit is one alarm, not twenty`() {
        val (after, first) = feed(NoiseWatch(), loud = 0.9f, forMillis = 10_000)
        val (_, again) = feed(after, loud = 0.9f, forMillis = 10_000, from = 10_000)

        assertTrue(first)
        assertFalse("still inside the quiet minute after an alarm", again)
    }

    @Test
    fun `turned off, it never fires`() {
        val off = NoiseWatch(settings = NoiseAlarmSettings(enabled = false))

        val (_, fired) = feed(off, loud = 1.0f, forMillis = 60_000)

        assertFalse(fired)
    }

    @Test
    fun `a higher threshold ignores what a lower one would catch`() {
        val fussy = NoiseWatch(settings = NoiseAlarmSettings(threshold = 0.9f))

        val (_, fired) = feed(fussy, loud = 0.8f, forMillis = 60_000)

        assertFalse(fired)
    }

    private fun feed(
        start: NoiseWatch,
        loud: Float,
        forMillis: Long,
        from: Long = 0
    ): Pair<NoiseWatch, Boolean> {
        var watch = start
        var fired = false
        var now = from
        while (now < from + forMillis) {
            now += 200
            val (next, alarm) = watch.on(loud, now)
            watch = next
            fired = fired || alarm
        }

        return watch to fired
    }
}
