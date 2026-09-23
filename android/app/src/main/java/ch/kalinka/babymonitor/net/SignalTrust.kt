package ch.kalinka.babymonitor.net

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.math.abs

/**
 * How far out of step two phones' clocks may be and still talk. Generous, because these are two
 * consumer handsets that may have been off for a while, and short enough that a message captured
 * in flight is worthless by the time anybody could use it. Signalling is answered in seconds —
 * a call gives up after fifteen — so nothing legitimate is anywhere near this old.
 */
const val SignalFreshnessWindowMillis = 60_000L

/**
 * Why a signal was or was not believed. A verdict rather than a boolean, because the three ways
 * of failing want different things done about them: a key nobody confirmed is a pairing step the
 * user has not done, a clock out of step is a phone to fix, and a bad signature on a message that
 * reached the right phone is the one that should never happen.
 */
sealed interface SignalTrust {
    data object Verified : SignalTrust

    /** No key has been confirmed for that phone, so there is nothing to check the signature against. */
    data object Unconfirmed : SignalTrust

    /** Signed, but not by the key this phone confirmed — or not signed at all. */
    data object WrongKey : SignalTrust

    /** Genuine, and too old or too far in the future to be live. */
    data object Stale : SignalTrust

    val isVerified: Boolean get() = this is Verified
}

/**
 * Whether this signal really came from the phone whose key was confirmed in person, recently
 * enough to be live.
 *
 * [now] is a parameter so the freshness window can be tested without waiting out a minute.
 */
fun SignalMessage.trust(
    confirmedKeyBase64: String?,
    now: Long = System.currentTimeMillis()
): SignalTrust {
    if (confirmedKeyBase64.isNullOrBlank()) {
        return SignalTrust.Unconfirmed
    }

    if (signature.isBlank()) {
        return SignalTrust.WrongKey
    }

    // Checked both ways round: a clock behind rejects a replay, and one running ahead cannot be
    // used to stamp a message so it stays valid.
    if (abs(now - sentAt) > SignalFreshnessWindowMillis) {
        return SignalTrust.Stale
    }

    val valid = runCatching {
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(confirmedKeyBase64)))

        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(signedBytes())
            verify(Base64.getDecoder().decode(signature))
        }
    }.getOrDefault(false)

    return if (valid) SignalTrust.Verified else SignalTrust.WrongKey
}
