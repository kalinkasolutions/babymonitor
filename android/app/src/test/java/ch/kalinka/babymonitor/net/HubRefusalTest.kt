package ch.kalinka.babymonitor.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the person holding the phone is told when the hub refuses a call. The text is pulled out
 * of another library's wrapper message, so it is worth pinning: the whole point of these is that
 * they send someone to the right problem.
 */
class HubRefusalTest {
    @Test
    fun `the server's own words are what get shown`() {
        val wrapped = RuntimeException(
            "An unexpected error occurred invoking 'Signal' on the server. " +
                "HubException: That phone is not one this account can reach."
        )

        assertEquals("That phone is not one this account can reach.", hubRefusal(wrapped))
    }

    @Test
    fun `a server without the method is an old server, not a sleeping phone`() {
        val missing = RuntimeException("The 'Signal' method does not exist on the server.")

        assertEquals("This server cannot connect calls yet. It is older than this app.", hubRefusal(missing))
    }

    @Test
    fun `anything else is passed through rather than swallowed`() {
        assertEquals("Websocket closed.", hubRefusal(RuntimeException("Websocket closed.")))
        assertEquals("The server refused the call.", hubRefusal(RuntimeException()))
    }
}
