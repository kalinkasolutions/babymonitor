package ch.kalinka.babymonitor.ui.account

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ch.kalinka.babymonitor.ui.ErrorText
import ch.kalinka.babymonitor.ui.MinPasswordLength

@Composable
fun ChangeUsernameScreen(
    current: String,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit
) {
    var username by rememberSaveable { mutableStateOf(current) }

    FormScaffold("Display name", onBack) {
        Text(
            "The name the other phone shows for you. It is not used to sign in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onSubmit(username) },
            enabled = !busy && username.isNotBlank() && username != current,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save") }
    }
}

@Composable
fun ChangeEmailScreen(
    current: String,
    busy: Boolean,
    error: String?,
    onSubmit: (email: String, password: String) -> Unit,
    onBack: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    FormScaffold("Email address", onBack) {
        Text(
            "Currently $current. The account only moves once you follow the link sent to the new address.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("New email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        PasswordField(password, "Current password") { password = it }
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onSubmit(email, password) },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Send confirmation link") }
    }
}

@Composable
fun ChangePasswordScreen(
    busy: Boolean,
    error: String?,
    onSubmit: (current: String, new: String) -> Unit,
    onBack: () -> Unit
) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }

    FormScaffold("Password", onBack) {
        Text(
            "Changing your password signs out your other devices. This one stays signed in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        PasswordField(current, "Current password") { current = it }
        Spacer(Modifier.height(12.dp))
        PasswordField(new, "New password", "At least $MinPasswordLength characters") { new = it }
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onSubmit(current, new) },
            enabled = !busy && current.isNotBlank() && new.length >= MinPasswordLength,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Change password") }
    }
}

@Composable
fun InviteScreen(
    busy: Boolean,
    error: String?,
    onSubmit: (email: String, username: String) -> Unit,
    onBack: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }

    FormScaffold("Invite someone", onBack) {
        Text(
            "For someone who is not here. If they are, pairing by QR code is better — it verifies " +
                "the connection without trusting the server.\n\nIf they already have an account, " +
                "they get an email asking whether to link with yours; the display name is only " +
                "used when it creates one for them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Their email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Their display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onSubmit(email, username) },
            enabled = !busy && email.isNotBlank() && username.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Send invitation") }
    }
}

@Composable
fun DeleteAccountScreen(
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit
) {
    var password by rememberSaveable { mutableStateOf("") }

    FormScaffold("Delete account", onBack) {
        Text(
            "This removes the account and everything belonging to it. It cannot be undone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        PasswordField(password, "Current password") { password = it }
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onSubmit(password) },
            enabled = !busy && password.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Delete my account") }
    }
}

@Composable
private fun PasswordField(
    value: String,
    label: String,
    supporting: String? = null,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
    )
}
