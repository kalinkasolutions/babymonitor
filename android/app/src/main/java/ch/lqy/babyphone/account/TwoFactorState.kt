package ch.lqy.babyphone.account

import ch.lqy.babyphone.net.TwoFactorSetupDto

/** What the two-factor screen is currently doing. */
sealed interface TwoFactorState {
    data object Idle : TwoFactorState

    /** A secret has been issued and is waiting for a code to confirm it. */
    data class AwaitingCode(val setup: TwoFactorSetupDto) : TwoFactorState

    /** Codes are shown once and never again, so the user has to acknowledge them. */
    data class ShowingRecoveryCodes(val codes: List<String>) : TwoFactorState
}
