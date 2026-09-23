package ch.kalinka.babymonitor.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * One newline-terminated line, refusing anything longer than [limit] rather than growing to meet
 * it.
 *
 * `BufferedReader.readLine` has no such limit, which is fine when the other end is trusted and is
 * not what this reads from: the LAN socket takes connections from anyone on the WiFi, and the
 * signature that decides whether to believe them is checked on what this returns. A sender that
 * never sends a newline would otherwise grow a string until the phone in the nursery died.
 */
internal fun InputStream.readLine(limit: Int): String? {
    val line = ByteArrayOutputStream()
    while (true) {
        val byte = read()
        when {
            byte < 0 -> return if (line.size() == 0) null else line.toString(Charsets.UTF_8.name())
            byte == NewLine -> return line.toString(Charsets.UTF_8.name())
            line.size() >= limit -> throw IOException("A local message went past $limit bytes without ending")

            // Tolerated so a line ending either way reads the same, and dropped so it never
            // reaches the signature check as part of the signed text.
            byte != CarriageReturn -> line.write(byte)
        }
    }
}

private const val NewLine = '\n'.code
private const val CarriageReturn = '\r'.code
