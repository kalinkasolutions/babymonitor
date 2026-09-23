package ch.kalinka.babymonitor.media

import android.content.Context
import android.util.Log
import ch.kalinka.babymonitor.net.IceCandidateDto
import ch.kalinka.babymonitor.net.IceServersDto
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** File-level, because the SDP observers below are top-level functions and share it. */
private const val MediaTag = "WebRtcMediaLink"

/**
 * One OpenGL context for the whole app. The capturer, the encoder, the decoder and the renderer
 * all have to share one, and creating a second is how you get a black picture at one end.
 */
object WebRtcEgl {
    val base: EglBase by lazy { EglBase.create() }
}

/**
 * The real thing: a peer connection carrying audio, and video when it was asked for.
 *
 * Audio processing is deliberately off. Echo cancellation, noise suppression and gain control are
 * tuned for a phone call, and every one of them works to remove exactly what a baby monitor exists
 * to carry — a quiet, steady room with an occasional small sound in it.
 */
class WebRtcMediaLink(
    private val context: Context,
    iceServers: IceServersDto,
    private val sendAudio: Boolean,
    /** Whether this phone may film at all. The track is made either way; the camera is not. */
    private val canFilm: Boolean,

    /** Whether it should be filming from the start. */
    private var sendVideo: Boolean,
    private var quality: CaptureQuality,
    private val listener: MediaLinkListener
) : MediaLink {
    private val factory: PeerConnectionFactory
    private val audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
        .setUseHardwareAcousticEchoCanceler(false)
        .setUseHardwareNoiseSuppressor(false)
        // The only place the microphone can be heard as numbers. WebRTC hands over what it
        // records, never what it plays, so how loud the room is has to be measured at the end
        // that is in it — which is also the end that will one day raise an alarm about it.
        .setSamplesReadyCallback(::onSamples)
        .createAudioDeviceModule()

    private var connection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var channel: DataChannel? = null
    private var incomingAudio: AudioTrack? = null
    private var muted = false
    private var levelSentAt = 0L
    private var level = 0f

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )

        val egl = WebRtcEgl.base.eglBaseContext
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
            .createPeerConnectionFactory()

        val configuration = PeerConnection.RTCConfiguration(iceServers.toIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

            // Every path, in the order ICE prefers them: the same WiFi first, then whatever STUN
            // can punch through between the two networks, and the relay only when neither works.
            iceTransportsType = PeerConnection.IceTransportsType.ALL

            // Keep gathering after the first connection: a phone that changes network mid-night
            // needs candidates ready rather than a fresh negotiation from nothing.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        connection = factory.createPeerConnection(configuration, Observer())

        // Opened by the end that offers, and therefore before the offer is made — a channel added
        // afterwards would need the whole negotiation again. The other end is handed it by
        // onDataChannel rather than opening one of its own.
        if (sendAudio) {
            openChannel(connection?.createDataChannel(ChannelLabel, DataChannel.Init()))
        }

        if (sendAudio) {
            openMicrophone()
        }

        // The track is added now, running or not: a phone that might be asked for a picture later
        // has to have negotiated one, or turning the camera on would mean negotiating again.
        if (sendAudio && canFilm) {
            openCamera(capturing = sendVideo)
        }
    }

    private fun openMicrophone() {
        val source = factory.createAudioSource(noProcessing())
        val track = factory.createAudioTrack("babymonitor-audio", source)
        connection?.addTrack(track, listOf(StreamId))
        audioSource = source
        audioTrack = track
    }

    /**
     * The front camera, because the screen and the front lens face the same way: lighting a dark
     * nursery with the phone's own screen only works for the camera pointing where the light goes.
     */
    private fun openCamera(capturing: Boolean) {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing)
            ?: enumerator.deviceNames.firstOrNull()
            ?: return run {
                Log.e(MediaTag, "Asked to film, but this phone reports no camera at all")
                listener.onFailed("This phone has no camera to send.")
            }

        val capturer = enumerator.createCapturer(name, null)
        val helper = SurfaceTextureHelper.create("babymonitor-capture", WebRtcEgl.base.eglBaseContext)
        val source = factory.createVideoSource(false)
        capturer.initialize(helper, context.applicationContext, source.capturerObserver)

        if (capturing) {
            // Whatever this phone was set to. Small and slow by default, on purpose: a monitor
            // runs all night, and a sharper picture costs the phone that can least afford it.
            capturer.startCapture(quality.width, quality.height, quality.fps)
        }

        val track = factory.createVideoTrack("babymonitor-video", source)
        connection?.addTrack(track, listOf(StreamId))
        Log.i(MediaTag, "Camera $name open, ${if (capturing) "filming at $quality" else "idle"}")

        videoCapturer = capturer
        videoSource = source
        videoTrack = track
        captureHelper = helper
    }

    override fun createOffer() {
        val connection = connection ?: return listener.onFailed("The connection is gone.")
        Log.i(MediaTag, "Building an offer")
        connection.createOffer(
            describing("offer", listener::onFailed) { sdp ->
                connection.setLocalDescription(applying("offer", listener::onFailed), sdp)
                listener.onLocalOffer(sdp.description)
            },
            MediaConstraints()
        )
    }

    override fun acceptOffer(sdp: String) {
        val connection = connection ?: return listener.onFailed("The connection is gone.")
        Log.i(MediaTag, "Answering an offer of ${sdp.length} characters")
        connection.setRemoteDescription(
            settled("offer from the other phone", listener::onFailed) {
                connection.createAnswer(
                    describing("answer", listener::onFailed) { answer ->
                        connection.setLocalDescription(applying("answer", listener::onFailed), answer)
                        listener.onLocalAnswer(answer.description)
                    },
                    MediaConstraints()
                )
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
    }

    override fun acceptAnswer(sdp: String) {
        Log.i(MediaTag, "Applying an answer of ${sdp.length} characters")
        connection?.setRemoteDescription(
            applying("answer from the other phone", listener::onFailed),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    override fun addRemoteCandidate(candidate: IceCandidateDto) {
        connection?.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )
    }

    /**
     * Ten milliseconds of microphone, as loudness. Root mean square rather than a peak, because a
     * peak is a door closing and the thing worth watching is how loud the room is.
     *
     * Reported a few times a second, and eased on the way down: a meter that drops to nothing
     * between two syllables reads as silence when somebody is talking.
     */
    private fun onSamples(samples: JavaAudioDeviceModule.AudioSamples) {
        val data = samples.data
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < data.size) {
            val sample = (data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)
            sum += (sample.toShort().toDouble()).let { it * it }
            count++
            index += 2
        }

        if (count == 0) {
            return
        }

        // Full scale is a 16 bit maximum; the quietest worth drawing is about sixty decibels down.
        val rms = kotlin.math.sqrt(sum / count)
        val decibels = 20 * kotlin.math.log10((rms / Short.MAX_VALUE).coerceAtLeast(1e-7))
        val next = ((decibels + QuietDecibels) / QuietDecibels).toFloat().coerceIn(0f, 1f)
        level = if (next > level) next else level + (next - level) * FallRate

        val now = System.currentTimeMillis()
        if (now - levelSentAt < LevelInterval) {
            return
        }

        levelSentAt = now
        listener.onAudioLevel(level)
    }

    private fun openChannel(opened: DataChannel?) {
        channel = opened ?: return
        opened.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                listener.onMessage(String(bytes, StandardCharsets.UTF_8))
            }

            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (opened.state() == DataChannel.State.OPEN) {
                    listener.onChannelOpen()
                }
            }
        })
    }

    override fun send(text: String) {
        val open = channel?.takeIf { it.state() == DataChannel.State.OPEN } ?: return
        runCatching {
            open.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8)), false))
        }
    }

    override fun setIncomingAudioMuted(muted: Boolean) {
        this.muted = muted
        incomingAudio?.setEnabled(!muted)
    }

    override fun changeQuality(quality: CaptureQuality) {
        this.quality = quality
        runCatching { videoCapturer?.changeCaptureFormat(quality.width, quality.height, quality.fps) }
    }

    /**
     * Reads the pair ICE settled on. The candidate types are the whole answer: two host addresses
     * mean the same network, anything reflexive means two networks that found each other, and a
     * relay candidate on either side means the server is carrying it.
     */
    override fun probePath() {
        val connection = connection ?: return
        connection.getStats { report ->
            val stats = report.statsMap.values
            val pair = stats.firstOrNull {
                it.type == "candidate-pair" && it.members["state"] == "succeeded"
            } ?: return@getStats

            val types = listOf("localCandidateId", "remoteCandidateId")
                .mapNotNull { pair.members[it] as? String }
                .mapNotNull { id -> stats.firstOrNull { it.id == id }?.members?.get("candidateType") }
                .map { it.toString() }

            if (types.size < 2) {
                return@getStats
            }

            listener.onPath(
                when {
                    types.any { it == "relay" } -> CallPath.Relayed
                    types.all { it == "host" } -> CallPath.Lan
                    else -> CallPath.Direct
                }
            )
        }
    }

    override fun setVideoEnabled(on: Boolean) {
        val capturer = videoCapturer ?: return
        if (on == sendVideo) {
            return
        }

        sendVideo = on
        runCatching {
            if (on) {
                capturer.startCapture(quality.width, quality.height, quality.fps)
            } else {
                // Stops the camera itself, so the light beside it goes out and the battery stops
                // paying for a picture nobody asked to see.
                capturer.stopCapture()
            }
        }
    }

    override fun close() {
        Log.i(MediaTag, "Closing the peer connection")
        incomingAudio = null
        channel?.dispose()
        channel = null

        // The capturer holds the camera open and has to be stopped before anything it feeds is
        // disposed, or the next call finds the camera still in use.
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoCapturer = null
        captureHelper?.dispose()
        captureHelper = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null

        connection?.dispose()
        connection = null
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        factory.dispose()
        audioDeviceModule.release()
    }

    /** Every default is something that helps a voice call and hurts a baby monitor. */
    private fun noProcessing() = MediaConstraints().apply {
        listOf(
            "googEchoCancellation",
            "googAutoGainControl",
            "googNoiseSuppression",
            "googHighpassFilter"
        ).forEach { mandatory.add(MediaConstraints.KeyValuePair(it, "false")) }
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            listener.onLocalCandidate(
                IceCandidateDto(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            )
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(MediaTag, "Peer connection is now $newState")
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> listener.onConnected()
                PeerConnection.PeerConnectionState.DISCONNECTED -> listener.onDisconnected()
                PeerConnection.PeerConnectionState.FAILED ->
                    listener.onFailed("The two phones could not reach each other.")

                else -> Unit
            }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            when (val track = transceiver?.receiver?.track()) {
                is VideoTrack -> listener.onRemoteVideo(track)

                // Kept so it can be silenced later, and silenced now if it already was: a track
                // that arrives after the mute was pressed must not start talking.
                is AudioTrack -> {
                    incomingAudio = track
                    track.setEnabled(!muted)
                }

                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = openChannel(channel)
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        override fun onRemoveTrack(receiver: RtpReceiver?) = Unit
    }

    private companion object {
        const val StreamId = "babymonitor"
        const val ChannelLabel = "status"

        /** Below this much under full scale, a nursery is simply quiet. */
        const val QuietDecibels = 60.0

        const val LevelInterval = 200L
        const val FallRate = 0.25f
    }
}

/**
 * The one place the fetched ICE configuration becomes something WebRTC understands.
 *
 * STUN is asked nothing and told nothing: it exists to hand a phone its own public address. Only
 * the relay takes a credential, because only the relay carries traffic worth paying for.
 */
fun IceServersDto.toIceServers(): List<PeerConnection.IceServer> = urls.map { url ->
    val relay = !url.startsWith("stun:", ignoreCase = true)
    PeerConnection.IceServer.builder(url)
        .setUsername(if (relay) username else "")
        .setPassword(if (relay) credential else "")
        .createIceServer()
}

/**
 * Turns the four-method Java observer into the one callback each use actually has.
 *
 * The failures are not ignored. A description that cannot be built or applied ends the call here,
 * where the reason is still known — left silent, the call sits in negotiation until the answer
 * timeout calls it "No answer", which points at the other phone when the fault was at this end.
 */
private fun describing(step: String, onFailed: (String) -> Unit, onCreated: (SessionDescription) -> Unit) =
    object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onCreated(description)
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = fail(step, "create", error, onFailed)
        override fun onSetFailure(error: String?) = fail(step, "apply", error, onFailed)
    }

private fun settled(step: String, onFailed: (String) -> Unit, onSet: () -> Unit) = object : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) = Unit
    override fun onSetSuccess() = onSet()
    override fun onCreateFailure(error: String?) = fail(step, "create", error, onFailed)
    override fun onSetFailure(error: String?) = fail(step, "apply", error, onFailed)
}

private fun applying(step: String, onFailed: (String) -> Unit) = object : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = fail(step, "create", error, onFailed)
    override fun onSetFailure(error: String?) = fail(step, "apply", error, onFailed)
}

private fun fail(step: String, action: String, error: String?, onFailed: (String) -> Unit) {
    Log.e(MediaTag, "Could not $action the $step: ${error ?: "no reason given"}")
    onFailed("The two phones could not agree on a connection.")
}
