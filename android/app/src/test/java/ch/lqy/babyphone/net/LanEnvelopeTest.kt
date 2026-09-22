package ch.lqy.babyphone.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * What stops a phone on the same WiFi from starting somebody else's camera. The hub has a cookie
 * for this; a local socket has only the signature.
 */
class LanEnvelopeTest {
    @Test
    fun `a message signed by the confirmed key is accepted`() {
        val envelope = sign(SIGNAL, keys)

        assertTrue(envelope.isSignedBy(publicKey(keys)))
    }

    @Test
    fun `a message from another phone's key is not`() {
        val envelope = sign(SIGNAL, keys)

        assertFalse(envelope.isSignedBy(publicKey(impostor)))
    }

    @Test
    fun `a message altered after signing is not`() {
        val envelope = sign(SIGNAL, keys).copy(signal = SIGNAL.replace("start", "offer"))

        assertFalse(envelope.isSignedBy(publicKey(keys)))
    }

    @Test
    fun `nothing at all is not proof of anything`() {
        assertFalse(LanEnvelope(from = "a", signal = SIGNAL).isSignedBy(publicKey(keys)))
        assertFalse(sign(SIGNAL, keys).isSignedBy(null))
        assertFalse(sign(SIGNAL, keys).isSignedBy(""))
        assertFalse(sign(SIGNAL, keys).isSignedBy("not a key"))
    }

    private fun sign(payload: String, pair: java.security.KeyPair) = LanEnvelope(
        from = "3f1d9b6a-0000-4000-8000-000000000001",
        signal = payload,
        signature = Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(payload.toByteArray())
                sign()
            }
        )
    )

    private fun publicKey(pair: java.security.KeyPair) =
        Base64.getEncoder().encodeToString(pair.public.encoded)

    private companion object {
        const val SIGNAL = """{"toDeviceId":"b","kind":"start","body":""}"""

        val keys: java.security.KeyPair = generate()
        val impostor: java.security.KeyPair = generate()

        fun generate() = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }
}
