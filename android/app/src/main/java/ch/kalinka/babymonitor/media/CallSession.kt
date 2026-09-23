package ch.kalinka.babymonitor.media

import ch.kalinka.babymonitor.net.CallRequest
import ch.kalinka.babymonitor.net.IceCandidateDto
import ch.kalinka.babymonitor.net.LightMode
import ch.kalinka.babymonitor.net.LightRequest
import ch.kalinka.babymonitor.net.QualityRequest
import ch.kalinka.babymonitor.net.SignalKinds
import ch.kalinka.babymonitor.net.VideoRequest
import kotlinx.serialization.json.Json

/** Long enough to see the room, short enough not to be a wake-up call. */
const val GlanceSeconds = 8

/** What the light on the other phone is doing. Part of the call, like the picture size. */
data class LightState(
    val mode: LightMode = LightMode.Off,
    val brightness: Float = 1f
)

/**
 * Who does what in a call. The phone that captures is the one that offers, because it is the one
 * whose media the offer describes; the phone that asked to listen answers.
 */
enum class CallRole { Listener, Speaker }

sealed interface CallState {
    data object Idle : CallState

    /** This phone asked to listen and is waiting for the other one's offer. */
    data class Asking(val peerDeviceId: String) : CallState

    /** Negotiating: an offer or an answer is in flight. */
    data class Negotiating(val peerDeviceId: String, val role: CallRole) : CallState

    /** Audio is flowing. */
    data class Live(val peerDeviceId: String, val role: CallRole) : CallState

    data class Failed(val reason: String) : CallState
}

/** Everything that can happen to a call, from the user, the other phone, or the media stack. */
sealed interface CallEvent {
    /** The user pressed listen, or watch, which is listen with the camera on. */
    data class Listen(val peerDeviceId: String, val video: Boolean = false) : CallEvent

    /** The phone watching picked a picture size — before a call, or during one. */
    data class ChooseQuality(val quality: CaptureQuality) : CallEvent

    /** The phone watching turned the camera on or off. */
    data class AskForVideo(val on: Boolean) : CallEvent

    /** The phone watching asked this one to start or stop its camera. */
    data class VideoAsked(val peerDeviceId: String, val on: Boolean) : CallEvent

    /** The phone watching asked this one to change what it is sending. */
    data class QualityAsked(val peerDeviceId: String, val quality: CaptureQuality) : CallEvent

    /**
     * The other phone asked this one to send. [allowed] is decided outside: a phone whose key
     * this one has not confirmed does not get to switch on a microphone in a nursery.
     */
    data class Asked(
        val peerDeviceId: String,
        val allowed: Boolean,
        val video: Boolean = false,
        val quality: CaptureQuality = CaptureQuality.Standard
    ) : CallEvent

    data class OfferReceived(val peerDeviceId: String, val sdp: String) : CallEvent
    data class AnswerReceived(val peerDeviceId: String, val sdp: String) : CallEvent
    data class CandidateReceived(val peerDeviceId: String, val candidate: IceCandidateDto) : CallEvent

    /** The local peer connection produced a description, which now has to travel. */
    data class LocalOffer(val sdp: String) : CallEvent
    data class LocalAnswer(val sdp: String) : CallEvent
    data class LocalCandidate(val candidate: IceCandidateDto) : CallEvent

    /**
     * The user changed the other phone's light: its colour, its brightness, or both. [seconds] of
     * zero leaves it on until something turns it off.
     */
    data class AskForLight(
        val mode: LightMode,
        val brightness: Float = 1f,
        val seconds: Int = 0
    ) : CallEvent

    /** The other phone asked for this one's light. */
    data class LightAsked(
        val peerDeviceId: String,
        val mode: LightMode,
        val brightness: Float,
        val seconds: Int
    ) : CallEvent

    data object Connected : CallEvent
    data class Stopped(val peerDeviceId: String) : CallEvent
    data object HangUp : CallEvent
    data class Failure(val reason: String) : CallEvent
}

/** What the session wants done. The view model is the only thing that knows how. */
sealed interface CallEffect {
    /**
     * Open a peer connection. [CallRole.Speaker] is the end that opens the microphone, and the
     * camera too when [video] was asked for.
     */
    data class Open(val role: CallRole, val video: Boolean, val quality: CaptureQuality) : CallEffect

    /** Retune the camera that is already running, without touching the call. */
    data class SetQuality(val quality: CaptureQuality) : CallEffect

    /** Start or stop this phone's camera. The track stays where it is either way. */
    data class SetVideo(val on: Boolean) : CallEffect

    data object CreateOffer : CallEffect
    data class AcceptOffer(val sdp: String) : CallEffect
    data class AcceptAnswer(val sdp: String) : CallEffect
    data class AddCandidate(val candidate: IceCandidateDto) : CallEffect
    data class Send(val toDeviceId: String, val kind: String, val body: String) : CallEffect

    /** Light this phone's own screen, because the phone watching asked for it. */
    data class ShowLight(val mode: LightMode, val brightness: Float, val seconds: Int) : CallEffect

    data object Close : CallEffect
}

/**
 * The call, as a value: a state and whatever arrived too early to use yet. Deliberately knows
 * nothing about WebRTC, sockets or Android, so the awkward parts — an answer that arrives before
 * the offer was applied, a second phone calling while one is already connected, a stop from
 * somebody else's phone — can be tested without any of them.
 */
data class CallSession(
    val state: CallState = CallState.Idle,

    /**
     * How much picture this call is carrying. A property of the call and not of either phone: it
     * goes back to the default when the call ends, because the next one is a different question.
     */
    val quality: CaptureQuality = CaptureQuality.Standard,

    /** What the other phone's screen is doing, so a slider can show it and a switch can restore it. */
    val light: LightState = LightState(),

    /** Whether the camera is running. Asked for at the start and changed at any point after. */
    val video: Boolean = false,

    /**
     * Candidates that arrived before the description they belong to. WebRTC rejects those, and
     * they arrive early routinely, so they wait here rather than being dropped.
     */
    val pendingCandidates: List<IceCandidateDto> = emptyList(),
    val remoteDescriptionSet: Boolean = false
) {
    val peerDeviceId: String? = when (state) {
        is CallState.Asking -> state.peerDeviceId
        is CallState.Negotiating -> state.peerDeviceId
        is CallState.Live -> state.peerDeviceId
        else -> null
    }

    val isBusy: Boolean = peerDeviceId != null

    /** This phone is the one listening in this call, which is the end that decides the picture. */
    private val isWatching: Boolean = when (state) {
        is CallState.Asking -> true
        is CallState.Negotiating -> state.role == CallRole.Listener
        is CallState.Live -> state.role == CallRole.Listener
        else -> false
    }

    fun on(event: CallEvent): Pair<CallSession, List<CallEffect>> = when (event) {
        is CallEvent.Listen -> when {
            isBusy -> this to emptyList()
            else -> copy(state = CallState.Asking(event.peerDeviceId), video = event.video) to
                listOf(
                    CallEffect.Send(
                        event.peerDeviceId,
                        SignalKinds.Start,
                        RequestFormat.encodeToString(CallRequest(event.video, quality.name))
                    )
                )
        }

        is CallEvent.Asked -> when {
            !event.allowed -> this to listOf(CallEffect.Send(event.peerDeviceId, SignalKinds.Stop, Refused))

            // One call at a time: a second phone asking is told no rather than quietly taking over.
            isBusy && !isFrom(event.peerDeviceId) ->
                this to listOf(CallEffect.Send(event.peerDeviceId, SignalKinds.Stop, Busy))

            // The same phone asking again means the call it is asking about is already gone —
            // its network dropped, or it was killed — and this end simply has not noticed yet.
            // Refusing as busy would leave the two of them waiting for each other all night.
            isBusy -> CallSession(
                state = CallState.Negotiating(event.peerDeviceId, CallRole.Speaker),
                quality = event.quality,
                video = event.video
            ) to listOf(
                CallEffect.Close,
                CallEffect.Open(CallRole.Speaker, event.video, event.quality),
                CallEffect.CreateOffer
            )

            else -> copy(
                state = CallState.Negotiating(event.peerDeviceId, CallRole.Speaker),
                quality = event.quality,
                video = event.video
            ) to listOf(
                CallEffect.Open(CallRole.Speaker, event.video, event.quality),
                CallEffect.CreateOffer
            )
        }

        is CallEvent.OfferReceived -> when {
            !isFrom(event.peerDeviceId) -> this to listOf(CallEffect.Send(event.peerDeviceId, SignalKinds.Stop, Busy))
            else -> copy(state = CallState.Negotiating(event.peerDeviceId, CallRole.Listener)) to
                listOf(
                    CallEffect.Open(CallRole.Listener, video = false, quality = quality),
                    CallEffect.AcceptOffer(event.sdp)
                )
        }

        is CallEvent.AnswerReceived -> when {
            !isFrom(event.peerDeviceId) -> this to emptyList()
            else -> copy(remoteDescriptionSet = true, pendingCandidates = emptyList()) to
                (listOf(CallEffect.AcceptAnswer(event.sdp)) + pendingCandidates.map(CallEffect::AddCandidate))
        }

        is CallEvent.CandidateReceived -> when {
            !isFrom(event.peerDeviceId) -> this to emptyList()

            // Before the description it belongs to, a candidate has nothing to attach to.
            !remoteDescriptionSet -> copy(pendingCandidates = pendingCandidates + event.candidate) to emptyList()

            else -> this to listOf(CallEffect.AddCandidate(event.candidate))
        }

        is CallEvent.LocalOffer -> sendToPeer(SignalKinds.Offer, event.sdp)

        // An answer exists only once the offer was applied, so whatever was queued can go now.
        is CallEvent.LocalAnswer -> copy(remoteDescriptionSet = true, pendingCandidates = emptyList()) to
            (send(SignalKinds.Answer, event.sdp) + pendingCandidates.map(CallEffect::AddCandidate))

        is CallEvent.LocalCandidate ->
            sendToPeer(SignalKinds.Candidate, CandidateFormat.encodeToString(event.candidate))

        // Only the end that is watching chooses, and only that end's choice travels. Before a call
        // it is simply remembered, so the next one starts the way the last one was left.
        is CallEvent.ChooseQuality -> copy(quality = event.quality).let { next ->
            if (isWatching) {
                next to next.send(
                    SignalKinds.Quality,
                    QualityFormat.encodeToString(QualityRequest(event.quality.name))
                )
            } else {
                next to emptyList()
            }
        }

        is CallEvent.AskForVideo -> copy(video = event.on).let { next ->
            if (isWatching) {
                next to next.send(SignalKinds.Video, VideoFormat.encodeToString(VideoRequest(event.on)))
            } else {
                next to emptyList()
            }
        }

        is CallEvent.VideoAsked -> when {
            !isFrom(event.peerDeviceId) -> this to emptyList()
            else -> copy(video = event.on) to listOf(CallEffect.SetVideo(event.on))
        }

        is CallEvent.QualityAsked -> when {
            !isFrom(event.peerDeviceId) -> this to emptyList()
            else -> copy(quality = event.quality) to listOf(CallEffect.SetQuality(event.quality))
        }

        is CallEvent.AskForLight -> when (state) {
            // Only while there is a call: a light nobody is watching is just a light left on.
            is CallState.Live -> copy(light = LightState(event.mode, event.brightness)).let { next ->
                next to next.send(
                    SignalKinds.Light,
                    LightFormat.encodeToString(
                        LightRequest(event.mode.name, event.brightness, event.seconds)
                    )
                )
            }

            else -> this to emptyList()
        }

        is CallEvent.LightAsked -> when {
            // The phone in the call is the only one that gets to light this room.
            !isFrom(event.peerDeviceId) -> this to emptyList()
            else -> copy(light = LightState(event.mode, event.brightness)) to
                listOf(CallEffect.ShowLight(event.mode, event.brightness, event.seconds))
        }

        CallEvent.Connected -> when (val current = state) {
            is CallState.Negotiating -> copy(state = CallState.Live(current.peerDeviceId, current.role)) to emptyList()
            else -> this to emptyList()
        }

        is CallEvent.Stopped -> when {
            !isFrom(event.peerDeviceId) -> this to emptyList()
            else -> CallSession() to listOf(CallEffect.ShowLight(LightMode.Off, 0f, 0), CallEffect.Close)
        }

        CallEvent.HangUp -> when (val peer = peerDeviceId) {
            null -> this to emptyList()

            // The light goes out with the call. Leaving a nursery lit because somebody closed a
            // screen is the one failure mode nobody would forgive.
            else -> CallSession() to listOf(
                CallEffect.Send(peer, SignalKinds.Light, LightFormat.encodeToString(LightRequest())),
                CallEffect.Send(peer, SignalKinds.Stop, ""),
                CallEffect.Close
            )
        }

        is CallEvent.Failure -> CallSession(state = CallState.Failed(event.reason)) to
            (peerDeviceId?.let { listOf(CallEffect.Send(it, SignalKinds.Stop, event.reason)) }.orEmpty() +
                listOf(CallEffect.ShowLight(LightMode.Off, 0f, 0), CallEffect.Close))
    }

    private fun isFrom(deviceId: String) = peerDeviceId == deviceId

    private fun sendToPeer(kind: String, body: String) = this to send(kind, body)

    private fun send(kind: String, body: String): List<CallEffect> =
        peerDeviceId?.let { listOf(CallEffect.Send(it, kind, body)) }.orEmpty()

    companion object {
        private val CandidateFormat = Json
        private val RequestFormat = Json
        private val LightFormat = Json
        private val QualityFormat = Json
        private val VideoFormat = Json

        const val Busy = "That phone is already in a call."
        const val Refused = "That phone has not confirmed this one's key."
    }
}
