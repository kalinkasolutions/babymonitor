package ch.kalinka.babymonitor.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import ch.kalinka.babymonitor.device.Household
import ch.kalinka.babymonitor.device.Phone
import ch.kalinka.babymonitor.net.ApiClient
import ch.kalinka.babymonitor.net.ApiResult
import ch.kalinka.babymonitor.net.CallRequest
import ch.kalinka.babymonitor.net.DeviceEvent
import ch.kalinka.babymonitor.net.DeviceStream
import ch.kalinka.babymonitor.net.IceCandidateDto
import ch.kalinka.babymonitor.net.IceServersDto
import ch.kalinka.babymonitor.net.LanSignalling
import ch.kalinka.babymonitor.net.LightMode
import ch.kalinka.babymonitor.net.LightRequest
import ch.kalinka.babymonitor.net.QualityRequest
import ch.kalinka.babymonitor.net.SignalKinds
import ch.kalinka.babymonitor.net.SignalMessage
import ch.kalinka.babymonitor.net.SignalResult
import ch.kalinka.babymonitor.net.SignalReplayGuard
import ch.kalinka.babymonitor.net.SignalTrust
import ch.kalinka.babymonitor.net.signed
import ch.kalinka.babymonitor.net.trust
import ch.kalinka.babymonitor.net.VideoRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.webrtc.VideoTrack

/**
 * The call, for as long as the app is installed rather than for as long as a screen is showing.
 *
 * This deliberately does not live in a view model. A monitor's whole job is to keep running with
 * nobody looking at it: switching tabs, locking the phone or the system trimming an activity all
 * destroy a view model, and each of those was taking the microphone with it. Held against the
 * application instead, and kept alive by [MonitorService] once a call is up, so the only things
 * that end a call are hanging up, the other phone going away, and the process dying.
 */
class CallCenter private constructor(private val context: Context) {
    private val api = ApiClient(context)
    private val stream = DeviceStream(api)

    /**
     * The same signalling, straight across the WiFi. Tried first when the other phone is on this
     * network: it is quicker, and it is what keeps two phones in one house working when the
     * internet does not.
     */
    private val lan = LanSignalling(
        context = context,
        thisDeviceId = { household.deviceId },
        confirmedKeyFor = { deviceId -> household.keyOf(deviceId) },
        onSignal = { signal -> scope.launch { onSignal(signal) } },
        onUnverified = { signal -> scope.launch { _pairings.emit(signal) } }
    )

    private val _pairings = MutableSharedFlow<SignalMessage>(extraBufferCapacity = 4)

    /** Somebody scanned this phone's code and said so, across the WiFi or through the relay. */
    val pairings: SharedFlow<SignalMessage> = _pairings.asSharedFlow()
    private val household = Household(context)
    private val replay = SignalReplayGuard()
    private val alarmSettings = AlarmSettings(context)
    private val callPreferences = CallPreferences(context)
    private var watch = NoiseWatch(alarmSettings.current)
    private val json = Json { ignoreUnknownKeys = true }

    // Main, so every event lands on one thread: they arrive from the hub, from WebRTC's own
    // threads and from the screen, and a state machine three threads can advance at once will
    // eventually answer its own offer.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _online = MutableStateFlow<Set<String>>(emptySet())

    /** The paired phones that can answer right now, on the WiFi or through the relay. */
    val online: StateFlow<Set<String>> = _online.asStateFlow()

    /** What the relay last said about who is connected to it. */
    private var onRelay: Set<String> = emptySet()

    private val _others = MutableStateFlow<List<Phone>>(emptyList())
    val others: StateFlow<List<Phone>> = _others.asStateFlow()

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _muted = MutableStateFlow(false)

    /** Whether this phone is playing what it is being sent. Local, and told to nobody. */
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _alarm = MutableStateFlow(alarmSettings.current)

    /** When the room counts as loud enough to wake somebody. */
    val alarm: StateFlow<NoiseAlarmSettings> = _alarm.asStateFlow()

    private val _path = MutableStateFlow(CallPath.Unknown)

    /** Which way the media is going: the WiFi, the internet, or the relay. */
    val path: StateFlow<CallPath> = _path.asStateFlow()

    private val _startWithVideoOff = MutableStateFlow(callPreferences.startWithVideoOff)

    /** Whether Listen starts without the camera. */
    val startWithVideoOff: StateFlow<Boolean> = _startWithVideoOff.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(callPreferences.keepScreenOn)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _retrying = MutableStateFlow(false)

    /** Whether the call is being re-established after dropping out on its own. */
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    private val _silent = MutableStateFlow(false)

    /** Whether the phone being watched has stopped saying anything at all. */
    val silent: StateFlow<Boolean> = _silent.asStateFlow()

    private val _arming = MutableStateFlow(0f)

    /** How far the room has got towards an alarm, nought to one. */
    val arming: StateFlow<Float> = _arming.asStateFlow()

    private val _noise = MutableStateFlow(0f)

    /** How loud the room being watched is, nought to one. */
    val noise: StateFlow<Float> = _noise.asStateFlow()

    private val _peerStatus = MutableStateFlow<PhoneStatus?>(null)

    /** How the phone being watched is doing: its battery, its charger, its network. */
    val peerStatus: StateFlow<PhoneStatus?> = _peerStatus.asStateFlow()

    private val _video = MutableStateFlow(false)

    /** Whether the other phone's camera is running. */
    val video: StateFlow<Boolean> = _video.asStateFlow()

    private val _light = MutableStateFlow(LightState())

    /** What the other phone's screen is doing, so the controls show what is actually on. */
    val light: StateFlow<LightState> = _light.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _armed = MutableStateFlow(MonitorService.isRunning)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private val _quality = MutableStateFlow(CaptureQuality.Standard)

    /** The picture this call is carrying, or the one the next call will ask for. */
    val quality: StateFlow<CaptureQuality> = _quality.asStateFlow()

    private var session = CallSession()
    private var link: MediaLink? = null
    private var statusTicker: Job? = null
    private var watchdog: Job? = null
    private var redial: Job? = null
    private var dropped: Job? = null
    private var answering: Job? = null
    private var pathWatch: Job? = null
    private var presenceWatch: Job? = null

    /** What this phone asked for, so it can ask again. Cleared only when somebody says stop. */
    private var wanted: Pair<String, Boolean>? = null
    private var attempts = 0

    /** When the phone being watched was last heard from, by any message at all. */
    private var heardAt = 0L

    /**
     * Whether somebody is actually looking at the picture. An alarm is for the times nobody is:
     * a room that is being watched with the sound on needs no notification about being loud.
     */
    @Volatile
    var watching: Boolean = false
        set(value) {
            field = value
            // Looking at the room is as good as pressing the button: the alarm has done its job.
            if (value) {
                MonitorService.silenceAlarm(context)
            }
        }

    /**
     * Runs only on the phone being watched, and only while it is. Every change goes down the data
     * channel, so the other end hears about a charger being pulled out within a second of it
     * happening rather than whenever somebody next opens a screen.
     */
    private val status = StatusMonitor(context) { current ->
        link?.send(json.encodeToString(RoomReport(status = current)))
    }
    private var ice: IceServersDto? = null

    init {
        load()
        listen()
    }

    fun refresh() = load()

    /** The relay address or key changed: drop the old connection and use the new one. */
    fun reconnect() {
        stream.disconnect()
        onRelay = emptySet()
        ice = null
        stream.connect()
        load()
    }

    /**
     * Hands the pairing message to the phone that was just scanned. It cannot check the signature
     * yet, having no key for this phone; it trusts the proof inside instead.
     */
    suspend fun sendPairing(message: SignalMessage): Boolean =
        send(message.toDeviceId, message.kind, message.body) == SignalResult.Delivered

    fun listenTo(deviceId: String, video: Boolean = false) {
        // Whatever went wrong last time is not news about this attempt.
        _message.value = null
        _silent.value = false
        MonitorService.clearLostAlarm(context)

        // Said here rather than discovered when the offer arrives and cannot be checked. The call
        // would fail either way — nothing unconfirmed is listened to — but it would fail after
        // fifteen seconds of waiting and read as "no answer", which sends somebody to the wrong
        // room. This is a pairing step nobody has done, and says so.
        if (!isConfirmed(deviceId)) {
            explainUnconfirmed(nameOf(deviceId))
            return
        }

        // Asked for by a person, so this is the call to keep re-establishing until they say stop.
        wanted = deviceId to video
        attempts = 0
        stopRedialling()
        dispatch(CallEvent.Listen(deviceId, video))
    }

    /**
     * Whether the key this phone confirmed for that phone is still the key it is presenting.
     *
     * Both halves matter: never confirmed means nobody has checked the key, and confirmed but
     * changed means the phone reinstalled — or something is standing in for it.
     */
    private fun isConfirmed(deviceId: String): Boolean = household.keyOf(deviceId) != null

    /** Stopping on purpose is the one ending that is not news, and the one that stops the retrying. */
    fun hangUp() {
        wanted = null
        stopRedialling()
        waitForAnswer(null)
        _silent.value = false
        MonitorService.clearLostAlarm(context)
        dispatch(CallEvent.HangUp)
    }

    /**
     * Silences the sound here without touching what the other phone is doing. Watching a room
     * without listening to it is a thing people want; making the nursery phone stop sending is
     * not, because then nothing would notice the room at all.
     */
    fun toggleMute() {
        _muted.value = !_muted.value
        link?.setIncomingAudioMuted(_muted.value)
    }

    /** [seconds] of zero leaves the light on until it is turned off. */
    fun askForLight(mode: LightMode, brightness: Float, seconds: Int = 0) =
        dispatch(CallEvent.AskForLight(mode, brightness, seconds))

    fun showVideo(on: Boolean) = dispatch(CallEvent.AskForVideo(on))

    fun chooseQuality(value: CaptureQuality) = dispatch(CallEvent.ChooseQuality(value))

    fun dismissMessage() {
        _message.value = null
    }

    /**
     * Puts this phone on duty. Started from the screen on purpose — from Android 14 a camera or
     * microphone service cannot be started from the background, so the phone in the nursery is
     * armed by hand before the night rather than woken into filming from cold.
     */
    fun arm() {
        _armed.value = MonitorService.arm(context)
        if (!_armed.value) {
            _message.value = "This phone could not go on duty. Open the app and try again."
        }
    }

    fun disarm() {
        MonitorService.disarm(context)
        _armed.value = false
    }

    fun hasMicrophonePermission(): Boolean = granted(Manifest.permission.RECORD_AUDIO)

    fun hasCameraPermission(): Boolean = granted(Manifest.permission.CAMERA)

    fun canStartFromBackground(): Boolean = MonitorService.canStartFromBackground(context)

    fun canScheduleExactAlarm(): Boolean = MonitorService.canScheduleExactAlarm(context)

    fun runsUnrestricted(): Boolean = MonitorService.runsUnrestricted(context)

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun load() {
        // A no-op once the socket is already listening.
        lan.start()
        _others.value = household.peers()
        stream.watch(_others.value.map { it.id })

        // One watcher, however often this is called. It re-reads the paired phones too, so one
        // paired a moment ago turns up without a restart.
        if (presenceWatch == null) {
            presenceWatch = scope.launch {
                while (true) {
                    _others.value = household.peers()
                    stream.watch(_others.value.map { it.id })
                    updateOnline()
                    delay(PresenceInterval)
                }
            }
        }

        if (api.isConfigured && ice == null) {
            scope.launch {
                when (val result = api.iceServers()) {
                    is ApiResult.Ok -> ice = result.value
                    // Not fatal: two phones on one WiFi reach each other without it.
                    is ApiResult.Failure -> _message.value = result.message
                }
            }
        }
    }

    private fun updateOnline() {
        _online.value = _others.value
            .filter { lan.reachable(it.id) || it.id in onRelay }
            .map { it.id }
            .toSet()
    }

    private fun listen() {
        stream.connect()
        scope.launch {
            stream.events.collect { event ->
                when (event) {
                    is DeviceEvent.Signal -> onSignalFromServer(event.signal)

                    is DeviceEvent.Presence -> {
                        onRelay = if (event.online) onRelay + event.deviceId else onRelay - event.deviceId
                        updateOnline()

                        // The phone we are waiting for is back: no reason to sit out the backoff.
                        if (event.online && event.deviceId == wanted?.first && _retrying.value) {
                            Log.i(Tag, "The room is back; calling it now")
                            attempts = 0
                            scheduleRedial(now = true)
                        }
                    }
                }
            }
        }
    }

    /**
     * A signal the backend handed over, which is the one place this app has to assume the worst
     * about the server it is talking to. The hub says who sent it; the signature is what proves
     * it, and proves the offer inside has not been swapped for one the server can decrypt.
     *
     * Anything that fails is dropped rather than acted on, because the alternative is a call to
     * something that is not the phone in the nursery.
     */
    private suspend fun onSignalFromServer(signal: SignalMessage) {
        val peer = _others.value.firstOrNull { it.id == signal.fromDeviceId }
        val confirmed = household.keyOf(signal.fromDeviceId)

        when (val trust = signal.trust(confirmed)) {
            SignalTrust.Verified -> onSignal(signal)

            // The one message a stranger may send: it carries proof it read this phone's screen.
            SignalTrust.Unconfirmed if signal.kind == SignalKinds.Pair -> _pairings.emit(signal)

            SignalTrust.Unconfirmed -> {
                Log.w(Tag, "Dropped a ${signal.kind} from ${signal.fromDeviceId}: its key was never confirmed")
                explainUnconfirmed(peer?.name ?: "That phone")
            }

            SignalTrust.Stale -> {
                Log.w(Tag, "Dropped a ${signal.kind} from ${signal.fromDeviceId}: signed ${signal.sentAt}, too far from now")
                _message.value = "${peer?.name ?: "That phone"} and this one disagree about the " +
                    "time. Check the clock on both."
            }

            SignalTrust.WrongKey -> {
                // The loud one. Innocently the other phone reinstalled and has a new key it has
                // not been scanned with; otherwise something is relaying a key it cannot sign for.
                Log.e(
                    Tag,
                    "Dropped a ${signal.kind} from ${signal.fromDeviceId}: not signed by the confirmed key ($trust)"
                )
                _message.value = "${peer?.name ?: "That phone"} could not prove it is itself. " +
                    "Scan its code again before trusting it."
            }
        }
    }

    /** Said once per call rather than once per candidate, which would bury it. */
    private fun explainUnconfirmed(name: String) {
        val notice = "$name has not been confirmed on this phone. Scan its code to listen to it."
        if (_message.value != notice) {
            _message.value = notice
        }
    }

    private fun onSignal(signal: SignalMessage) {
        // Both paths arrive here, so this is the one place a message that has already been acted
        // on can be turned away. A signature is good for a minute; whatever relayed it can offer
        // it again inside that minute without being able to forge anything.
        if (!replay.accept(signal)) {
            Log.w(Tag, "Dropped a ${signal.kind} from ${signal.fromDeviceId}: already acted on")
            return
        }

        val from = signal.fromDeviceId
        when (signal.kind) {
            SignalKinds.Start -> {
                val request = runCatching { json.decodeFromString<CallRequest>(signal.body) }
                    .getOrDefault(CallRequest())
                dispatch(
                    CallEvent.Asked(
                        peerDeviceId = from,
                        allowed = mayBeHeardBy(from, request.video),
                        video = request.video,
                        quality = CaptureQuality.named(request.quality)
                    )
                )
            }

            SignalKinds.Offer -> dispatch(CallEvent.OfferReceived(from, signal.body))
            SignalKinds.Answer -> dispatch(CallEvent.AnswerReceived(from, signal.body))
            SignalKinds.Candidate -> dispatch(
                CallEvent.CandidateReceived(from, json.decodeFromString<IceCandidateDto>(signal.body))
            )

            SignalKinds.Quality -> {
                val request = runCatching { json.decodeFromString<QualityRequest>(signal.body) }
                    .getOrDefault(QualityRequest())
                dispatch(CallEvent.QualityAsked(from, CaptureQuality.named(request.quality)))
            }

            SignalKinds.Video -> {
                val request = runCatching { json.decodeFromString<VideoRequest>(signal.body) }
                    .getOrDefault(VideoRequest())
                dispatch(CallEvent.VideoAsked(from, request.on))
            }

            SignalKinds.Light -> {
                val request = runCatching { json.decodeFromString<LightRequest>(signal.body) }
                    .getOrDefault(LightRequest())

                if (request.reason.isNotBlank()) {
                    // The other end could not do it. Nothing to switch on here; somebody is
                    // holding this phone and can be told why.
                    _message.value = request.reason
                } else {
                    val mode = runCatching { LightMode.valueOf(request.mode) }.getOrDefault(LightMode.Off)
                    dispatch(CallEvent.LightAsked(from, mode, request.brightness, request.seconds))
                }
            }

            SignalKinds.Stop -> {
                signal.body.takeIf { it.isNotBlank() }?.let { _message.value = it }
                dispatch(CallEvent.Stopped(from))
            }

            // Pairing again with a phone that is already paired: still worth showing on screen.
            SignalKinds.Pair -> _pairings.tryEmit(signal)
        }
    }

    /**
     * Whether to open the microphone for the phone that asked: this phone may record at all, and
     * the phone asking was paired in person.
     */
    private fun mayBeHeardBy(deviceId: String, video: Boolean): Boolean {
        if (!hasMicrophonePermission()) {
            _message.value = "Another phone asked to listen, but this one has no microphone permission."
            return false
        }

        if (video && !hasCameraPermission()) {
            _message.value = "Another phone asked to watch, but this one has no camera permission."
            return false
        }

        val confirmed = isConfirmed(deviceId)
        if (!confirmed) {
            _message.value = "Refused a phone whose key this one has not confirmed."
        }

        return confirmed
    }

    private fun dispatch(event: CallEvent) {
        scope.launch {
            val was = session.state
            val (next, effects) = session.on(event)
            session = next
            _state.value = next.state
            _quality.value = next.quality
            _light.value = next.light
            _video.value = next.video

            // A complaint about a phone that would not answer has no business outliving the call
            // that answered: connecting is the answer to it.
            if (next.state is CallState.Live) {
                _message.value = null
            }
            effects.forEach { apply(it) }

            // After the effects, because closing the call clears the alarm and a call that ended
            // by failing is exactly the case worth being woken for: a killed app, a flat battery
            // or a phone carried out of range all arrive here, and none of them are somebody
            // deciding to stop.
            if (next.state is CallState.Failed && was is CallState.Live && was.role == CallRole.Listener) {
                reportRoomLost(was.peerDeviceId)
            }

            when (next.state) {
                // Back with nothing, and nobody asked for that: go round again.
                is CallState.Idle, is CallState.Failed -> {
                    waitForAnswer(null)
                    if (wanted != null && was !is CallState.Idle) {
                        scheduleRedial()
                    }
                }

                // Asked, or halfway through answering. Both are supposed to be brief, and
                // neither has anything of its own to say when they are not: a phone whose
                // socket the server has not yet noticed is dead takes the message and never
                // replies, and without this the call waits for it for ever.
                is CallState.Asking, is CallState.Negotiating -> waitForAnswer("No answer.")

                // It answered. Start counting from nothing again, so a night of one drop at
                // three in the morning does not begin the next attempt half an hour late.
                is CallState.Live -> {
                    waitForAnswer(null)
                    attempts = 0
                    _retrying.value = false
                    watchThePath()
                }
            }

            holdTheProcess()
        }
    }

    /**
     * A call keeps the service up for as long as it lasts, whether or not anybody armed this
     * phone. Without it the phone that answered a call is a background app the moment its screen
     * goes off, and Android takes the microphone straight back.
     */
    private fun holdTheProcess() {
        val busy = session.isBusy
        if (busy && !MonitorService.isRunning) {
            // May well fail: a call answered while this phone is locked and was never armed is
            // exactly the case Android refuses. Nothing to do about it here but carry on — the
            // call works while the screen is on, and the chip is what makes it work when it is not.
            MonitorService.arm(context)
        } else if (!busy && !_armed.value && MonitorService.isRunning) {
            MonitorService.disarm(context)
        }
    }

    private suspend fun apply(effect: CallEffect) {
        when (effect) {
            is CallEffect.Open -> {
                link = WebRtcMediaLink(
                    context = context,
                    iceServers = ice ?: IceServersDto(),
                    sendAudio = effect.role == CallRole.Speaker,
                    canFilm = effect.role == CallRole.Speaker && hasCameraPermission(),
                    sendVideo = effect.role == CallRole.Speaker && effect.video,
                    quality = effect.quality,
                    listener = Callbacks()
                ).also { it.setIncomingAudioMuted(_muted.value) }

                // The end doing the watching keeps an ear out for the other going quiet.
                if (effect.role == CallRole.Listener) {
                    heardAt = System.currentTimeMillis()
                    watchdog = scope.launch {
                        while (true) {
                            delay(SilenceCheck)
                            checkStillThere()
                        }
                    }
                }

                // The end that is being watched is the one with news about itself.
                if (effect.role == CallRole.Speaker) {
                    status.start()
                    statusTicker = scope.launch {
                        while (true) {
                            delay(StatusInterval)
                            link?.send(json.encodeToString(RoomReport(status = status.current)))
                        }
                    }
                }
            }

            CallEffect.CreateOffer -> link?.createOffer()
            is CallEffect.AcceptOffer -> link?.acceptOffer(effect.sdp)
            is CallEffect.AcceptAnswer -> link?.acceptAnswer(effect.sdp)
            is CallEffect.AddCandidate -> link?.addRemoteCandidate(effect.candidate)
            is CallEffect.SetQuality -> link?.changeQuality(effect.quality)
            is CallEffect.SetVideo -> link?.setVideoEnabled(effect.on)

            is CallEffect.ShowLight -> {
                val raised = MonitorService.light(context, effect.mode, effect.brightness, effect.seconds)
                if (!raised && effect.mode != LightMode.Off) {
                    reportUnlitRoom()
                }
            }

            is CallEffect.Send -> report(effect, send(effect.toDeviceId, effect.kind, effect.body))

            CallEffect.Close -> {
                pathWatch?.cancel()
                pathWatch = null
                _path.value = CallPath.Unknown
                dropped?.cancel()
                dropped = null
                statusTicker?.cancel()
                statusTicker = null
                watchdog?.cancel()
                watchdog = null
                status.stop()
                _muted.value = false
                link?.close()
                link = null
                _remoteVideo.value = null
                _peerStatus.value = null
                _noise.value = 0f
                _arming.value = 0f
                watch = NoiseWatch(alarmSettings.current)
            }
        }
    }

    /**
     * Signs a message once and hands it to whichever path works: the WiFi first, then the relay.
     * An offer names the fingerprint the media will be encrypted to, so the signature is what
     * stops anything in the middle, the relay included, from swapping in its own.
     */
    private suspend fun send(toDeviceId: String, kind: String, body: String): SignalResult {
        // Off the main thread, because signing goes to the keystore.
        val signed = withContext(Dispatchers.Default) {
            SignalMessage(toDeviceId = toDeviceId, kind = kind, body = body)
                .signed(fromDeviceId = household.deviceId)
        }

        if (signed == null) {
            Log.e(Tag, "Could not sign a $kind; this phone cannot prove who it is")
            return SignalResult.Refused("This phone could not sign for itself.")
        }

        return if (lan.send(signed)) SignalResult.Delivered else stream.sendSignal(signed)
    }

    /**
     * What to say about a signal that did not arrive. Only the asking is worth reporting when the
     * other phone is simply not there — the messages after it follow a phone that has already
     * answered, and one lost candidate is not news anybody can act on. A refusal or this phone
     * being offline is worth saying whenever it happens, because neither will fix itself.
     */
    private fun report(effect: CallEffect.Send, result: SignalResult) {
        when (result) {
            SignalResult.Delivered -> Unit

            SignalResult.NotListening -> if (effect.kind == SignalKinds.Start) {
                _message.value =
                    "${nameOf(effect.toDeviceId)} is not connected. Open the app on it and leave it on."
                dispatch(CallEvent.Stopped(effect.toDeviceId))
            }

            // Without a relay the WiFi is the only path, so that is the thing to say.
            SignalResult.Offline -> {
                val reason = if (api.isConfigured) {
                    "${nameOf(effect.toDeviceId)} is not on this WiFi, and this phone cannot " +
                        "reach the server."
                } else {
                    "${nameOf(effect.toDeviceId)} is not on this WiFi. Both phones need the app " +
                        "open on the same network."
                }

                _message.value = reason
                dispatch(CallEvent.Failure(reason))
            }

            is SignalResult.Refused -> {
                _message.value = result.reason
                dispatch(CallEvent.Failure(result.reason))
            }
        }
    }

    /**
     * Tells the phone that asked why the room stayed dark. Android 14 hands out the permission to
     * show over a lock screen only to apps it takes for alarms or calls, and somebody staring at a
     * black picture has no other way to find that out.
     */
    private fun reportUnlitRoom() {
        val peer = session.peerDeviceId ?: return
        scope.launch {
            send(
                toDeviceId = peer,
                kind = SignalKinds.Light,
                body = json.encodeToString(
                    LightRequest(
                        mode = LightMode.Off.name,
                        reason = "That phone is not allowed to show a screen while it is " +
                            "locked. On it: allow \"display over other apps\"."
                    )
                )
            )
        }
    }

    /**
     * Asks again, further apart each time. A redial rather than an ICE restart: every way a call
     * dies — a network changing under it, a phone rebooting, a router blinking — is the same
     * problem from here, and starting over is the one answer that covers all of them.
     */
    private fun scheduleRedial(now: Boolean = false) {
        val (peer, video) = wanted ?: return
        redial?.cancel()
        _retrying.value = true
        redial = scope.launch {
            val wait = if (now) 0L else Backoff[attempts.coerceAtMost(Backoff.lastIndex)]
            attempts++
            Log.i(Tag, "Trying the room again in ${wait / 1000}s")
            delay(wait)
            dispatch(CallEvent.Listen(peer, video))
        }
    }

    fun setStartWithVideoOff(value: Boolean) {
        callPreferences.startWithVideoOff = value
        _startWithVideoOff.value = value
    }

    fun setKeepScreenOn(value: Boolean) {
        callPreferences.keepScreenOn = value
        _keepScreenOn.value = value
    }

    /**
     * Asks now and again rather than once: ICE can move a live call to a better pair, and a call
     * that started on the relay may not stay there.
     */
    private fun watchThePath() {
        pathWatch?.cancel()
        pathWatch = scope.launch {
            while (true) {
                link?.probePath()
                delay(PathInterval)
            }
        }
    }

    /** Gives the other end a while to reply, and calls it a failure when it does not. */
    private fun waitForAnswer(reason: String?) {
        answering?.cancel()
        answering = reason?.let {
            scope.launch {
                delay(AnswerLimit)
                Log.w(Tag, "No answer within ${AnswerLimit / 1000}s")
                dispatch(CallEvent.Failure(it))
            }
        }
    }

    private fun stopRedialling() {
        redial?.cancel()
        redial = null
        _retrying.value = false
    }

    /** The room has gone, however it went. Raised once, whether or not anybody is watching. */
    private fun reportRoomLost(peerDeviceId: String) {
        // Once per disappearance, however many times the redial fails on the way back.
        if (_silent.value) {
            return
        }

        _silent.value = true
        Log.w(Tag, "Lost the room: the call ended without being stopped")
        if (_alarm.value.warnOffline) {
            MonitorService.lostAlarm(context, nameOf(peerDeviceId))
        }
    }

    /**
     * Whether the room is still answering. A phone that has run out of battery, been switched off
     * or lost its network stops sending and looks from here exactly like a quiet room — which is
     * the one failure a baby monitor must not have.
     */
    private fun checkStillThere() {
        if (!session.isBusy || _silent.value) {
            return
        }

        if (System.currentTimeMillis() - heardAt < SilenceLimit) {
            return
        }

        Log.w(Tag, "Nothing from the room for ${SilenceLimit / 1000} seconds")

        // Down the same path as any other failure, so silence gets the alarm and the redialling
        // rather than a live connection nobody is using.
        dispatch(CallEvent.Failure("The room stopped answering."))
    }

    /**
     * The alarm is raised here rather than on the phone in the room, because everything that
     * decides it belongs to this end: the threshold somebody chose, whether the sound is off, and
     * whether anybody is looking.
     */
    private fun considerAlarm(level: Float) {
        val (next, fire) = watch.on(level, System.currentTimeMillis())
        watch = next
        _arming.value = next.progress
        if (!fire) {
            return
        }

        // Already looking at the room with the sound on: the noise is its own notification.
        if (watching && !_muted.value) {
            Log.i(Tag, "Noise alarm held back: somebody is watching with the sound on")
            return
        }

        Log.i(Tag, "Noise alarm at level $level")
        MonitorService.alarm(context, session.peerDeviceId?.let(::nameOf) ?: "The room")
    }

    fun setAlarm(settings: NoiseAlarmSettings) {
        alarmSettings.current = settings
        _alarm.value = settings
        watch = watch.copy(settings = settings)
    }

    private fun nameOf(deviceId: String) =
        _others.value.firstOrNull { it.id == deviceId }?.name ?: "That phone"

    private inner class Callbacks : MediaLinkListener {
        override fun onLocalOffer(sdp: String) = dispatch(CallEvent.LocalOffer(sdp))
        override fun onLocalAnswer(sdp: String) = dispatch(CallEvent.LocalAnswer(sdp))
        override fun onLocalCandidate(candidate: IceCandidateDto) = dispatch(CallEvent.LocalCandidate(candidate))
        override fun onConnected() {
            dropped?.cancel()
            dropped = null
            dispatch(CallEvent.Connected)
        }

        /**
         * Given a little while to come back on its own — a phone changing network drops and
         * recovers within seconds — and then treated as gone. WebRTC takes far longer than that
         * to call it failed, and both ends sitting in a call that no longer exists is how a
         * redial gets refused as busy.
         */
        override fun onDisconnected() {
            if (dropped != null) {
                return
            }

            dropped = scope.launch {
                delay(DropGrace)
                dropped = null
                dispatch(CallEvent.Failure("The connection dropped."))
            }
        }
        override fun onFailed(reason: String) = dispatch(CallEvent.Failure(reason))

        override fun onRemoteVideo(track: VideoTrack) {
            _remoteVideo.value = track
        }

        override fun onMessage(text: String) {
            // Anything at all counts as a sign of life: noise arrives five times a second and the
            // state of the phone every thirty, so silence really is silence.
            heardAt = System.currentTimeMillis()
            if (_silent.value) {
                _silent.value = false
                MonitorService.clearLostAlarm(context)
            }

            val report = runCatching { json.decodeFromString<RoomReport>(text) }.getOrNull() ?: return
            report.status?.let { _peerStatus.value = it }
            report.noise?.let {
                _noise.value = it
                considerAlarm(it)
            }
        }

        override fun onPath(path: CallPath) {
            _path.value = path
        }

        override fun onAudioLevel(level: Float) {
            scope.launch { link?.send(json.encodeToString(RoomReport(noise = level))) }
        }

        /**
         * Say it again now that anybody can hear it. The first reading is produced the moment the
         * connection is built, long before the channel carrying it is open, and a phone on a
         * charger in a room with steady WiFi may not change anything for hours.
         */
        override fun onChannelOpen() {
            link?.send(json.encodeToString(RoomReport(status = status.current)))
        }
    }

    companion object {
        private const val Tag = "CallCenter"

        /**
         * Said again on a slow beat, whether or not anything changed. A battery that has not moved
         * produces no news, so without this a single message going astray leaves the other phone
         * with nothing for hours — and a beat that keeps coming is also how that phone will one
         * day notice this one has gone quiet.
         */
        private const val StatusInterval = 30_000L

        /** Noise arrives five times a second, so this much quiet is not a hiccup. */
        private const val SilenceLimit = 15_000L
        private const val SilenceCheck = 2_000L

        /** Quick at first, because most drops are a phone changing network. */
        private val Backoff = longArrayOf(2_000, 5_000, 15_000, 30_000)

        /** Long enough for a phone hopping between networks, short enough to be worth waiting. */
        private const val DropGrace = 8_000L

        /** Asking and negotiating both take seconds; this much means nobody is there. */
        private const val AnswerLimit = 15_000L
        private const val PathInterval = 5_000L

        /** How often to look around the WiFi for the paired phones. */
        private const val PresenceInterval = 3_000L

        @Volatile
        private var instance: CallCenter? = null

        /** One per process, against the application context — never against an activity. */
        fun of(context: Context): CallCenter =
            instance ?: synchronized(this) {
                instance ?: CallCenter(context.applicationContext).also { instance = it }
            }

    }
}
