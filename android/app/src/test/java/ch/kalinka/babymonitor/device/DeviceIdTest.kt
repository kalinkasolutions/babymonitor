package ch.kalinka.babymonitor.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdTest {
    @Test
    fun `matches the id the backend derives from the same key`() {
        // The same vector as the backend's DeviceIdTests, so the two cannot drift apart.
        val key = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEdmOgVpvF/cEdTUgGwq0NmnP6ogOJkJZ/OFKkQTVx5D1H1bNqEwGSlhcB6O9R/0juTjuKAdHlwOi9E95btJSUJw=="

        assertEquals("227ffade69852ccfdc929494dc7316cc", deviceIdOf(key))
    }
}
