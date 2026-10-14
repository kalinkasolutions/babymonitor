package ch.kalinka.babymonitor.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {
    @Test
    fun `round trips through the QR text`() {
        val payload = PairingPayload(
            deviceId = DEVICE_ID,
            publicKey = KEY,
            name = "Pixel",
            secret = SECRET,
            server = "https://baby.example.com"
        )

        assertEquals(payload, PairingPayload.decode(payload.encode()))
    }

    @Test
    fun `rejects text that is not a pairing code`() {
        assertNull(PairingPayload.decode("https://example.com"))
        assertNull(PairingPayload.decode(""))
        assertNull(PairingPayload.decode("{\"nope\":1}"))
    }

    @Test
    fun `a payload with no server is a pairing on the WiFi alone`() {
        val decoded = PairingPayload.decode("""{"v":2,"d":"$DEVICE_ID","k":"$KEY","n":"$SECRET"}""")

        assertEquals("", decoded?.server)
        assertEquals(KEY, decoded?.publicKey)
    }

    @Test
    fun `rejects a payload with no secret, which could not confirm anything`() {
        assertNull(PairingPayload.decode("""{"v":2,"d":"$DEVICE_ID","k":"$KEY"}"""))
    }

    @Test
    fun `rejects a device id that is not the digest of the key`() {
        assertNull(PairingPayload.decode("""{"v":2,"d":"0123456789abcdef0123456789abcdef","k":"$KEY","n":"$SECRET"}"""))
    }

    @Test
    fun `rejects a payload from a different version`() {
        assertNull(PairingPayload.decode("""{"v":1,"d":"$DEVICE_ID","k":"$KEY","n":"$SECRET"}"""))
    }

    private companion object {
        const val KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEdmOgVpvF/cEdTUgGwq0NmnP6ogOJkJZ/OFKkQTVx5D1H1bNqEwGSlhcB6O9R/0juTjuKAdHlwOi9E95btJSUJw=="
        val DEVICE_ID = deviceIdOf(KEY)
        const val SECRET = "Gk9mQm5wZXhhbXBsZXNlY3JldA=="
    }
}
