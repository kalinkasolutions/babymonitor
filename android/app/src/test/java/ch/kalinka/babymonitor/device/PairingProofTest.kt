package ch.kalinka.babymonitor.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingProofTest {
    @Test
    fun `a phone that saw the screen can prove it`() {
        val proof = PairingProof.sign(SECRET, DEVICE_ID, KEY)

        assertTrue(PairingProof.verify(SECRET, DEVICE_ID, KEY, proof))
    }

    @Test
    fun `a phone that never saw the screen cannot`() {
        val proof = PairingProof.sign("a different secret entirely", DEVICE_ID, KEY)

        assertFalse(PairingProof.verify(SECRET, DEVICE_ID, KEY, proof))
    }

    @Test
    fun `a proof does not carry over to a substituted key`() {
        val proof = PairingProof.sign(SECRET, DEVICE_ID, KEY)

        // What a backend swapping in its own key would have to get past.
        assertFalse(PairingProof.verify(SECRET, DEVICE_ID, OTHER_KEY, proof))
    }

    @Test
    fun `a proof does not carry over to another device`() {
        val proof = PairingProof.sign(SECRET, DEVICE_ID, KEY)

        assertFalse(PairingProof.verify(SECRET, OTHER_DEVICE_ID, KEY, proof))
    }

    @Test
    fun `nonsense in place of a proof is rejected rather than thrown over`() {
        assertFalse(PairingProof.verify(SECRET, DEVICE_ID, KEY, "not base64 at all!"))
        assertFalse(PairingProof.verify(SECRET, DEVICE_ID, KEY, ""))
    }

    @Test
    fun `each pairing gets its own secret`() {
        assertNotEquals(PairingProof.newSecret(), PairingProof.newSecret())
    }

    private companion object {
        const val SECRET = "Gk9mQm5wZXhhbXBsZXNlY3JldGZvcnRlc3Rpbmc9PQ=="
        const val DEVICE_ID = "6d8f5e02-7e21-4049-b34c-44fa85b696e8"
        const val OTHER_DEVICE_ID = "44659d0c-849a-40d3-b777-8b5f35ebdbf1"
        const val KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEexamplekeymaterialbase64=="
        const val OTHER_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsubstitutedbytheserver=="
    }
}
