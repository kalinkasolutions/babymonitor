package ch.kalinka.babymonitor.log

import java.io.File

/**
 * Log lines waiting to be uploaded. Every line goes straight to a file, so the ones written just
 * before a crash are still there when the app starts again.
 */
class LogOutbox(directory: File, private val maxBytes: Long = DefaultMaxBytes) {
    private val pending = File(directory, "pending.jsonl")
    private val sending = File(directory, "sending.jsonl")

    init {
        directory.mkdirs()
    }

    // Past the cap the newest lines are dropped: the start of a problem is the part worth keeping.
    @Synchronized
    fun append(line: String) {
        if (pending.length() < maxBytes) {
            pending.appendText(line + "\n")
        }
    }

    /** The lines that failed to send last time if there are any, otherwise everything since. */
    @Synchronized
    fun nextBatch(): List<String> {
        if (!sending.exists() && pending.exists()) {
            pending.renameTo(sending)
        }
        return readSending()
    }

    /** Forgets the first [count] lines of the batch, once the server has them. */
    @Synchronized
    fun markSent(count: Int) {
        val rest = readSending().drop(count)
        if (rest.isEmpty()) {
            sending.delete()
        } else {
            sending.writeText(rest.joinToString("\n", postfix = "\n"))
        }
    }

    @Synchronized
    fun clear() {
        pending.delete()
        sending.delete()
    }

    private fun readSending(): List<String> =
        if (sending.exists()) sending.readLines().filter { it.isNotBlank() } else emptyList()

    private companion object {
        // Room for a few minutes of WebRTC's own lines while debugging, uploaded or not.
        const val DefaultMaxBytes = 5_000_000L
    }
}
