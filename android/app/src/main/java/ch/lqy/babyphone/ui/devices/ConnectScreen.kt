package ch.lqy.babyphone.ui.devices

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.lqy.babyphone.device.PairingViewModel
import ch.lqy.babyphone.device.ScanOutcome
import ch.lqy.babyphone.ui.ErrorText
import ch.lqy.babyphone.ui.QrCode

/**
 * Pairing, as a full screen with no bottom bar — a task you finish and leave.
 *
 * Showing and scanning are tabs rather than roles, because either phone can do either. One scan
 * settles both phones: the scanner reads the key off the screen, and proves it did, so the phone
 * that was scanned can trust it back. Whichever side this phone played, it leaves with a verdict.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onBack: () -> Unit,
    onInvite: () -> Unit,
    viewModel: PairingViewModel = viewModel()
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val outcome by viewModel.outcome.collectAsState()
    val error by viewModel.error.collectAsState()
    val code by viewModel.code.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val secondsLeft by viewModel.secondsLeft.collectAsState()

    // Reached from either side: this phone scanned, or it was scanned and the server said so.
    val paired = outcome is ScanOutcome.Confirmed ||
        outcome is ScanOutcome.ConfirmedByScanner ||
        outcome is ScanOutcome.LinkedOnly

    // Nothing to say when it worked: the screen closing is the answer, and the trust it reached
    // is on the device list next to the phone it belongs to. Only trouble is worth a dialog.
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

                // A typed code links two accounts. With none, there is nothing it could say.
                if (!viewModel.withoutAccount) {
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Type") })
                }
            }

            when (tab) {
                0 -> ShowCode(
                    payload = viewModel.qrPayload,
                    shortCode = code?.code,
                    secondsLeft = secondsLeft,
                    error = error,
                    withoutAccount = viewModel.withoutAccount,
                    onNewCode = viewModel::newCode,
                    onInvite = onInvite
                )

                1 -> ScanCode(viewModel::onScanned)
                else -> TypeCode(busy, viewModel::onCodeTyped)
            }
        }
    }
}

@Composable
private fun ShowCode(
    payload: String?,
    shortCode: String?,
    secondsLeft: Long,
    error: String?,
    withoutAccount: Boolean,
    onNewCode: () -> Unit,
    onInvite: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (payload == null) {
            Text(
                text = "No code yet. This phone has to reach the server once to get one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            ErrorText(error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onNewCode) { Text("Try again") }
            return@Column
        }

        Text(
            text = "Scan this from the other phone. The code carries the key this phone actually " +
                "holds, read off the screen instead of over the network — which is what proves " +
                "nobody swapped it on the way.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        QrCode(payload, size = 240.dp)

        if (!shortCode.isNullOrBlank()) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("No camera? Read this out instead:", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = shortCode,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Typing it links the phones but cannot carry a key, so they stay at " +
                    "trust 1 of 2 until one scans the other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Good for another ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (withoutAccount) {
            // Nothing here expires and nothing can be typed, so the screen should stop implying
            // both. What it can say is why, and what the other phone has to be doing.
            Spacer(Modifier.height(16.dp))
            Text(
                text = "There is no code to type: without an account there are no accounts to " +
                    "link, and a key is far too long to read out. Scanning is the whole of it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The other phone needs the app open on this WiFi — it is told who scanned " +
                    "it over the network, and there is no server to hold that message for later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            return@Column
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onNewCode) { Text("New code") }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("Not in the same room?", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onInvite) { Text("Invite by email instead") }
    }
}

@Composable
private fun TypeCode(busy: Boolean, onSubmit: (String) -> Unit) {
    var entered by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Type the code shown on the other phone. This links them so they can see each " +
                "other; the keys stay unconfirmed until one scans the other.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = entered,
            onValueChange = { entered = it.uppercase() },
            label = { Text("Code") },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(entered) },
            enabled = !busy && entered.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Link") }
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
            text = "Point at the code on the other phone.",
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
        is ScanOutcome.Confirmed ->
            "${outcome.deviceName}: trust 2 of 2" to
                "Linked, and the key on that screen matches the one the server handed over — so " +
                    "nothing is sitting in between. That one scan settles both phones; there is " +
                    "nothing to scan back."

        is ScanOutcome.ConfirmedByScanner ->
            "${outcome.deviceName}: trust 2 of 2" to
                "${outcome.deviceName} scanned this screen and proved it, so its key is genuine " +
                    "and this phone has kept it. Both sides are confirmed — nothing left to do."

        is ScanOutcome.ProofFailed ->
            "${outcome.deviceName} could not prove it" to
                "${outcome.deviceName} is linked, but it could not show it had read the code off " +
                    "this screen. That happens when the code was refreshed mid-scan — it is also " +
                    "what somebody relaying a key of their own would look like. The keys are not " +
                    "confirmed: show a fresh code and scan it again."

        is ScanOutcome.LinkedOnly ->
            "${outcome.deviceName}: trust 1 of 2" to
                "Linked, so the phones can see each other. A typed code cannot carry a key, so " +
                    "this rests on trusting the server. Scan the other phone's code when you are " +
                    "next together to get to 2 of 2."

        is ScanOutcome.Mismatch ->
            "That key does not match" to
                "${outcome.deviceName} is showing a different key from the one the server gave " +
                    "for it. That happens when the app is reinstalled — but it is also exactly " +
                    "what listening in would look like. Do not trust the connection until it is " +
                    "re-registered and scanned again."

        ScanOutcome.NotAPairingCode ->
            "Not a pairing code" to "That QR code is something else."

        is ScanOutcome.Failed -> "Could not link" to outcome.message

        // Handled by closing the screen rather than by a dialog.
        ScanOutcome.None,
        is ScanOutcome.Confirmed,
        is ScanOutcome.ConfirmedByScanner,
        is ScanOutcome.LinkedOnly -> return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}
