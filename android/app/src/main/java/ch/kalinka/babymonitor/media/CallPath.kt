package ch.kalinka.babymonitor.media

import android.content.Context
import androidx.core.content.edit

/** Which of the three ways the media is actually taking. */
enum class CallPath(val label: String) {
    Unknown("Connecting…"),

    /** Both phones on one network, talking straight to each other. The normal night. */
    Lan("On the WiFi"),

    /** Different networks, but the two punched through — still nobody in the middle. */
    Direct("Direct over the internet"),

    /** Neither of the above worked, so the relay is carrying it. Costs bandwidth. */
    Relayed("Through the relay")
}

/** Settings about how a call is made, kept on the phone that makes it. */
class CallPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("call", Context.MODE_PRIVATE)

    /**
     * Whether a call starts without the camera. On by default: audio costs a fraction of the
     * battery and the bandwidth, and the picture is worth nothing in a dark room anyway until
     * somebody turns a light on. Watch always brings the camera; this is what plain Listen does.
     */
    var startWithVideoOff: Boolean
        get() = prefs.getBoolean(VideoOff, true)
        set(value) = prefs.edit { putBoolean(VideoOff, value) }

    private companion object {
        const val VideoOff = "startWithVideoOff"
    }
}
