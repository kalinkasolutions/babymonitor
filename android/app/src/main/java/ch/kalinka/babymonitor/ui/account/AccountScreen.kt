package ch.kalinka.babymonitor.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.kalinka.babymonitor.account.AccountPage
import ch.kalinka.babymonitor.account.AccountViewModel

/**
 * The account area. One view model owns the whole thing; [AccountPage] decides which form shows,
 * so the back arrow only ever has to return to the overview.
 */
@Composable
fun AccountScreen(
    onClose: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: AccountViewModel = viewModel()
) {
    val account by viewModel.account.collectAsState()
    val page by viewModel.page.collectAsState()
    val twoFactor by viewModel.twoFactor.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val signedOut by viewModel.signedOut.collectAsState()

    if (signedOut) {
        onSignedOut()
        return
    }

    val back = { viewModel.open(AccountPage.Overview) }

    when (page) {
        AccountPage.Overview -> AccountOverview(
            account = account,
            error = error,
            notice = notice,
            onOpen = viewModel::open,
            onSignOutEverywhere = viewModel::signOutEverywhere,
            onBack = onClose
        )

        AccountPage.ChangeUsername -> ChangeUsernameScreen(
            current = account?.username.orEmpty(),
            busy = busy,
            error = error,
            onSubmit = viewModel::updateUsername,
            onBack = back
        )

        AccountPage.ChangeEmail -> ChangeEmailScreen(
            current = account?.email.orEmpty(),
            busy = busy,
            error = error,
            onSubmit = viewModel::requestEmailChange,
            onBack = back
        )

        AccountPage.ChangePassword -> ChangePasswordScreen(
            busy = busy,
            error = error,
            onSubmit = viewModel::changePassword,
            onBack = back
        )

        AccountPage.TwoFactor -> TwoFactorScreen(
            enabled = account?.twoFactorEnabled == true,
            recoveryCodesLeft = account?.recoveryCodesLeft ?: 0,
            state = twoFactor,
            busy = busy,
            error = error,
            onStartSetup = viewModel::startTwoFactorSetup,
            onEnable = viewModel::enableTwoFactor,
            onDisable = viewModel::disableTwoFactor,
            onRegenerateCodes = viewModel::regenerateRecoveryCodes,
            onDismissCodes = viewModel::dismissRecoveryCodes,
            onBack = back
        )

        AccountPage.Invite -> InviteScreen(
            busy = busy,
            error = error,
            onSubmit = viewModel::invite,
            onBack = back
        )

        AccountPage.DeleteAccount -> DeleteAccountScreen(
            busy = busy,
            error = error,
            onSubmit = viewModel::deleteAccount,
            onBack = back
        )
    }
}
