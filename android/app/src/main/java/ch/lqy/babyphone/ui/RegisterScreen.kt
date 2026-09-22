package ch.lqy.babyphone.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

// Mirrors the backend's Identity rules, so the obvious mistakes are caught without a round trip.
private const val MIN_PASSWORD_LENGTH = 4

@Composable
fun RegisterScreen(
    busy: Boolean,
    error: String?,
    onRegister: (email: String, username: String, password: String) -> Unit,
    onGoToLogin: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val canSubmit = !busy &&
        email.isNotBlank() &&
        username.isNotBlank() &&
        password.length >= MIN_PASSWORD_LENGTH

    AuthScaffold(
        title = "Create an account",
        subtitle = "One account holds both phones. The other joins by scanning a code, or by invitation if it is not here."
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Display name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            supportingText = { Text("At least $MIN_PASSWORD_LENGTH characters") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )

        ErrorText(error)

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onRegister(email, username, password) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp))
            } else {
                Text("Create account")
            }
        }

        TextButton(onClick = onGoToLogin, enabled = !busy) {
            Text("I already have an account")
        }
    }
}
