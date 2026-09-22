package ch.lqy.babyphone.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a phone puts in its pairing QR code.
 *
 * The code links the two accounts. The key is the part that only a QR can carry — a code short
 * enough to read aloud cannot hold one — and it is read off a screen rather than off the network,
 * which is what lets the scanner tell a genuine key from one the backend made up.
 */
@Serializable
data class PairingPayload(
    @SerialName("v") val version: Int = CurrentVersion,
    @SerialName("d") val deviceId: String,
    @SerialName("k") val publicKey: String,

    /**
     * The code that links the two accounts. Empty when there are no accounts: with no server
     * there is nothing to link, and the key beside it is the whole of what a scan is for.
     */
    @SerialName("c") val code: String = "",

    /** What this phone calls itself, since no account has a name for it. */
    @SerialName("m") val name: String = "",

    /**
     * The one-time secret that makes a single scan confirm both phones. Present only in a QR —
     * a code short enough to read out has no room for it, which is why the typed path cannot
     * reach full trust.
     */
    @SerialName("n") val secret: String
) {
    fun encode(): String = Format.encodeToString(this)

    companion object {
        const val CurrentVersion = 1

        private val Format = Json { ignoreUnknownKeys = true }

        fun decode(text: String): PairingPayload? =
            runCatching { Format.decodeFromString<PairingPayload>(text) }
                .getOrNull()
                ?.takeIf {
                    it.version == CurrentVersion &&
                        it.deviceId.isNotBlank() &&
                        it.publicKey.isNotBlank() &&
                        it.secret.isNotBlank()
                }
    }
}
