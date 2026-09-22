package ch.lqy.babyphone

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import ch.lqy.babyphone.auth.AuthState
import ch.lqy.babyphone.auth.AuthViewModel
import ch.lqy.babyphone.nav.SignedInNavHost
import ch.lqy.babyphone.ui.LoginScreen
import ch.lqy.babyphone.ui.RegisterScreen
import ch.lqy.babyphone.ui.TwoFactorLoginScreen
import ch.lqy.babyphone.ui.theme.BabyphoneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BabyphoneTheme {
                BabyphoneApp()
            }
        }
    }
}

@Composable
private fun BabyphoneApp(viewModel: AuthViewModel = viewModel()) {
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
