package ch.lqy.babyphone.net

import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A signalling message as it travels across the LAN, with proof of who sent it.
 *
 * The hub knows who is speaking because they signed in. On a local socket nobody has, and a
 * message that merely claims to come from the nursery phone is worth nothing — anyone on the
 * WiFi could send one. So the sender signs it with the identity key in its keystore, and the
 * receiver checks that against the key it confirmed by scanning the other phone's screen.
 *
 * That makes the LAN path need the same trust the microphone does: phones at 1 of 2 fall back to
 * the server, where the cookie does the vouching instead.
 */
@Serializable
data class LanEnvelope(
    val from: String = "",

    /** The [SignalMessage] as JSON. Signed as text, so both ends agree byte for byte. */
    val signal: String = "",
    val signature: String = ""
)

/** Whether this envelope really came from the phone whose key was confirmed in person. */
fun LanEnvelope.isSignedBy(publicKeyBase64: String?): Boolean {
    if (publicKeyBase64.isNullOrBlank() || signature.isBlank()) {
        return false
    }

    return runCatching {
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))

        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(signal.toByteArray())
            verify(Base64.getDecoder().decode(signature))
        }
    }.getOrDefault(false)
}
