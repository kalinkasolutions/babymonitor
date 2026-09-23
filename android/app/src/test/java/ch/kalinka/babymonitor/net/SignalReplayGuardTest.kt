package ch.kalinka.babymonitor.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalReplayGuardTest {
    @Test
    fun `a signal is acted on once`() {
        val guard = SignalReplayGuard()
        val stop = signal(sentAt = Now)

        assertTrue(guard.accept(stop, Now))
        assertFalse("the same signed bytes came back", guard.accept(stop, Now + 1_000))
    }

    @Test
    fun `two different signals both go through`() {
        val guard = SignalReplayGuard()

        assertTrue(guard.accept(signal(sentAt = Now, signature = "one"), Now))
        assertTrue(guard.accept(signal(sentAt = Now, signature = "two"), Now))
    }

    @Test
    fun `the same signature from two phones is two signals`() {
        val guard = SignalReplayGuard()

        assertTrue(guard.accept(signal(sentAt = Now, from = "one"), Now))
        assertTrue(guard.accept(signal(sentAt = Now, from = "two"), Now))
    }

    @Test
    fun `what is remembered is forgotten once it could no longer be believed`() {
        val guard = SignalReplayGuard()
        val stop = signal(sentAt = Now)
        guard.accept(stop, Now)

        // Past the freshness window, so trust() rejects it on its age and there is nothing left
        // for this to remember about it.
        assertTrue(guard.accept(stop, Now + SignalFreshnessWindowMillis + 1))
    }

    @Test
    fun `a message stamped ahead is remembered for as long as it stays good`() {
        val guard = SignalReplayGuard()
        val ahead = signal(sentAt = Now + SignalFreshnessWindowMillis)
        guard.accept(ahead, Now)

        assertFalse(guard.accept(ahead, Now + SignalFreshnessWindowMillis))
    }

    private fun signal(
        sentAt: Long,
        from: String = "3f1d9b6a-0000-4000-8000-000000000001",
        signature: String = "c2lnbmF0dXJl"
    ) = SignalMessage(
        toDeviceId = "3f1d9b6a-0000-4000-8000-000000000002",
        fromDeviceId = from,
        kind = SignalKinds.Stop,
        sentAt = sentAt,
        signature = signature
    )

    private companion object {
        const val Now = 1_800_000_000_000L
    }
}
