package ch.lqy.babyphone.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {
    @Test
    fun `round trips through the QR text`() {
        val payload = PairingPayload(deviceId = DEVICE_ID, publicKey = KEY, code = CODE, secret = SECRET)

        val decoded = PairingPayload.decode(payload.encode())

        assertEquals(payload, decoded)
    }

    @Test
    fun `rejects text that is not a pairing code`() {
        assertNull(PairingPayload.decode("https://example.com"))
        assertNull(PairingPayload.decode(""))
        assertNull(PairingPayload.decode("{\"nope\":1}"))
    }

    @Test
    fun `a payload with no code is a pairing with no accounts behind it`() {
        val decoded = PairingPayload.decode("""{"v":1,"d":"$DEVICE_ID","k":"$KEY","n":"$SECRET"}""")

        assertEquals("", decoded?.code)
        assertEquals(KEY, decoded?.publicKey)
    }

    @Test
    fun `rejects a payload with no secret, which could not confirm anything`() {
        assertNull(PairingPayload.decode("""{"v":1,"d":"$DEVICE_ID","k":"$KEY","c":"$CODE"}"""))
    }

    @Test
    fun `rejects a payload from a different version`() {
        val fromTheFuture = """{"v":2,"d":"$DEVICE_ID","k":"$KEY","c":"$CODE","n":"$SECRET"}"""

        assertNull(PairingPayload.decode(fromTheFuture))
    }

    @Test
    fun `rejects a payload with nothing in it`() {
        assertNull(PairingPayload.decode("""{"v":1,"d":"","k":"","c":"","n":""}"""))
    }

    private companion object {
        const val DEVICE_ID = "6d8f5e02-7e21-4049-b34c-44fa85b696e8"
        const val KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEexamplekeymaterialbase64=="
        const val CODE = "J7K8-09CK"
        const val SECRET = "Gk9mQm5wZXhhbXBsZXNlY3JldA=="
    }
}
