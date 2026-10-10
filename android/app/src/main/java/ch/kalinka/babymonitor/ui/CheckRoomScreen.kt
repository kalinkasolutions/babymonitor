package ch.kalinka.babymonitor.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.kalinka.babymonitor.media.CallState
import ch.kalinka.babymonitor.media.MonitorViewModel
import ch.kalinka.babymonitor.media.microphoneLevel

/**
 * What this phone would send, seen on this phone: the same front camera a call uses, and how
 * loud the room sounds to its microphone. For placing the phone before the night.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckRoomScreen(onBack: () -> Unit, viewModel: MonitorViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val alarm by viewModel.alarm.collectAsState()

    // A call owns the camera and the microphone; the check gives way to it.
    val inCall = state !is CallState.Idle && state !is CallState.Failed

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check the picture") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.Black)
            ) {
                when {
                    inCall -> Text(
                        text = "Another phone is watching right now.",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    !viewModel.hasCameraPermission() || !viewModel.hasMicrophonePermission() -> Text(
                        text = "Needs the camera and the microphone. Allow both on the Monitor tab.",
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )

                    else -> {
                        FrontCameraPreview(Modifier.fillMaxSize())

                        val level by remember { microphoneLevel() }.collectAsState(initial = 0f)
                        NoiseMeter(
                            level = level,
                            threshold = alarm.threshold,
                            arming = 0f,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "This is the camera a call uses. Make sure the bed is in the picture, " +
                    "then clap: the bar on the left shows how loud that reaches the phone. The " +
                    "line on it is where the noise alarm starts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
