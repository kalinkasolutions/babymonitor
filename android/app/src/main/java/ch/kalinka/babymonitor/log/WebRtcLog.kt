package ch.kalinka.babymonitor.log

import android.util.Log
import org.webrtc.Loggable
import org.webrtc.Logging

/**
 * WebRTC's own log. Taking it over stops WebRTC writing to logcat, so everything still goes there.
 *
 * Normally only its warnings and errors are uploaded, a few per source file a minute: one reconnect
 * repeats the same connectivity warning dozens of times. Both go up as warnings, because WebRTC
 * calls routine things errors — a path timing out while the phone changes network, the other end
 * hanging up — and an error in the log is an alert.
 *
 * While the server asks for Debug, its info lines go up too, unthrottled: they are the candidates
 * and the checks on every pair, which is what says why a call did or did not connect.
 */
object WebRtcLog : Loggable {
    /** Read when a call starts, so a change of level applies from the next call on. */
    val minimumSeverity: Logging.Severity
        get() = if (AppLog.uploadLevel == LogLevel.Verbose) Logging.Severity.LS_VERBOSE else Logging.Severity.LS_INFO

    private val uploads = LogThrottle(limit = 5, windowMillis = 60_000)

    override fun onLogMessage(message: String, severity: Logging.Severity, tag: String) {
        Log.println(priorityOf(severity), tag, message)

        val level = levelOf(severity)
        val throttled = level == LogLevel.Warn && !AppLog.isDebugging
        if (throttled && !uploads.allows(tag, System.currentTimeMillis())) {
            return
        }

        AppLog.record(level, tag, message.trimEnd())
    }

    private fun levelOf(severity: Logging.Severity): LogLevel = when (severity) {
        Logging.Severity.LS_ERROR, Logging.Severity.LS_WARNING -> LogLevel.Warn
        Logging.Severity.LS_INFO -> LogLevel.Debug
        else -> LogLevel.Verbose
    }

    private fun priorityOf(severity: Logging.Severity): Int = when (severity) {
        Logging.Severity.LS_ERROR -> Log.ERROR
        Logging.Severity.LS_WARNING -> Log.WARN
        Logging.Severity.LS_INFO -> Log.INFO
        else -> Log.VERBOSE
    }
}
