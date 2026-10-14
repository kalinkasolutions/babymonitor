package ch.kalinka.babymonitor.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.kalinka.babymonitor.device.Phone
import ch.kalinka.babymonitor.device.Reachability
import ch.kalinka.babymonitor.media.CallRole
import ch.kalinka.babymonitor.media.CallState
import ch.kalinka.babymonitor.media.MonitorViewModel

/**
 * Setting the monitor up: which phone stays in the room, and which other phone to open.
 *
 * Watching has a screen of its own — this one is everything you do before there is anything to
 * watch, and it hands over as soon as there is.
 */
@Composable
fun MonitorScreen(
    onGoToDevices: () -> Unit,
    onCheckRoom: () -> Unit,
    viewModel: MonitorViewModel = viewModel()
) {
    val others by viewModel.others.collectAsState()
    val online by viewModel.online.collectAsState()
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val remoteVideo by viewModel.remoteVideo.collectAsState()
    val armed by viewModel.armed.collectAsState()
    val quality by viewModel.quality.collectAsState()
    val light by viewModel.light.collectAsState()
    val muted by viewModel.muted.collectAsState()
    val peerStatus by viewModel.peerStatus.collectAsState()
    val noise by viewModel.noise.collectAsState()
    val alarm by viewModel.alarm.collectAsState()
    val startWithVideoOff by viewModel.startWithVideoOff.collectAsState()
    val arming by viewModel.arming.collectAsState()
    val silent by viewModel.silent.collectAsState()
    val retrying by viewModel.retrying.collectAsState()
    val path by viewModel.path.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val availability by viewModel.availability.collectAsState()
    val offline by viewModel.offline.collectAsState()

    // Remembered across a drop, so a call being re-established keeps its screen instead of
    // bouncing back to the list and losing what was on it.
    var watchingPeer by remember { mutableStateOf("") }
    LaunchedEffect(state) {
        (state as? CallState.Live)?.takeIf { it.role == CallRole.Listener }?.let {
            watchingPeer = it.peerDeviceId
        }
    }
    val video by viewModel.video.collectAsState()
    val context = LocalContext.current

    // Asked for on arrival rather than when somebody calls: the answer has to be in hand before
    // the other phone asks, or the first request of the night is refused while a dialog opens.
    // Both, because either phone may end up being the one in the nursery.
    val askForCapture = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val missing = listOfNotNull(
            Manifest.permission.RECORD_AUDIO.takeIf { !viewModel.hasMicrophonePermission() },
            Manifest.permission.CAMERA.takeIf { !viewModel.hasCameraPermission() },
            Manifest.permission.POST_NOTIFICATIONS.takeIf { !viewModel.hasNotificationPermission() }
        )
        if (missing.isNotEmpty()) {
            askForCapture.launch(missing.toTypedArray())
        }
    }

    // Everything on screen while a call is up is about the other phone, so it gets the screen.
    val live = (state as? CallState.Live)?.takeIf { it.role == CallRole.Listener }?.peerDeviceId
    val peer = live ?: watchingPeer.takeIf { retrying }
    if (peer != null) {
        WatchScreen(
            peerName = others.firstOrNull { it.id == peer }?.name ?: "The other phone",
            videoTrack = remoteVideo,
            status = peerStatus,
            noise = noise,
            alarmAt = alarm.threshold,
            arming = arming,
            silent = silent,
            retrying = retrying,
            offline = offline,
            path = path,
            muted = muted,
            video = video,
            quality = quality,
            light = light,
            keepScreenOn = keepScreenOn,
            onKeepScreenOn = viewModel::setKeepScreenOn,
            onMute = viewModel::toggleMute,
            onVideo = viewModel::showVideo,
            onQuality = viewModel::chooseQuality,
            onLight = viewModel::askForLight,
            onStop = viewModel::hangUp,
            onWatching = viewModel::watching
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Only when something is happening. Idle needs no announcement; it needs instructions. A
        // failure is said once: as the message if there is one, which usually carries more.
        val showState = state !is CallState.Idle && !(state is CallState.Failed && message != null)
        if (showState) {
            CallStateLine(state, others)
            Spacer(Modifier.height(8.dp))
        }

        // The way out of what is happening sits with it, named for what it ends.
        stopLabel(state, others)?.let { label ->
            Button(onClick = viewModel::hangUp) { Text(label) }
            Spacer(Modifier.height(16.dp))
        }

        message?.let {
            ErrorText(it)
            TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
            Spacer(Modifier.height(8.dp))
        }

        if (!viewModel.hasServer()) {
            WifiOnlyNotice()
            Spacer(Modifier.height(24.dp))
        }

        Step(
            title = "Leave this phone in the room",
            body = if (armed) {
                "It is listening and can be watched, even locked. Leave it plugged in."
            } else {
                "It will listen and film while locked. Turn this on first. A locked phone " +
                    "cannot start its own camera."
            },
            action = if (armed) "Stop monitoring" else "Use this phone as the monitor",
            done = armed,
            onAction = { if (armed) viewModel.disarm() else viewModel.arm() }
        )

        Step(
            title = "Check the picture",
            body = "See what this phone films and hears, to find a good spot for it.",
            action = "Show the camera",
            done = false,
            onAction = onCheckRoom
        )

        // Checked again whenever the screen comes back, so granting it and returning clears this
        // rather than leaving a warning about something already fixed.
        var canOverlay by remember { mutableStateOf(viewModel.canStartFromBackground()) }
        var canAlarm by remember { mutableStateOf(viewModel.canScheduleExactAlarm()) }
        var unrestricted by remember { mutableStateOf(viewModel.runsUnrestricted()) }
        LifecycleResumeEffect(Unit) {
            canOverlay = viewModel.canStartFromBackground()
            canAlarm = viewModel.canScheduleExactAlarm()
            unrestricted = viewModel.runsUnrestricted()
            onPauseOrDispose { }
        }

        if (armed && !unrestricted) {
            Step(
                title = "Let it run all night",
                body = "Android puts sleeping apps to sleep too. Without this it may go quiet " +
                    "hours from now, with nothing to show that it did.",
                action = "Allow it to keep running",
                done = false,
                onAction = { context.askToRunUnrestricted() }
            )
        }

        // Only once this phone is the one in the room, because until then it is somebody else's
        // problem, and a warning about a thing you have not chosen to do yet is noise.
        if (armed && !canOverlay) {
            Step(
                title = "Let it light the room",
                body = "A dark room films black. To light it with its own screen while locked, " +
                    "Android needs this one permission.",
                action = "Allow display over other apps",
                done = false,
                onAction = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                },
                footnote = "Exact alarms: ${onOrOff(canAlarm)} · Display over other apps: off"
            )
        }

        // Picking a phone to watch only makes sense while this one is doing nothing else.
        if (state !is CallState.Idle && state !is CallState.Failed) {
            return@Column
        }

        Text(
            text = "Watch another phone",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))

        if (others.isEmpty()) {
            Text(
                text = "No other phone yet. Connect one first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onGoToDevices) { Text("Go to Devices") }
            return@Column
        }

        Text(
            text = "Listen for sound, or watch with the camera.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (device in others) {
                ListenRow(
                    device = device,
                    reachability = Reachability.of(device.id in online, availability[device.id]),
                    onListen = { viewModel.listenTo(device.id, video = !startWithVideoOff) },
                    onWatch = { viewModel.listenTo(device.id, video = true) }
                )
            }
        }
    }
}

/** What the button that ends the call says, or null when there is no call to end. */
private fun stopLabel(state: CallState, others: List<Phone>): String? {
    fun nameOf(deviceId: String) = others.firstOrNull { it.id == deviceId }?.name ?: "the other phone"

    return when (state) {
        is CallState.Asking -> "Cancel"
        is CallState.Negotiating -> if (state.role == CallRole.Speaker) "Stop sending to ${nameOf(state.peerDeviceId)}" else "Cancel"
        is CallState.Live -> if (state.role == CallRole.Speaker) "Stop sending to ${nameOf(state.peerDeviceId)}" else "Stop watching"
        else -> null
    }
}

/**
 * One instruction: what it is, what happens if you do it, and the thing to press — in that order,
 * because a button you read before its explanation is a button you press to find out.
 *
 * A step that is done says so in the colour the rest of the app uses for a thing that has been
 * confirmed, and its button becomes the way back out of it.
 */
@Composable
private fun Step(
    title: String,
    body: String,
    action: String,
    done: Boolean,
    onAction: () -> Unit,
    footnote: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onAction,
            colors = if (done) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            } else {
                ButtonDefaults.buttonColors()
            }
        ) { Text(action) }

        footnote?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ListenRow(
    device: Phone,
    reachability: Reachability,
    onListen: () -> Unit,
    onWatch: () -> Unit
) {
    val online = reachability.canBeWatched
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)

                // Being in the list means it exists; this means it can answer.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (online) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = reachability.line(device.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = onWatch, enabled = online) { Text("Watch") }
            Button(onClick = onListen, enabled = online) { Text("Listen") }
        }
    }
}

/** What running without a server costs, on the screen you come back to. */
@Composable
private fun WifiOnlyNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("No server", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text("•  Both phones must be on the same WiFi.", style = MaterialTheme.typography.bodyMedium)
            Text("•  You cannot listen from outside the house.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text("Add a server in Settings to change that.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The system's own dialog, which is one tap. Some builds do not carry it, so the settings list
 * is the fallback — being sent to the right screen still beats a button that does nothing.
 */
private fun Context.askToRunUnrestricted() {
    val ask = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.fromParts("package", packageName, null)
    )
    try {
        startActivity(ask)
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun onOrOff(allowed: Boolean) = if (allowed) "on" else "off"

@Composable
private fun CallStateLine(state: CallState, others: List<Phone>) {
    fun nameOf(deviceId: String) = others.firstOrNull { it.id == deviceId }?.name ?: "the other phone"

    val line = when (state) {
        CallState.Idle -> "Nothing running."
        is CallState.Asking -> "Asking ${nameOf(state.peerDeviceId)} to send…"
        is CallState.Negotiating -> "Connecting to ${nameOf(state.peerDeviceId)}…"
        is CallState.Live -> when (state.role) {
            CallRole.Listener -> "Listening to ${nameOf(state.peerDeviceId)}."
            CallRole.Speaker -> "Sending to ${nameOf(state.peerDeviceId)}."
        }

        is CallState.Failed -> state.reason
    }

    Text(
        text = line,
        style = MaterialTheme.typography.bodyLarge,
        color = if (state is CallState.Failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        textAlign = TextAlign.Center
    )
}
