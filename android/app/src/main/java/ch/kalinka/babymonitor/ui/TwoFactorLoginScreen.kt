package ch.kalinka.babymonitor.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp

@Composable
fun TwoFactorLoginScreen(
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var code by rememberSaveable { mutableStateOf("") }

    AuthScaffold(
        title = "One more step",
        subtitle = "Enter the code from your authenticator app, or one of your recovery codes."
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Code") },
            singleLine = true,
            // Not a numeric keyboard: a recovery code is also valid here and contains letters.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )

        ErrorText(error)

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onSubmit(code) },
            enabled = !busy && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Sign in") }

        TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
    }
}
