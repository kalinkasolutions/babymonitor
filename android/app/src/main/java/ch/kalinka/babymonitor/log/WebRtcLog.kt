package ch.kalinka.babymonitor.log

import android.util.Log
import org.webrtc.Loggable
import org.webrtc.Logging

/**
 * WebRTC's own log. Taking it over stops WebRTC writing to logcat, so everything still goes there.
 * Only warnings and errors are uploaded, and only a few per source file a minute: one reconnect
 * repeats the same connectivity warning dozens of times.
 */
object WebRtcLog : Loggable {
    val minimumSeverity = Logging.Severity.LS_INFO

    private val uploads = LogThrottle(limit = 5, windowMillis = 60_000)

    override fun onLogMessage(message: String, severity: Logging.Severity, tag: String) {
        val worthUploading = severity == Logging.Severity.LS_ERROR || severity == Logging.Severity.LS_WARNING
        if (!worthUploading || !uploads.allows(tag, System.currentTimeMillis())) {
            Log.println(priorityOf(severity), tag, message)
            return
        }

        if (severity == Logging.Severity.LS_ERROR) {
            AppLog.e(tag, message)
        } else {
            AppLog.w(tag, message)
        }
    }

    private fun priorityOf(severity: Logging.Severity): Int = when (severity) {
        Logging.Severity.LS_ERROR -> Log.ERROR
        Logging.Severity.LS_WARNING -> Log.WARN
        else -> Log.INFO
    }
}
