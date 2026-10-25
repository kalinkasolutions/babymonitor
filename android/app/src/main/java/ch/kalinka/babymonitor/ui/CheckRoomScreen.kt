package ch.kalinka.babymonitor.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.kalinka.babymonitor.media.CallState
import ch.kalinka.babymonitor.media.MonitorViewModel
import ch.kalinka.babymonitor.media.microphoneLevel

/**
 * What this phone would send, seen on this phone: the same front camera a call uses, and how
 * loud the room sounds to its microphone. For placing the phone before the night, so it fills
 * the screen in either orientation and keeps a back button big enough to hit while holding it up.
 */
@Composable
fun CheckRoomScreen(onBack: () -> Unit, viewModel: MonitorViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    // The alarm is raised on the watching phone, so its threshold is the one worth drawing here.
    val alarmAt by viewModel.watcherThreshold.collectAsState()

    // A call owns the camera and the microphone; the check gives way to it.
    val inCall = state !is CallState.Idle && state !is CallState.Failed

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose { }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val cameraUsable = !inCall && viewModel.hasCameraPermission() && viewModel.hasMicrophonePermission()
            if (cameraUsable) {
                FrontCameraPreview(Modifier.fillMaxSize())
            }

            // Kept clear of a notch, which sits at the side once the phone is turned.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(16.dp)
            ) {
                when {
                    inCall -> Text(
                        text = "Another phone is watching right now.",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    !cameraUsable -> Text(
                        text = "Needs the camera and the microphone. Allow both on the Monitor tab.",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    else -> {
                        val level by remember { microphoneLevel() }.collectAsState(initial = 0f)
                        NoiseMeter(
                            level = level,
                            threshold = alarmAt,
                            arming = 0f,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )

                        Text(
                            text = "Make sure the bed is in the picture, then clap: the bar shows how loud " +
                                "that reaches the phone. " +
                                if (alarmAt != null) {
                                    "Its line is where the watching phone's noise alarm starts."
                                } else {
                                    "A line for the noise alarm appears after the first call."
                                },
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .widthIn(max = 480.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(Scrim)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                BackButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))
            }
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Scrim)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.size(36.dp)
        )
    }
}

/** The front camera, because a call films with it: it faces the same way as the screen light. */
@Composable
private fun FrontCameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            providerFuture.addListener({
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val provider = providerFuture.get()
                provider.unbindAll()
                // A phone with no front camera just shows black, like a call would.
                runCatching { provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview) }
            }, ContextCompat.getMainExecutor(context))
            previewView
        },
        // Let go at once, so a call arriving right after finds the camera free.
        onRelease = { ProcessCameraProvider.getInstance(context).get().unbindAll() }
    )
}

private val Scrim = Color(0x99000000)
