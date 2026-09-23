package ch.kalinka.babymonitor.account

/** Which account screen is showing. The overview is the root; the rest are pushed on top of it. */
enum class AccountPage {
    Overview,
    ChangeUsername,
    ChangeEmail,
    ChangePassword,
    TwoFactor,
    Invite,
    DeleteAccount
}
