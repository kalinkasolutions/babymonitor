package ch.kalinka.babymonitor.auth

/** Where the user is in the sign-in flow. The UI renders one screen per state. */
sealed interface AuthState {
    /** Checking a stored cookie against the server on launch. */
    data object Checking : AuthState

    data class SignedOut(val showRegister: Boolean = false) : AuthState

    /** The password was accepted but no cookie is issued until a second factor is given. */
    data object TwoFactorRequired : AuthState

    data class SignedIn(val username: String) : AuthState
}
