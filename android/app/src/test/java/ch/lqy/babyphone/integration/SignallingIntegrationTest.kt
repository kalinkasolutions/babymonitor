package ch.lqy.babyphone.integration

import ch.lqy.babyphone.net.SignalKinds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The signalling path against a backend that is actually running: two accounts, two phones, one
 * hub, and the messages a call is made of. Nothing here is mocked, which is the point — the parts
 * that break in this app are the ones between the two processes, and a stub of the hub would
 * agree with whatever the client believed.
 *
 * Skipped when there is no backend to talk to, so an ordinary `./gradlew test` stays green.
 * To run it:
 *
 *     ConnectionStrings__Babyphone="Data Source=/tmp/babyphone-test.db" \
 *     ASPNETCORE_ENVIRONMENT=Development ASPNETCORE_URLS=http://127.0.0.1:5199 \
 *     dotnet run --project backend/Babyphone.Api --no-launch-profile
 *
 * The two accounts are the ones the Development configuration seeds.
 */
class SignallingIntegrationTest {
    private lateinit var parent: Backend
    private lateinit var sitter: Backend

    private lateinit var parentPhone: String
    private lateinit var sitterPhone: String
    private lateinit var sitterSpare: String

    private val hubs = mutableListOf<Hub>()

    @Before
    fun twoPairedPhones() {
        assumeTrue("No backend at ${Backend.Url}", Backend.isRunning())

        parent = Backend(Backend.Url).apply { logIn("admin@local", "admin") }
        sitter = Backend(Backend.Url).apply { logIn("niggi@local", "niggi") }

        parentPhone = parent.registerDevice("Nursery phone", "TESTKEY-PARENT-1")
        sitterPhone = sitter.registerDevice("Bedside phone", "TESTKEY-SITTER-1")

        // A second phone for the sitter that never connects, so "nobody is listening" can be told
        // apart from "no such phone".
        sitterSpare = sitter.registerDevice("Spare phone", "TESTKEY-SITTER-2")

        link()
    }

    @After
    fun hangUp() {
        hubs.forEach { it.close() }
    }

    @Test
    fun `an offer reaches the other phone with the sender stamped on it`() {
        val nursery = hub(parent, parentPhone)
        val bedside = hub(sitter, sitterPhone)

        val delivered = nursery.signal(sitterPhone, SignalKinds.Offer, OFFER)

        assertTrue("the bedside phone was connected", delivered)
        val arrived = bedside.nextSignal()
        assertEquals(SignalKinds.Offer, arrived?.kind)
        assertEquals(OFFER, arrived?.body)

        // Stamped by the hub, not by the sender: the return address has to be one the other phone
        // can rely on, or an answer goes to whoever asked for it.
        assertEquals(parentPhone, arrived?.fromDeviceId)
    }

    @Test
    fun `a full offer answer candidate exchange goes both ways`() {
        val nursery = hub(parent, parentPhone)
        val bedside = hub(sitter, sitterPhone)

        bedside.signal(parentPhone, SignalKinds.Start, "")
        assertEquals(SignalKinds.Start, nursery.nextSignal()?.kind)

        nursery.signal(sitterPhone, SignalKinds.Offer, OFFER)
        assertEquals(SignalKinds.Offer, bedside.nextSignal()?.kind)

        bedside.signal(parentPhone, SignalKinds.Answer, ANSWER)
        assertEquals(ANSWER, nursery.nextSignal()?.body)

        bedside.signal(parentPhone, SignalKinds.Candidate, CANDIDATE)
        assertEquals(CANDIDATE, nursery.nextSignal()?.body)
    }

    @Test
    fun `a phone with no connection is reported rather than silently missed`() {
        val nursery = hub(parent, parentPhone)

        val delivered = nursery.signal(sitterSpare, SignalKinds.Start, "")

        assertFalse("the spare phone has never connected", delivered)
    }

    @Test
    fun `the signal goes to the one phone it is addressed to`() {
        val nursery = hub(parent, parentPhone)
        val bedside = hub(sitter, sitterPhone)
        val spare = hub(sitter, sitterSpare)

        nursery.signal(sitterPhone, SignalKinds.Start, "")

        assertEquals(SignalKinds.Start, bedside.nextSignal()?.kind)

        // Same account, same hub, not addressed: an offer is for one phone.
        assertNull(spare.nextSignal(seconds = 2))
    }

    @Test
    fun `a phone on an unlinked account cannot be signalled`() {
        val nursery = hub(parent, parentPhone)
        unlink()

        val refused = runCatching { nursery.signal(sitterPhone, SignalKinds.Start, "") }

        assertTrue("expected a refusal, got ${refused.getOrNull()}", refused.isFailure)
        assertTrue(
            "unexpected refusal: ${refused.exceptionOrNull()?.message}",
            refused.exceptionOrNull()?.message.orEmpty().contains("not one this account can reach")
        )
    }

    @Test
    fun `a device id nobody owns is refused the same way`() {
        val nursery = hub(parent, parentPhone)

        val refused = runCatching {
            nursery.signal("00000000-0000-4000-8000-000000000000", SignalKinds.Start, "")
        }

        assertTrue(refused.isFailure)
    }

    @Test
    fun `a kind this server does not carry is refused`() {
        val nursery = hub(parent, parentPhone)

        val refused = runCatching { nursery.signal(sitterPhone, "eavesdrop", "") }

        assertTrue(refused.isFailure)
    }

    private fun hub(backend: Backend, deviceId: String): Hub =
        backend.connectHub(deviceId).also { hubs.add(it) }

    /**
     * Pairs the two accounts, unless a previous test already did. Not unconditional: claiming is
     * rate limited on purpose, and a test suite that burns a real account's budget every run is
     * testing the limiter rather than the thing it came for.
     */
    private fun link() {
        if (sitter.canSee(parentPhone)) {
            return
        }

        val code = parent.pairingCode(parentPhone)
        sitter.claim(code, sitterPhone)
    }

    private fun unlink() {
        parent.unlinkFrom(parent.ownerOf(sitterPhone))
    }

    private companion object {
        const val OFFER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        const val ANSWER = "v=0\r\no=- 3 4 IN IP4 127.0.0.1\r\ns=-\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        const val CANDIDATE =
            """{"sdpMid":"0","sdpMLineIndex":0,"candidate":"candidate:1 1 udp 2130706431 10.0.0.5 50000 typ host"}"""
    }
}
