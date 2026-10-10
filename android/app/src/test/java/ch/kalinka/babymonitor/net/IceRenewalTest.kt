package ch.kalinka.babymonitor.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IceRenewalTest {
    private val now = Instant.parse("2026-10-10T20:00:00Z").toEpochMilli()

    private fun relay(expiresAt: String) =
        IceServersDto(urls = listOf("turn:relay"), username = "1:abc", credential = "x", expiresAt = expiresAt)

    @Test
    fun `nothing fetched yet needs fetching`() {
        assertTrue(needsIceRenewal(null, now))
    }

    @Test
    fun `credentials good for hours are kept`() {
        assertFalse(needsIceRenewal(relay("2026-10-11T08:00:00.1234567Z"), now))
    }

    @Test
    fun `credentials within the last hour, or past it, are renewed`() {
        assertTrue(needsIceRenewal(relay("2026-10-10T20:30:00Z"), now))
        assertTrue(needsIceRenewal(relay("2026-10-10T08:00:00Z"), now))
    }

    @Test
    fun `an unreadable expiry is renewed rather than trusted`() {
        assertTrue(needsIceRenewal(relay(""), now))
    }

    @Test
    fun `stun alone has no credential to expire`() {
        assertFalse(needsIceRenewal(IceServersDto(urls = listOf("stun:relay"), expiresAt = "2026-10-10T08:00:00Z"), now))
    }
}
