package ch.lqy.babyphone.media

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ch.lqy.babyphone.net.LightMode

/**
 * What the monitor screen talks to. Nothing but a way in to [CallCenter], which outlives it —
 * this view model dies whenever the screen is left, and a call must not.
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val calls = CallCenter.of(application)

    val others = calls.others
    val online = calls.online
    val state = calls.state
    val message = calls.message
    val remoteVideo = calls.remoteVideo
    val armed = calls.armed
    val quality = calls.quality

    fun listenTo(deviceId: String, video: Boolean = false) = calls.listenTo(deviceId, video)
    fun hangUp() = calls.hangUp()
    fun toggleMute() = calls.toggleMute()
    fun showVideo(on: Boolean) = calls.showVideo(on)
    fun setRelayOnly(value: Boolean) = calls.setRelayOnly(value)
    fun setAlarm(settings: NoiseAlarmSettings) = calls.setAlarm(settings)

    /** Told by the screen, so the alarm knows whether anybody is already looking. */
    fun watching(value: Boolean) {
        calls.watching = value
    }
    val light = calls.light
    val muted = calls.muted
    val video = calls.video
    val peerStatus = calls.peerStatus
    val noise = calls.noise
    val alarm = calls.alarm
    val arming = calls.arming
    val silent = calls.silent
    val retrying = calls.retrying
    val path = calls.path
    val relayOnly = calls.relayOnly

    fun askForLight(mode: LightMode, brightness: Float, seconds: Int = 0) =
        calls.askForLight(mode, brightness, seconds)
    fun chooseQuality(value: CaptureQuality) = calls.chooseQuality(value)
    fun arm() = calls.arm()
    fun disarm() = calls.disarm()
    fun dismissMessage() = calls.dismissMessage()
    fun refresh() = calls.refresh()

    fun hasMicrophonePermission() = calls.hasMicrophonePermission()
    fun hasCameraPermission() = calls.hasCameraPermission()
    fun hasNotificationPermission() = calls.hasNotificationPermission()
    fun canLightWhileLocked() = calls.canLightWhileLocked()
    fun canStartFromBackground() = calls.canStartFromBackground()
    fun canScheduleExactAlarm() = calls.canScheduleExactAlarm()
}
