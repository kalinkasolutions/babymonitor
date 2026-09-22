package ch.lqy.babyphone.device

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyTrustTest {
    @Test
    fun `a key never confirmed is only vouched for by the server`() {
        assertEquals(KeyTrust.ServerVouched, keyTrustOf(pinnedKey = null, reportedKey = KEY))
    }

    @Test
    fun `a confirmed key the server still agrees with stays confirmed`() {
        assertEquals(KeyTrust.Confirmed, keyTrustOf(pinnedKey = KEY, reportedKey = KEY))
    }

    @Test
    fun `a server reporting something other than what was confirmed is flagged`() {
        assertEquals(KeyTrust.Changed, keyTrustOf(pinnedKey = KEY, reportedKey = OTHER_KEY))
    }

    private companion object {
        const val KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEexamplekeymaterialbase64=="
        const val OTHER_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsubstitutedbytheserver=="
    }
}
