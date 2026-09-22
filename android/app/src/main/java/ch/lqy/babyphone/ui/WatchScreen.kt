package ch.lqy.babyphone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import ch.lqy.babyphone.media.CallPath
import ch.lqy.babyphone.media.CaptureQuality
import ch.lqy.babyphone.media.GlanceSeconds
import ch.lqy.babyphone.media.LightState
import ch.lqy.babyphone.media.PhoneStatus
import ch.lqy.babyphone.media.WebRtcEgl
import ch.lqy.babyphone.net.LightMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * The room, while you are watching it. Its own screen because it is its own task: everything here
 * is about the other phone, and none of the setting up that the monitor screen is for.
 *
 * What can be pressed sits on the picture, where a video player keeps its controls — sound in one
 * corner, the picture's settings in the other, and the light underneath because a slider wants
 * room to be dragged.
 */
@Composable
fun WatchScreen(
    peerName: String,
    videoTrack: VideoTrack?,
    status: PhoneStatus?,
    noise: Float,
    alarmAt: Float,
    arming: Float,
    silent: Boolean,
    retrying: Boolean,
    path: CallPath,
    muted: Boolean,
    video: Boolean,
    quality: CaptureQuality,
    light: LightState,
    onMute: () -> Unit,
    onVideo: (Boolean) -> Unit,
    onQuality: (CaptureQuality) -> Unit,
    onLight: (LightMode, Float, Int) -> Unit,
    onStop: () -> Unit,
    onWatching: (Boolean) -> Unit
) {
    // Looking at the room counts as knowing about it; the alarm is for when nobody is.
    LifecycleResumeEffect(Unit) {
        onWatching(true)
        onPauseOrDispose { onWatching(false) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = peerName,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        if (silent || retrying) {
            Text(
                text = when {
                    retrying -> "Lost the room — trying again."
                    else -> "Not answering — nothing from this phone for a while."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.Black)
        ) {
            if (videoTrack != null && video) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        SurfaceViewRenderer(context).apply {
                            init(WebRtcEgl.base.eglBaseContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        }
                    },
                    onRelease = { renderer ->
                        videoTrack.removeSink(renderer)
                        renderer.release()
                    },
                    update = { renderer -> videoTrack.addSink(renderer) }
                )
            } else {
                Text(
                    text = if (video) "Waiting for the picture…" else "Listening. Camera is off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            NoiseMeter(
                level = noise,
                threshold = alarmAt,
                arming = arming,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(8.dp)
            )

            // Shown as soon as either half of it is known: the route is worked out from the
            // connection here, while the battery has to travel from the other phone.
            if (status != null || path != CallPath.Unknown) {
                StatusOverlay(
                    status = status ?: PhoneStatus(),
                    path = path,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }

            // Together in one corner: both are things you do to the picture, and a control on
            // its own at the far side reads as belonging to whatever is nearest it.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CameraButton(on = video, onClick = { onVideo(!video) })
                MuteButton(muted = muted, onClick = onMute)
                QualityMenu(selected = quality, onChoose = onQuality)
            }
        }

        Spacer(Modifier.height(16.dp))
        LightControls(light, onLight)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStop) { Text("Stop watching") }
    }
}

@Composable
private fun CameraButton(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Scrim)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) { drawCamera(on) }
    }
}

@Composable
private fun MuteButton(muted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Scrim)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) { drawSpeaker(muted) }
    }
}

@Composable
private fun QualityMenu(
    selected: CaptureQuality,
    onChoose: (CaptureQuality) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Scrim)
                .clickable { open = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Picture settings",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in CaptureQuality.entries) {
                DropdownMenuItem(
                    text = { Text(if (option == selected) "${option.label} ✓" else option.label) },
                    onClick = {
                        onChoose(option)
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * The light in the other room, from here: whether it is on, what colour, and how bright. A dark
 * nursery films as black, so the picture is only worth anything with this on — and red at a low
 * setting is the difference between seeing the room and waking what is in it.
 */
@Composable
private fun LightControls(light: LightState, onLight: (LightMode, Float, Int) -> Unit) {
    // Not keyed on the light: while a finger is on the slider, the slider is the truth, and
    // resetting it from the echo of its own change would make it fight the drag.
    var dragging by remember { mutableStateOf(light.brightness) }
    val on = light.mode != LightMode.Off

    // Sent while dragging rather than at the end of it, so the room brightens under your thumb —
    // but sampled, because a signal per pixel would be a flood the other phone has to wade
    // through. Conflating and then waiting keeps only the latest value of each interval.
    LaunchedEffect(Unit) {
        snapshotFlow { dragging }
            .drop(1)
            .conflate()
            .collect { value ->
                onLight(if (on) light.mode else LightMode.White, value, 0)
                delay(BrightnessInterval)
            }
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = light.mode == LightMode.Off,
                onClick = { onLight(LightMode.Off, 0f, 0) },
                label = { Text("Off") }
            )
            FilterChip(
                selected = light.mode == LightMode.White,
                onClick = { onLight(LightMode.White, dragging, 0) },
                label = { Text("White") }
            )
            FilterChip(
                selected = light.mode == LightMode.Red,
                onClick = { onLight(LightMode.Red, dragging, 0) },
                label = { Text("Red") }
            )
            TextButton(
                // The same light with an end to it, so a look at the room cannot be left on.
                onClick = { onLight(LightMode.White, 1f, GlanceSeconds) }
            ) { Text("Glance") }
        }

        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            // The last value always goes, in case the drag ended inside a sampling interval.
            onValueChangeFinished = { onLight(if (on) light.mode else LightMode.White, dragging, 0) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Light ${(dragging * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Fast enough to feel like a dimmer, slow enough not to be a flood. */
private const val BrightnessInterval = 80L

private val Scrim = Color(0x99000000)
