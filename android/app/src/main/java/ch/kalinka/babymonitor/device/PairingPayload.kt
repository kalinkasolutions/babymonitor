package ch.kalinka.babymonitor.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a phone puts in its pairing QR code. The key is read off a screen rather than off the
 * network, which is what makes it trustworthy.
 */
@Serializable
data class PairingPayload(
    @SerialName("v") val version: Int = CurrentVersion,
    @SerialName("d") val deviceId: String,
    @SerialName("k") val publicKey: String,
    @SerialName("m") val name: String = "",

    /** The one-time secret the scanner uses to prove it read this screen, so one scan confirms both. */
    @SerialName("n") val secret: String,

    /** This phone's relay, if it has one, so the scanner can use it too. */
    @SerialName("s") val server: String = "",
    @SerialName("a") val serverKey: String = ""
) {
    fun encode(): String = Format.encodeToString(this)

    companion object {
        const val CurrentVersion = 2

        private val Format = Json { ignoreUnknownKeys = true }

        fun decode(text: String): PairingPayload? =
            runCatching { Format.decodeFromString<PairingPayload>(text) }
                .getOrNull()
                ?.takeIf {
                    it.version == CurrentVersion &&
                        it.publicKey.isNotBlank() &&
                        it.secret.isNotBlank() &&
                        runCatching { deviceIdOf(it.publicKey) }.getOrNull() == it.deviceId
                }
    }
}
