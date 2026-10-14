package ch.kalinka.babymonitor.device

import ch.kalinka.babymonitor.net.Availability
import org.junit.Assert.assertEquals
import org.junit.Test

class ReachabilityTest {
    @Test
    fun `a phone that cannot be reached is that, whatever it said before`() {
        assertEquals(Reachability.Unreachable, Reachability.of(online = false, Availability(armed = true)))
    }

    @Test
    fun `a phone not set up as the monitor cannot be watched`() {
        assertEquals(Reachability.NotArmed, Reachability.of(online = true, Availability(armed = false)))
    }

    @Test
    fun `a phone in another call says so`() {
        assertEquals(Reachability.InACall, Reachability.of(online = true, Availability(armed = true, busy = true)))
    }

    @Test
    fun `an older app that says nothing is taken as ready`() {
        assertEquals(Reachability.Ready, Reachability.of(online = true, availability = null))
    }
}
