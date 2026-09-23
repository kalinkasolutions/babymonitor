package ch.kalinka.babymonitor.device

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.kalinka.babymonitor.net.ApiClient
import ch.kalinka.babymonitor.net.DeviceEvent
import ch.kalinka.babymonitor.net.DeviceStream
import ch.kalinka.babymonitor.net.ApiResult
import ch.kalinka.babymonitor.media.CallCenter
import ch.kalinka.babymonitor.net.PairRequest
import ch.kalinka.babymonitor.net.PairingCodeDto
import ch.kalinka.babymonitor.net.SignalKinds
import ch.kalinka.babymonitor.net.SignalMessage
import kotlinx.serialization.json.Json
import ch.kalinka.babymonitor.net.PairingCompletedDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicBoolean

class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val api = ApiClient(application)
    private val verifiedKeys = VerifiedKeys(application)
    private val local = LocalSession(application)
    private val stream = DeviceStream(api)

    private val _code = MutableStateFlow<PairingCodeDto?>(null)
    val code: StateFlow<PairingCodeDto?> = _code.asStateFlow()

    private val _outcome = MutableStateFlow<ScanOutcome>(ScanOutcome.None)
    val outcome: StateFlow<ScanOutcome> = _outcome.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    /** Set from the camera's analysis thread, so it has to be atomic rather than a plain flag. */
    private val claiming = AtomicBoolean(false)

    /** Guards the code request itself, which the countdown can ask for while one is in flight. */
    private val issuing = AtomicBoolean(false)

    /**
     * The secrets behind the QR codes this phone has shown, newest first — the first one belongs
     * to [_code]. Kept here and never uploaded: they are what lets this phone tell a scanner that
     * was really in the room from one the backend invented.
     *
     * The last few are kept rather than only the current one, so a proof that was already in
     * flight when the code rolled over can still be checked. A proof matching any of them was
     * read off this screen, which is the only thing being asked.
     */
    private val shownSecrets = ArrayDeque<String>()

    private val _secondsLeft = MutableStateFlow(0L)

    /** How long the displayed code is still good for, so it cannot lapse without saying so. */
    val secondsLeft: StateFlow<Long> = _secondsLeft.asStateFlow()

    /** With no account there is no code to type, nobody to invite, and no code to renew. */
    val withoutAccount: Boolean = local.enabled

    private val calls = CallCenter.of(application)

    init {
        newCode()
        if (local.enabled) {
            listenForLocalPairing()
        } else {
            listenForCompletion()
            countDown()
        }
    }

    /**
     * The other half of a scan with no server behind it. The phone that scanned says who it is
     * across the WiFi, and proves it by signing its own identity with the secret that was only
     * ever on this screen. Without that proof this is just a stranger claiming to be a baby
     * monitor, which is why it is the only unverified message the app accepts.
     */
    /**
     * Hands the other phone this one's key, with the proof that its screen was just read.
     *
     * Kept trying for a while, because mDNS has not necessarily noticed the other phone yet: the
     * QR was read in a second and a service announcement can take several. Failing silently here
     * would leave two phones each believing something different about whether they are paired.
     */
    private suspend fun tellThemWhoScanned(payload: PairingPayload) {
        val message = SignalMessage(
            toDeviceId = payload.deviceId,
            kind = SignalKinds.Pair,
            body = json.encodeToString(
                PairRequest(
                    deviceId = local.deviceId,
                    name = local.name,
                    publicKey = DeviceIdentity.publicKey(),
                    proof = PairingProof.sign(
                        secret = payload.secret,
                        deviceId = local.deviceId,
                        publicKey = DeviceIdentity.publicKey()
                    )
                )
            )
        )

        val name = payload.name.ifBlank { "The other phone" }
        Log.i(Tag, "Scanned ${payload.deviceId}; telling it who scanned, over the network")
        repeat(PairingAttempts) {
            if (calls.sendLocally(message)) {
                Log.i(Tag, "Paired with ${payload.deviceId} and confirmed its key")
                local.remember(payload.deviceId, name, payload.publicKey)
                calls.refresh()
                _outcome.value = ScanOutcome.Confirmed(name)
                return
            }

            delay(PairingRetry)
        }

        // Nothing was paired. Saying so is the whole point: a phone listed here that has never
        // heard of this one is worse than no phone at all, because it looks like it would answer.
        Log.w(Tag, "Gave up reaching ${payload.deviceId} after $PairingAttempts tries; nothing was paired")
        verifiedKeys.forget(payload.deviceId)
        _outcome.value = ScanOutcome.Failed(
            "$name could not be reached on this network. Both phones need the app open on the " +
                "same WiFi. Nothing was paired."
        )
    }

    private fun listenForLocalPairing() {
        viewModelScope.launch {
            calls.pairings.collect { signal ->
                val request = runCatching { json.decodeFromString<PairRequest>(signal.body) }
                    .getOrNull() ?: return@collect

                val proved = shownSecrets.any {
                    PairingProof.verify(it, request.deviceId, request.publicKey, request.proof)
                }

                if (!proved) {
                    // Innocently a code that rolled over mid-scan; it is also what somebody
                    // relaying a key they never read off this screen would look like.
                    Log.w(Tag, "${request.deviceId} claimed to have scanned this phone but could not prove it")
                    _outcome.value = ScanOutcome.ProofFailed(request.name.ifBlank { "That phone" })
                    return@collect
                }

                Log.i(Tag, "${request.deviceId} proved it read this screen; pinning its key")
                local.remember(request.deviceId, request.name.ifBlank { "The other phone" }, request.publicKey)
                verifiedKeys.pin(request.deviceId, request.publicKey)
                calls.refresh()
                _outcome.value = ScanOutcome.ConfirmedByScanner(
                    request.name.ifBlank { "The other phone" }
                )
            }
        }
    }

    /**
     * Codes last five minutes. Walking between two phones can easily take longer than that, and a
     * code that has quietly lapsed looks exactly like one that was typed wrong — so the screen
     * counts down and asks for a fresh one before it runs out.
     */
    private fun countDown() {
        viewModelScope.launch {
            while (true) {
                val expiry = _code.value?.expiresAt?.let(::parseExpiry)
                _secondsLeft.value = expiry
                    ?.let { Duration.between(Instant.now(), it).seconds.coerceAtLeast(0) }
                    ?: 0

                if (expiry != null && _secondsLeft.value <= 0) {
                    newCode()
                }

                delay(1000)
            }
        }
    }

    private fun parseExpiry(value: String): Instant? =
        try {
            // The backend sends UTC without an offset, which Instant will not parse on its own.
            Instant.parse(if (value.endsWith("Z")) value else value + "Z")
        } catch (e: DateTimeParseException) {
            null
        }

    /**
     * The phone showing a code has no other way to learn that it was scanned, so the server says
     * so. Without this it sits on the pairing screen while the other phone has already moved on.
     */
    private fun listenForCompletion() {
        stream.connect()
        viewModelScope.launch {
            stream.events.collect { event ->
                if (event is DeviceEvent.PairingCompleted) {
                    onScannedByOther(event.completed)
                }
            }
        }
    }

    /**
     * The other half of a scan. Somebody claimed a code; if it was the one this phone is showing
     * and they can prove they saw the screen, their key is genuine and gets pinned here too —
     * which is what makes one scan enough for both phones.
     *
     * What cannot be proved is never quietly treated as if it had been: the verdict this phone
     * reaches is the one the screen shows.
     */
    private fun onScannedByOther(completed: PairingCompletedDto) {
        if (completed.showingDeviceId != api.deviceId) {
            // Another phone on this account was the one showing a code, or this is this phone's
            // own claim coming back around.
            return
        }

        val scanner = completed.claimingDevice
        val outcome = when {
            // A typed code carries no secret, so there is nothing here to check and nothing to
            // pin — the link is real, the key is still only the server's word.
            completed.proof.isEmpty() -> ScanOutcome.LinkedOnly(scanner.name)

            shownSecrets.any { PairingProof.verify(it, scanner.id, scanner.publicKey, completed.proof) } -> {
                verifiedKeys.pin(scanner.id, scanner.publicKey)
                ScanOutcome.ConfirmedByScanner(scanner.name)
            }

            else -> ScanOutcome.ProofFailed(scanner.name)
        }

        Log.i(Tag, "Device ${scanner.id} claimed this phone's code: ${outcome::class.simpleName}")

        // A verdict already on screen came from this phone's own scan and is the one the person is
        // reading; pinning has happened either way, which is the part that matters.
        if (_outcome.value is ScanOutcome.None) {
            _outcome.value = outcome
        }
    }

    override fun onCleared() {
        stream.disconnect()
        super.onCleared()
    }

    /** Asks for a fresh code. They expire after a few minutes, so this is also the retry. */
    fun newCode() {
        // With no account there is nothing to ask for: this phone already has everything a scan
        // needs, and a code exists only to link two accounts that do not know each other.
        if (local.enabled) {
            rememberSecret()
            _code.value = PairingCodeDto(
                deviceId = local.deviceId,
                publicKey = DeviceIdentity.publicKey(),
                code = "",
                expiresAt = ""
            )
            return
        }

        if (!issuing.compareAndSet(false, true)) {
            return
        }

        viewModelScope.launch {
            _busy.value = true
            try {
                when (val result = api.pairingCode()) {
                    is ApiResult.Ok -> {
                        rememberSecret()
                        _code.value = result.value
                        _error.value = null
                    }

                    // The stale code goes with it. Nothing can be scanned off this screen until
                    // there is a new one, and leaving an expired one in place had the countdown
                    // asking again every second for as long as the server was unreachable.
                    is ApiResult.Failure -> {
                        Log.w(Tag, "Could not get a pairing code: ${result.message}")
                        _code.value = null
                        _error.value = result.message
                    }
                }
            } finally {
                _busy.value = false
                issuing.set(false)
            }
        }
    }

    /** A new secret with every code, kept alongside the few before it. */
    private fun rememberSecret() {
        shownSecrets.addFirst(PairingProof.newSecret())
        while (shownSecrets.size > RememberedSecrets) {
            shownSecrets.removeLast()
        }
    }

    /** What goes in the QR: the code to link with, and the key only a QR is big enough to carry. */
    val qrPayload: String?
        get() {
            val code = _code.value ?: return null
            val secret = shownSecrets.firstOrNull() ?: return null
            return PairingPayload(
                deviceId = code.deviceId,
                publicKey = code.publicKey,
                code = code.code,
                name = if (local.enabled) local.name else "",
                secret = secret
            ).encode()
        }

    fun onScanned(text: String) {
        val payload = PairingPayload.decode(text)
        if (payload == null) {
            // The camera reads whatever is in front of it, so this is routine rather than alarming.
            Log.i(Tag, "Read a QR code that is not a Babymonitor pairing code")
            _outcome.value = ScanOutcome.NotAPairingCode
            return
        }

        // With no accounts, a scan is the whole of pairing: the key was read off the screen, so
        // it is confirmed, and there is no link to make because there are no accounts to link.
        if (local.enabled) {
            // The key is confirmed the moment it is read off the screen — that is what a scan is
            // for. Being paired is a different claim, and a mutual one: it waits until the other
            // phone has actually been told, or this list fills up with phones that have never
            // heard of it.
            verifiedKeys.pin(payload.deviceId, payload.publicKey)
            viewModelScope.launch { tellThemWhoScanned(payload) }
            return
        }

        // The scanner trusts what it read off the screen directly, and proves it was there so
        // the other phone can trust it back — both directions from the one scan.
        val proof = PairingProof.sign(
            secret = payload.secret,
            deviceId = api.deviceId.orEmpty(),
            publicKey = DeviceIdentity.publicKey()
        )
        claim(payload.code, scannedKey = payload.publicKey, proof = proof)
    }

    /** The typed path. No key travelled with the code, so this links without confirming anything. */
    fun onCodeTyped(code: String) = claim(code.trim(), scannedKey = null, proof = "")

    private fun claim(code: String, scannedKey: String?, proof: String) {
        if (_outcome.value !is ScanOutcome.None || !claiming.compareAndSet(false, true)) {
            // The analyzer keeps delivering frames while a claim is in flight or a verdict is up.
            return
        }

        viewModelScope.launch {
            _busy.value = true
            try {
                _outcome.value = when (val result = api.claimPairing(code, proof)) {
                    is ApiResult.Failure -> {
                        Log.w(Tag, "Claiming a pairing code failed: ${result.message}")
                        ScanOutcome.Failed(result.message)
                    }

                    is ApiResult.Ok ->
                        verdictFor(result.value.name, result.value.id, result.value.publicKey, scannedKey)
                }
            } finally {
                _busy.value = false
                claiming.set(false)
            }
        }
    }

    private fun verdictFor(
        name: String,
        deviceId: String,
        reportedKey: String,
        scannedKey: String?
    ): ScanOutcome = when {
        scannedKey == null -> {
            Log.i(Tag, "Linked to device $deviceId by a typed code; its key is unconfirmed")
            ScanOutcome.LinkedOnly(name)
        }

        scannedKey == reportedKey -> {
            verifiedKeys.pin(deviceId, scannedKey)
            Log.i(Tag, "Confirmed device $deviceId: the key on screen is the key the server holds")
            ScanOutcome.Confirmed(name)
        }

        // The loudest line this app can write. Innocently a reinstall mid-scan; otherwise it is
        // the backend handing over a key that is not the one on the other phone's screen.
        else -> {
            Log.e(Tag, "Device $deviceId: the scanned key is NOT the key the server reported")
            ScanOutcome.Mismatch(name)
        }
    }

    fun dismissOutcome() {
        _outcome.value = ScanOutcome.None
    }

    private companion object {
        const val Tag = "PairingViewModel"

        /** Enough to cover a code rolling over while somebody is walking between two phones. */
        const val RememberedSecrets = 3

        /** Long enough for mDNS to notice the other phone, which is seconds rather than instant. */
        const val PairingAttempts = 30
        const val PairingRetry = 500L
    }
}
