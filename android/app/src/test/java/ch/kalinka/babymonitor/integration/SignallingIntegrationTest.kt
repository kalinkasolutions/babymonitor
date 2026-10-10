package ch.kalinka.babymonitor.integration

import ch.kalinka.babymonitor.net.SignalKinds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The signalling path against a relay that is actually running. Nothing is mocked: the parts
 * that break are the ones between the two processes, and a stub would agree with the client.
 *
 * Skipped when there is no relay, so an ordinary `./gradlew test` stays green. To run it:
 *
 *     Server__Key=integration-test-key ASPNETCORE_URLS=http://127.0.0.1:5199 \
 *     dotnet run --project backend/Babymonitor.Api --no-launch-profile
 */
class SignallingIntegrationTest {
    private val nursery = TestPhone()
    private val bedside = TestPhone()
    private val hubs = mutableListOf<Hub>()

    @Before
    fun relayIsUp() {
        assumeTrue("No relay at ${Backend.Url}", Backend.isRunning())
    }

    @After
    fun hangUp() {
        hubs.forEach { it.close() }
    }

    @Test
    fun `an offer reaches the other phone with the sender stamped on it`() {
        val from = hub(nursery)
        val to = hub(bedside)

        assertTrue("the bedside phone was connected", from.signal(bedside.deviceId, SignalKinds.Offer, OFFER))

        val arrived = to.nextSignal()
        assertEquals(SignalKinds.Offer, arrived?.kind)
        assertEquals(OFFER, arrived?.body)
        assertEquals(nursery.deviceId, arrived?.fromDeviceId)
    }

    @Test
    fun `a full offer answer candidate exchange goes both ways`() {
        val room = hub(nursery)
        val bed = hub(bedside)

        bed.signal(nursery.deviceId, SignalKinds.Start, "")
        assertEquals(SignalKinds.Start, room.nextSignal()?.kind)

        room.signal(bedside.deviceId, SignalKinds.Offer, OFFER)
        assertEquals(SignalKinds.Offer, bed.nextSignal()?.kind)

        bed.signal(nursery.deviceId, SignalKinds.Answer, ANSWER)
        assertEquals(ANSWER, room.nextSignal()?.body)

        bed.signal(nursery.deviceId, SignalKinds.Candidate, CANDIDATE)
        assertEquals(CANDIDATE, room.nextSignal()?.body)
    }

    @Test
    fun `a pairing message is carried before either phone trusts the other`() {
        val room = hub(nursery)
        hub(bedside)

        assertTrue(room.signal(bedside.deviceId, SignalKinds.Pair, "{}"))
    }

    @Test
    fun `a phone with no connection is reported rather than silently missed`() {
        val room = hub(nursery)

        assertFalse(room.signal(TestPhone().deviceId, SignalKinds.Start, ""))
    }

    @Test
    fun `the signal goes to the one phone it is addressed to`() {
        val room = hub(nursery)
        val bed = hub(bedside)
        val stranger = hub(TestPhone())

        room.signal(bedside.deviceId, SignalKinds.Start, "")

        assertEquals(SignalKinds.Start, bed.nextSignal()?.kind)
        assertNull(stranger.nextSignal(seconds = 2))
    }

    @Test
    fun `a watched phone is reported arriving`() {
        val room = hub(nursery)
        assertEquals(emptyList<String>(), room.watch(bedside.deviceId))

        hub(bedside)

        val arrived = room.nextPresence()
        assertEquals(bedside.deviceId, arrived?.deviceId)
        assertEquals(true, arrived?.isOnline)
    }

    @Test
    fun `a kind this server does not carry is refused`() {
        val room = hub(nursery)

        assertTrue(runCatching { room.signal(bedside.deviceId, "eavesdrop", "") }.isFailure)
    }

    @Test
    fun `relay credentials need the right server key`() {
        assertEquals(200, nursery.iceServersStatus())
        assertEquals(401, nursery.iceServersStatus(nursery.authHeaders() + ("X-Server-Key" to "wrong")))
    }

    private fun hub(phone: TestPhone): Hub = phone.connectHub().also { hubs.add(it) }

    private companion object {
        const val OFFER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        const val ANSWER = "v=0\r\no=- 3 4 IN IP4 127.0.0.1\r\ns=-\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        const val CANDIDATE =
            """{"sdpMid":"0","sdpMLineIndex":0,"candidate":"candidate:1 1 udp 2130706431 10.0.0.5 50000 typ host"}"""
    }
}
