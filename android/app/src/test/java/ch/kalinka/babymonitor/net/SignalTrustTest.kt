package ch.kalinka.babymonitor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * What decides whether a signal is acted on.
 *
 * This is the whole of the app's defence against a backend that is not merely curious but
 * actively lying. An offer carries the DTLS fingerprint the media will be encrypted to, and
 * anything able to rewrite that in flight is a man in the middle — so the fingerprint is inside
 * what the sending phone signs, and the key it is checked against is the one somebody read off
 * that phone's screen. The backend can drop these or delay them; it cannot forge one.
 */
class SignalTrustTest {
    @Test
    fun `a signal signed by the confirmed key is acted on`() {
        val signal = sign(offer(), nursery)

        assertEquals(SignalTrust.Verified, signal.trust(publicKey(nursery), now = NOW))
    }

    @Test
    fun `a signal from a phone nobody confirmed is not`() {
        val signal = sign(offer(), nursery)

        assertEquals(SignalTrust.Unconfirmed, signal.trust(null, now = NOW))
        assertEquals(SignalTrust.Unconfirmed, signal.trust("", now = NOW))
    }

    @Test
    fun `a signal signed by some other key is not`() {
        val signal = sign(offer(), nursery)

        assertEquals(SignalTrust.WrongKey, signal.trust(publicKey(impostor), now = NOW))
    }

    @Test
    fun `an unsigned signal is not`() {
        assertEquals(SignalTrust.WrongKey, offer().trust(publicKey(nursery), now = NOW))
    }

    /**
     * The reason this exists at all. A backend relaying the real offer cannot read the media; one
     * that swaps the fingerprint for its own can, and this is what catches it.
     */
    @Test
    fun `an offer whose fingerprint was swapped in flight is refused`() {
        val genuine = sign(offer(fingerprint = "AA:BB:CC"), nursery)

        val tampered = genuine.copy(body = genuine.body.replace("AA:BB:CC", "DE:AD:BE"))

        assertEquals(SignalTrust.WrongKey, tampered.trust(publicKey(nursery), now = NOW))
    }

    @Test
    fun `a signal readdressed to another phone is refused`() {
        val genuine = sign(offer(), nursery)

        val readdressed = genuine.copy(toDeviceId = "somebody-else")

        assertEquals(SignalTrust.WrongKey, readdressed.trust(publicKey(nursery), now = NOW))
    }

    @Test
    fun `a signal whose kind was changed is refused`() {
        val genuine = sign(offer(), nursery)

        val rekinded = genuine.copy(kind = SignalKinds.Stop)

        assertEquals(SignalTrust.WrongKey, rekinded.trust(publicKey(nursery), now = NOW))
    }

    /**
     * Claiming to be somebody else picks a different key to check against, so it can only ever
     * cost a sender its own message. This is what stands in for the hub on the LAN, where nothing
     * authenticates the connection.
     */
    @Test
    fun `renaming the sender cannot impersonate anybody`() {
        val signal = sign(offer(), impostor).copy(fromDeviceId = "the-nursery-phone")

        assertEquals(SignalTrust.WrongKey, signal.trust(publicKey(nursery), now = NOW))
    }

    @Test
    fun `a captured signal played back later is refused`() {
        val signal = sign(offer(), nursery)

        assertEquals(
            SignalTrust.Stale,
            signal.trust(publicKey(nursery), now = NOW + SignalFreshnessWindowMillis + 1)
        )
    }

    @Test
    fun `a signal from just inside the window is still acted on`() {
        val signal = sign(offer(), nursery)

        assertEquals(
            SignalTrust.Verified,
            signal.trust(publicKey(nursery), now = NOW + SignalFreshnessWindowMillis - 1)
        )
    }

    @Test
    fun `a signal stamped in the future is refused`() {
        val signal = sign(offer(), nursery, sentAt = NOW + SignalFreshnessWindowMillis + 1)

        assertEquals(SignalTrust.Stale, signal.trust(publicKey(nursery), now = NOW))
    }

    /** The time is inside the signature, so moving it forward invalidates what was signed. */
    @Test
    fun `restamping a captured signal does not revive it`() {
        val captured = sign(offer(), nursery)

        val restamped = captured.copy(sentAt = NOW + SignalFreshnessWindowMillis * 2)

        assertEquals(SignalTrust.WrongKey, restamped.trust(publicKey(nursery), now = restamped.sentAt))
    }

    /** Two signals of the same thing differ, because the time they were signed is in them. */
    @Test
    fun `signing the same message twice does not produce the same bytes`() {
        assertNotEquals(
            sign(offer(), nursery, sentAt = NOW).signedBytes().toList(),
            sign(offer(), nursery, sentAt = NOW + 1).signedBytes().toList()
        )
    }

    private fun offer(fingerprint: String = "AA:BB:CC") = SignalMessage(
        toDeviceId = "the-listening-phone",
        fromDeviceId = "the-nursery-phone",
        kind = SignalKinds.Offer,
        body = "v=0\r\na=fingerprint:sha-256 $fingerprint\r\n"
    )

    private fun sign(message: SignalMessage, pair: KeyPair, sentAt: Long = NOW): SignalMessage {
        val stamped = message.copy(sentAt = sentAt)
        return stamped.copy(
            signature = Base64.getEncoder().encodeToString(
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(pair.private)
                    update(stamped.signedBytes())
                    sign()
                }
            )
        )
    }

    private fun publicKey(pair: KeyPair) = Base64.getEncoder().encodeToString(pair.public.encoded)

    private companion object {
        /** A fixed point, so the window is exercised rather than the wall clock. */
        const val NOW = 1_700_000_000_000L

        val nursery: KeyPair = generate()
        val impostor: KeyPair = generate()

        fun generate() = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }
}
