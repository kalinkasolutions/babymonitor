package ch.kalinka.babymonitor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class LineReaderTest {
    @Test
    fun `reads a line up to the newline`() {
        assertEquals("hello", stream("hello\nworld\n").readLine(LIMIT))
    }

    @Test
    fun `reads a last line with no newline after it`() {
        assertEquals("hello", stream("hello").readLine(LIMIT))
    }

    @Test
    fun `an empty stream is the end rather than an empty line`() {
        assertNull(stream("").readLine(LIMIT))
    }

    @Test
    fun `a carriage return before the newline is not part of the line`() {
        assertEquals("hello", stream("hello\r\n").readLine(LIMIT))
    }

    @Test
    fun `a line exactly at the limit is still read`() {
        val line = "x".repeat(LIMIT)

        assertEquals(line, stream("$line\n").readLine(LIMIT))
    }

    /**
     * The whole reason this exists. Anyone on the WiFi can open the socket this reads from, and
     * a sender that never sends a newline would otherwise grow a string until the phone died.
     */
    @Test
    fun `a line that never ends is refused rather than read`() {
        val endless = stream("x".repeat(LIMIT * 4))

        assertThrows(IOException::class.java) { endless.readLine(LIMIT) }
    }

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray())

    private companion object {
        const val LIMIT = 64
    }
}
