package ch.lqy.babyphone.device

/** What came of a pairing: scanning a code, typing one, or having this phone's code scanned. */
sealed interface ScanOutcome {
    data object None : ScanOutcome

    /** Linked, and the key on screen matched the one the backend holds — trust 2 of 2. */
    data class Confirmed(val deviceName: String) : ScanOutcome

    /**
     * The other end of a scan: that phone read this one's code and proved it, so its key is
     * genuine too. Trust 2 of 2 without this phone ever pointing a camera at anything.
     */
    data class ConfirmedByScanner(val deviceName: String) : ScanOutcome

    /** Linked by a typed code, which carries no key — trust 1 of 2 until somebody compares it. */
    data class LinkedOnly(val deviceName: String) : ScanOutcome

    /**
     * Linked, but the phone on screen holds a different key from the one the backend handed over
     * for it. That is what an attempt to listen in would look like.
     */
    data class Mismatch(val deviceName: String) : ScanOutcome

    /**
     * Something claimed this phone's code and could not prove it had read it off this screen.
     * Innocently that is a code that rolled over mid-scan; it is also what a backend relaying
     * somebody else's key would look like, so it is never quietly treated as a confirmation.
     */
    data class ProofFailed(val deviceName: String) : ScanOutcome

    data object NotAPairingCode : ScanOutcome

    data class Failed(val message: String) : ScanOutcome
}
