package ch.kalinka.babymonitor.log

import android.util.Log
import org.webrtc.Loggable
import org.webrtc.Logging

/**
 * WebRTC's own log. Taking it over stops WebRTC writing to logcat, so everything still goes there;
 * only warnings and errors are uploaded, because its info lines run to hundreds per call.
 */
object WebRtcLog : Loggable {
    val minimumSeverity = Logging.Severity.LS_INFO

    override fun onLogMessage(message: String, severity: Logging.Severity, tag: String) {
        when (severity) {
            Logging.Severity.LS_ERROR -> AppLog.e(tag, message)
            Logging.Severity.LS_WARNING -> AppLog.w(tag, message)
            else -> Log.i(tag, message)
        }
    }
}
