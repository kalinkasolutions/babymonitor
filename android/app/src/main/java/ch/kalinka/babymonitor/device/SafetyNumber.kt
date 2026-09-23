package ch.kalinka.babymonitor.device

import java.security.MessageDigest
import java.util.Locale

/**
 * A short string derived from both phones' identity keys, for two people to read to each other.
 *
 * This is how the keys get confirmed without a camera. If the backend had substituted a key, the
 * two phones would be hashing different pairs — one holds (real, forged), the other (forged,
 * real) — and the strings would differ. Matching strings therefore mean both keys are genuine,
 * and both phones can mark the other confirmed from a single comparison rather than each having
 * to scan.
 *
 * The keys are sorted before hashing so both sides compute the same value regardless of which
 * phone is asking.
 */
object SafetyNumber {
    private const val Groups = 8
    private const val GroupLength = 4

    fun of(oneKey: String, otherKey: String): String {
        val ordered = listOf(oneKey, otherKey).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(ordered.joinToString("\n").toByteArray())

        // 128 bits: past the point where a key colliding with a specific pair could be ground
        // out, and still two short lines to read to somebody.
        val hex = digest.take(Groups * GroupLength / 2)
            .joinToString("") { "%02x".format(it) }
            .uppercase(Locale.ROOT)

        return (0 until Groups).joinToString(" ") {
            hex.substring(it * GroupLength, (it + 1) * GroupLength)
        }
    }
}
