package ch.kalinka.babymonitor.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ch.kalinka.babymonitor.account.TwoFactorState
import ch.kalinka.babymonitor.ui.ErrorText
import ch.kalinka.babymonitor.ui.QrCode

@Composable
fun TwoFactorScreen(
    enabled: Boolean,
    recoveryCodesLeft: Int,
    state: TwoFactorState,
    busy: Boolean,
    error: String?,
    onStartSetup: () -> Unit,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onRegenerateCodes: (String) -> Unit,
    onDismissCodes: () -> Unit,
    onBack: () -> Unit
) {
    // The codes are shown once and never again, so the only way off this screen is the
    // acknowledgement button — a back arrow here would quietly lose them.
    if (state is TwoFactorState.ShowingRecoveryCodes) {
        RecoveryCodes(state.codes, onDismissCodes)
        return
    }

    FormScaffold("Two-factor authentication", onBack) {
        when {
            state is TwoFactorState.AwaitingCode -> ConfirmSetup(state, busy, error, onEnable)
            enabled -> AlreadyOn(recoveryCodesLeft, busy, error, onDisable, onRegenerateCodes)
            else -> NotOnYet(busy, error, onStartSetup)
        }
    }
}

@Composable
private fun NotOnYet(busy: Boolean, error: String?, onStartSetup: () -> Unit) {
    Text(
        "Ask for a code from an authenticator app every time you sign in.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ErrorText(error)
    Spacer(Modifier.height(20.dp))
    Button(onClick = onStartSetup, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Set up")
    }
}

@Composable
private fun ConfirmSetup(
    state: TwoFactorState.AwaitingCode,
    busy: Boolean,
    error: String?,
    onEnable: (String) -> Unit
) {
    var code by rememberSaveable { mutableStateOf("") }

    Text(
        "Scan this with your authenticator app, then type the code it shows.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(16.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        QrCode(state.setup.authenticatorUri)
        Spacer(Modifier.height(12.dp))
        Text("Or type this key:", style = MaterialTheme.typography.bodySmall)
        Text(
            text = state.setup.sharedKey,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Code from the app") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
    )
    ErrorText(error)
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { onEnable(code) },
        enabled = !busy && code.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Turn on") }
}

@Composable
private fun AlreadyOn(
    recoveryCodesLeft: Int,
    busy: Boolean,
    error: String?,
    onDisable: (String) -> Unit,
    onRegenerateCodes: (String) -> Unit
) {
    var password by rememberSaveable { mutableStateOf("") }

    Text("Two-factor authentication is on.", style = MaterialTheme.typography.bodyLarge)
    Text(
        "$recoveryCodesLeft recovery codes left.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // One password field for both buttons below it. New recovery codes retire the old ones and
    // are ten standing ways past the second factor, so that is as much of a change as turning it
    // off — and neither should rest on nothing more than the phone being unlocked.
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Current password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
    )
    ErrorText(error)

    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = { onRegenerateCodes(password) },
        enabled = !busy && password.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Generate new recovery codes") }

    Spacer(Modifier.height(32.dp))
    Text(
        "Turning it off also forgets the authenticator secret, so setting it up again pairs a new one.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { onDisable(password) },
        enabled = !busy && password.isNotBlank(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Turn off") }
}

@Composable
private fun RecoveryCodes(codes: List<String>, onDone: () -> Unit) {
    FormScaffold("Recovery codes", onBack = onDone) {
        Text(
            "Write these down now. They are shown once, and each one works a single time — they " +
                "are how you get in if you lose the authenticator.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (code in codes) {
                    Text(code, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("I have written them down")
        }
    }
}
