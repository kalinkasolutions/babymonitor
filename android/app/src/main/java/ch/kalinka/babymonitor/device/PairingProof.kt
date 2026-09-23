package ch.kalinka.babymonitor.device

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What lets one scan confirm both phones.
 *
 * The phone showing the code puts a random secret in the QR and keeps a copy. The phone that
 * scans it signs its own identity with that secret and sends the signature along with the claim.
 * The backend relays it without being able to check or forge it — the secret went from a screen
 * to a camera and was never uploaded — so a valid signature means the sender was physically
 * looking at that screen.
 *
 * The scanner already trusts what it read off the screen directly. This is the other direction.
 */
object PairingProof {
    private const val Algorithm = "HmacSHA256"
    private const val SecretBytes = 32

    // java.util.Base64 rather than android.util.Base64: available since API 26, well under this
    // app's minimum, and unlike the Android one it exists in plain JVM tests.
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    /** A fresh secret for one pairing attempt. Never leaves the two phones. */
    fun newSecret(): String {
        val bytes = ByteArray(SecretBytes)
        SecureRandom().nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun sign(secret: String, deviceId: String, publicKey: String): String =
        encoder.encodeToString(mac(secret, deviceId, publicKey))

    /**
     * Compared with [MessageDigest.isEqual], which does not stop at the first differing byte. A
     * timing leak here would let an attacker feel their way to a valid proof one byte at a time.
     */
    fun verify(secret: String, deviceId: String, publicKey: String, proof: String): Boolean {
        val offered = runCatching { decoder.decode(proof) }.getOrNull() ?: return false
        return MessageDigest.isEqual(mac(secret, deviceId, publicKey), offered)
    }

    // Both ends have to build the signed bytes identically; the separator keeps a device id
    // ending in key material from being confused with a shorter id and a longer key.
    private fun mac(secret: String, deviceId: String, publicKey: String): ByteArray =
        Mac.getInstance(Algorithm).apply {
            init(SecretKeySpec(secret.toByteArray(), Algorithm))
        }.doFinal("$deviceId\n$publicKey".toByteArray())
}
