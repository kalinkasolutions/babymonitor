package ch.kalinka.babymonitor.log

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One line of the phone's log, as the server's api/logs takes it. */
@Serializable
data class LogEntry(
    val time: String,
    val level: String,
    val tag: String,
    val message: String,
    val error: String? = null
)

@Serializable
private data class LogUploadBody(val appVersion: String, val entries: List<LogEntry>)

private val json = Json { explicitNulls = false }

fun encodeLogEntry(entry: LogEntry): String = json.encodeToString(entry)

/**
 * The lines at [minimum] or above. A line cut short by a crash mid-write does not parse; it is left
 * out rather than spoil the batch.
 */
fun logUploadBody(appVersion: String, lines: List<String>, minimum: LogLevel = LogLevel.Verbose): String =
    json.encodeToString(
        LogUploadBody(
            appVersion = appVersion,
            entries = lines
                .mapNotNull { runCatching { json.decodeFromString<LogEntry>(it) }.getOrNull() }
                .filter { (LogLevel.named(it.level) ?: LogLevel.Info) >= minimum }
        )
    )

@Serializable
private data class LogUploadReply(val level: String? = null)

/** The level the server wants from now on, or null from a server too old to say. */
fun uploadLevelFrom(reply: String): LogLevel? =
    runCatching { LogLevel.named(json.decodeFromString<LogUploadReply>(reply).level) }.getOrNull()
