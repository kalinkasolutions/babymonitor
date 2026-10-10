package ch.kalinka.babymonitor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ServerAuthTest {
    @Test
    fun `the signature covers the time sent beside it, and verifies with the phone's key`() {
        val keys = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)

        val headers = serverAuthHeaders("secret", publicKey, now = 1_700_000_000_000) { bytes ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(bytes)
                sign()
            }
        }

        assertEquals("secret", headers["X-Server-Key"])
        assertEquals(publicKey, headers["X-Device-Key"])
        assertEquals("1700000000000", headers["X-Device-Time"])

        val valid = Signature.getInstance("SHA256withECDSA").run {
            initVerify(keys.public)
            update("babymonitor-hub\n1700000000000".toByteArray())
            verify(Base64.getDecoder().decode(headers["X-Device-Signature"]))
        }
        assertTrue(valid)
    }
}
