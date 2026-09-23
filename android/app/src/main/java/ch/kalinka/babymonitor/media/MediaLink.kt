package ch.kalinka.babymonitor.media

import ch.kalinka.babymonitor.net.IceCandidateDto
import org.webrtc.VideoTrack

/**
 * One connection to one other phone, as little of WebRTC as the rest of the app needs to know.
 * Everything above this is plain Kotlin and testable without a device.
 */
interface MediaLink {
    fun createOffer()
    fun acceptOffer(sdp: String)
    fun acceptAnswer(sdp: String)
    fun addRemoteCandidate(candidate: IceCandidateDto)

    /** Retunes a camera that is already running. No renegotiation, so the picture holds. */
    fun changeQuality(quality: CaptureQuality)

    /**
     * Starts or stops the camera. The track itself stays negotiated either way, which is what
     * makes this instant: adding one to a live connection would mean offering all over again.
     */
    fun setVideoEnabled(on: Boolean)

    /** Asks which way the media is going. Answered on [MediaLinkListener.onPath]. */
    fun probePath()

    /**
     * Sends a line of text straight to the other phone, over the same encrypted connection as the
     * media and without the server in the middle.
     */
    fun send(text: String)

    /** Silences what is coming in, here and nowhere else — the other phone keeps sending. */
    fun setIncomingAudioMuted(muted: Boolean)

    fun close()
}

/** What a link reports back. Called on WebRTC's own threads. */
interface MediaLinkListener {
    fun onLocalOffer(sdp: String)
    fun onLocalAnswer(sdp: String)
    fun onLocalCandidate(candidate: IceCandidateDto)
    fun onConnected()

    /**
     * The path has gone, without WebRTC having given up on it yet. A phone in flight mode looks
     * like this for as long as the checks take to run out, which is longer than anybody watching
     * a nursery should wait.
     */
    fun onDisconnected()

    fun onFailed(reason: String)

    /** Something the other phone said over the data channel. */
    fun onMessage(text: String)

    /** How loud the room is, nought to one, from the phone doing the listening. */
    fun onAudioLevel(level: Float)

    /** Which way the media turned out to be going. */
    fun onPath(path: CallPath)

    /**
     * The data channel is open. Anything said before this went nowhere — a channel exists from
     * the moment it is created and carries nothing until the two ends have agreed on it.
     */
    fun onChannelOpen()

    /**
     * Video arrived from the other phone. A [VideoTrack] rather than something of our own: what
     * draws it is a WebRTC renderer, so hiding the type here would only mean unwrapping it again.
     */
    fun onRemoteVideo(track: VideoTrack)
}
