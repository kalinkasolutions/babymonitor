package ch.kalinka.babymonitor.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.kalinka.babymonitor.account.AccountPage
import ch.kalinka.babymonitor.net.AccountDto
import ch.kalinka.babymonitor.ui.ErrorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountOverview(
    account: AccountDto?,
    error: String?,
    notice: String?,
    onOpen: (AccountPage) -> Unit,
    onSignOutEverywhere: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account") },
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
                .padding(horizontal = 24.dp)
        ) {
            if (account == null) {
                ErrorText(error ?: "Loading…")
                return@Column
            }

            Spacer(Modifier.height(8.dp))
            Text(account.username, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = account.email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (notice != null) {
                Spacer(Modifier.height(8.dp))
                Text(notice, style = MaterialTheme.typography.bodyMedium)
            }
            ErrorText(error)
            Spacer(Modifier.height(20.dp))

            SettingsRow("Display name", account.username) { onOpen(AccountPage.ChangeUsername) }
            SettingsRow(
                label = "Email",
                value = if (account.emailConfirmed) account.email else "${account.email} — not confirmed"
            ) { onOpen(AccountPage.ChangeEmail) }
            SettingsRow("Password", "Change your password") { onOpen(AccountPage.ChangePassword) }
            SettingsRow(
                label = "Two-factor authentication",
                value = if (account.twoFactorEnabled) {
                    "On — ${account.recoveryCodesLeft} recovery codes left"
                } else {
                    "Off"
                }
            ) { onOpen(AccountPage.TwoFactor) }
            SettingsRow("Invite someone", "Add a second person by email") { onOpen(AccountPage.Invite) }
            SettingsRow("Sign out everywhere", "Ends the session on every device, including this one") {
                onSignOutEverywhere()
            }
            SettingsRow(
                label = "Delete account",
                value = "Permanent",
                labelColor = MaterialTheme.colorScheme.error
            ) { onOpen(AccountPage.DeleteAccount) }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Member since ${account.memberSince.take(10)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
