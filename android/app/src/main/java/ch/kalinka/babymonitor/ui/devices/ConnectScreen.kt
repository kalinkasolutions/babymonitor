package ch.kalinka.babymonitor.ui.devices

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.kalinka.babymonitor.device.PairingViewModel
import ch.kalinka.babymonitor.device.ScanOutcome
import ch.kalinka.babymonitor.ui.QrCode

/**
 * Pairing, as a full screen with no bottom bar. Showing and scanning are tabs rather than roles,
 * because either phone can do either, and one scan settles both phones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onBack: () -> Unit,
    viewModel: PairingViewModel = viewModel()
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val outcome by viewModel.outcome.collectAsState()
    val paired = outcome is ScanOutcome.Confirmed || outcome is ScanOutcome.ConfirmedByScanner

    // When it worked, closing the screen is the answer. Only trouble is worth a dialog.
    LaunchedEffect(paired) {
        if (paired) {
            viewModel.dismissOutcome()
            onBack()
        }
    }

    if (!paired && outcome !is ScanOutcome.None) {
        ScanOutcomeDialog(outcome) { viewModel.dismissOutcome() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect a phone") },
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
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Show") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Scan") })
            }

            when (tab) {
                0 -> ShowCode(viewModel.qrPayload, viewModel.wifiOnly)
                else -> ScanCode(viewModel::onScanned)
            }
        }
    }
}

@Composable
private fun ShowCode(payload: String, wifiOnly: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Scan this code with the other phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        QrCode(payload, size = 240.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (wifiOnly) {
                "Both phones must be on the same WiFi."
            } else {
                "The code also carries this phone's server settings, so the other phone does " +
                    "not need them typed in."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ScanCode(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    LaunchedEffect(Unit) {
        if (!granted) {
            request.launch(Manifest.permission.CAMERA)
        }
    }

    if (!granted) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Scanning needs the camera. You can also let the other phone scan this " +
                    "one instead.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { request.launch(Manifest.permission.CAMERA) }) {
                Text("Allow camera")
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        QrScanner(
            onScanned = onScanned,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .align(Alignment.TopCenter)
        )
        Text(
            text = "Scan the QR code on the other phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        )
    }
}

@Composable
private fun ScanOutcomeDialog(outcome: ScanOutcome, onDismiss: () -> Unit) {
    val (title, body) = when (outcome) {
        is ScanOutcome.ProofFailed ->
            "${outcome.deviceName} could not prove it" to
                "${outcome.deviceName} could not show it had read the code off this screen. " +
                    "That happens when it scanned an older code. It is also what a stranger " +
                    "faking a pairing would look like. Nothing was paired: scan again."

        ScanOutcome.NotAPairingCode ->
            "Not a pairing code" to "That QR code is something else, or comes from an older " +
                "version of the app. Update both phones."

        is ScanOutcome.Failed -> "Could not pair" to outcome.message

        // Handled by closing the screen rather than by a dialog.
        ScanOutcome.None,
        is ScanOutcome.Confirmed,
        is ScanOutcome.ConfirmedByScanner -> return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}
