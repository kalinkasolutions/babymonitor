package ch.kalinka.babymonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.kalinka.babymonitor.auth.AuthState
import ch.kalinka.babymonitor.auth.AuthViewModel
import ch.kalinka.babymonitor.nav.SignedInNavHost
import ch.kalinka.babymonitor.ui.LoginScreen
import ch.kalinka.babymonitor.ui.RegisterScreen
import ch.kalinka.babymonitor.ui.TwoFactorLoginScreen
import ch.kalinka.babymonitor.ui.theme.BabymonitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BabymonitorTheme {
                BabymonitorApp()
            }
        }
    }
}

@Composable
private fun BabymonitorApp(viewModel: AuthViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()

    when (val current = state) {
        AuthState.Checking -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }

        AuthState.TwoFactorRequired -> TwoFactorLoginScreen(
            busy = busy,
            error = error,
            onSubmit = viewModel::submitTwoFactorCode,
            onCancel = viewModel::cancelTwoFactor
        )

        is AuthState.SignedOut ->
            if (current.showRegister) {
                // Otherwise back leaves the app from a screen you opened by choice, one step in.
                BackHandler { viewModel.showRegister(false) }

                RegisterScreen(
                    busy = busy,
                    error = error,
                    onRegister = viewModel::register,
                    onGoToLogin = { viewModel.showRegister(false) }
                )
            } else {
                LoginScreen(
                    initialServerUrl = viewModel.serverUrl,
                    busy = busy,
                    error = error,
                    onServerUrlChange = { viewModel.serverUrl = it },
                    onLogin = viewModel::login,
                    onGoToRegister = { viewModel.showRegister(true) },
                    onWithoutAccount = viewModel::continueWithoutAccount
                )
            }

        is AuthState.SignedIn -> SignedInNavHost(
            username = current.username,
            serverUrl = viewModel.serverUrl,
            onServerUrlChange = { viewModel.serverUrl = it },
            onSignedOut = viewModel::forgetSession
        )
    }
}
