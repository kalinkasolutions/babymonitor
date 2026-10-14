package ch.kalinka.babymonitor.log

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
}
