package ch.lqy.babyphone.device

/**
 * How much this phone trusts the key the backend reports for another device.
 *
 * Only ever decided here, never by the server: it depends on whether somebody confirmed the key
 * off the other phone's screen, which is a thing the backend has no way to know or to claim.
 */
enum class KeyTrust {
    /** Linked, so the phones can see each other, but the key is only the server's word for it. */
    ServerVouched,

    /** Confirmed against the key shown on the other phone. */
    Confirmed,

    /**
     * Confirmed once, and the server now reports something else. Either that phone reinstalled —
     * a new install means a new key — or someone is trying to get in the middle.
     */
    Changed
}

fun keyTrustOf(pinnedKey: String?, reportedKey: String): KeyTrust = when (pinnedKey) {
    null -> KeyTrust.ServerVouched
    reportedKey -> KeyTrust.Confirmed
    else -> KeyTrust.Changed
}
