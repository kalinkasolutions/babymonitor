package ch.kalinka.babymonitor.ui.devices

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Camera preview that reports the first QR code it reads.
 *
 * The preview is a classic View, so it comes in through AndroidView — CameraX and WebRTC both
 * render into Views and there is no Compose equivalent.
 */
@Composable
fun QrScanner(onScanned: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { MultiFormatReader().apply { setHints(QrHints) } }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext)
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)

            providerFuture.addListener({
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor) { image -> image.decode(reader, onScanned) } }

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(viewContext))

            previewView
        },
        onRelease = { analysisExecutor.shutdown() }
    )
}

private val QrHints = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)
)

/**
 * Reads the luminance plane straight out of the frame. Only the Y plane is needed for a QR code,
 * so there is no colour conversion to pay for on every frame.
 */
@SuppressLint("UnsafeOptInUsageError")
private fun ImageProxy.decode(reader: MultiFormatReader, onScanned: (String) -> Unit) {
    try {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val source = PlanarYUVLuminanceSource(
            bytes, planes[0].rowStride, height, 0, 0, width, height, false
        )

        val result = runCatching {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        }.getOrNull()

        if (result != null) {
            onScanned(result.text)
        }
    } finally {
        reader.reset()
        close()
    }
}
