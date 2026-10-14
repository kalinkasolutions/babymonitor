package ch.kalinka.babymonitor.log

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogEntryTest {
    private val entry = LogEntry("2026-10-14T12:12:11Z", "Warn", "Test", "hello")

    @Test
    fun `the body carries the version and every entry`() {
        val body = Json.parseToJsonElement(logUploadBody("0.0.5-alpha", listOf(encodeLogEntry(entry)))).jsonObject

        assertEquals("0.0.5-alpha", body["appVersion"]!!.jsonPrimitive.content)
        assertEquals("hello", body["entries"]!!.jsonArray.single().jsonObject["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a line cut short by a crash is left out`() {
        val cutShort = encodeLogEntry(entry).take(20)
        val body = Json.parseToJsonElement(logUploadBody("0.0.5-alpha", listOf(cutShort, encodeLogEntry(entry)))).jsonObject

        assertEquals(1, body["entries"]!!.jsonArray.size)
    }

    @Test
    fun `lines below the level the server asks for are left out`() {
        val lines = listOf("Debug", "Info", "Warn").map { encodeLogEntry(entry.copy(level = it, message = it)) }

        val body = Json.parseToJsonElement(logUploadBody("0.0.9-alpha", lines, LogLevel.Info)).jsonObject
        val sent = body["entries"]!!.jsonArray.map { it.jsonObject["message"]!!.jsonPrimitive.content }

        assertEquals(listOf("Info", "Warn"), sent)
    }

    @Test
    fun `the server's reply names the level to upload from now on`() {
        assertEquals(LogLevel.Verbose, uploadLevelFrom("""{"level":"Verbose"}"""))
        assertEquals(LogLevel.Debug, uploadLevelFrom("""{"level":"debug"}"""))
    }

    @Test
    fun `an older server's empty reply changes nothing`() {
        assertNull(uploadLevelFrom(""))
        assertNull(uploadLevelFrom("""{"level":"nonsense"}"""))
    }
}
