package ch.kalinka.babymonitor.net

import java.time.Instant
import java.time.format.DateTimeParseException

/** Early enough that a phone asleep for a while still wakes up before its credentials run out. */
private const val RenewBeforeExpiry = 60 * 60 * 1000L

/**
 * Whether the relay credentials should be fetched again. A STUN-only answer has no credential, so
 * there is nothing to expire; one whose expiry cannot be read is treated as already expired.
 */
fun needsIceRenewal(ice: IceServersDto?, nowMillis: Long): Boolean {
    if (ice == null) return true
    if (ice.username.isBlank()) return false

    val expiresAt = try {
        Instant.parse(ice.expiresAt).toEpochMilli()
    } catch (_: DateTimeParseException) {
        return true
    }

    return expiresAt - nowMillis < RenewBeforeExpiry
}
