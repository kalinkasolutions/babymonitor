package ch.kalinka.babymonitor.device

/** What came of a pairing: scanning a code, or having this phone's code scanned. */
sealed interface ScanOutcome {
    data object None : ScanOutcome

    /** This phone scanned the other one, and the other one heard about it. */
    data class Confirmed(val deviceName: String) : ScanOutcome

    /** The other phone scanned this one and proved it read this screen. */
    data class ConfirmedByScanner(val deviceName: String) : ScanOutcome

    /**
     * Something claimed to have scanned this phone and could not prove it. Innocently a scan of
     * an older code; it is also what a stranger making up a pairing would look like.
     */
    data class ProofFailed(val deviceName: String) : ScanOutcome

    data object NotAPairingCode : ScanOutcome

    data class Failed(val message: String) : ScanOutcome
}
