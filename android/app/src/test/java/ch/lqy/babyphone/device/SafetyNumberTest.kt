package ch.lqy.babyphone.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SafetyNumberTest {
    @Test
    fun `both phones compute the same number whichever way round they ask`() {
        assertEquals(SafetyNumber.of(KEY_A, KEY_B), SafetyNumber.of(KEY_B, KEY_A))
    }

    @Test
    fun `a substituted key changes it, which is the whole point`() {
        assertNotEquals(SafetyNumber.of(KEY_A, KEY_B), SafetyNumber.of(KEY_A, FORGED))
    }

    @Test
    fun `it is short enough to read out`() {
        val number = SafetyNumber.of(KEY_A, KEY_B)

        assertEquals(8, number.split(" ").size)
        assertEquals(39, number.length)
    }

    private companion object {
        const val KEY_A = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfirstphonekeymaterial=="
        const val KEY_B = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsecondphonekeymaterial=="
        const val FORGED = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsubstitutedbytheserver=="
    }
}
