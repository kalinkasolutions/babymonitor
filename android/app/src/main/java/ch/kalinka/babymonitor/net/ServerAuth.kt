package ch.kalinka.babymonitor.net

import java.util.Base64

/**
 * The headers that let a phone use the relay. The signature proves which phone is asking, so
 * nobody can collect another phone's messages. [sign] is the identity key's signer, passed in so
 * this can be tested without a keystore.
 */
fun serverAuthHeaders(
    publicKey: String,
    now: Long,
    sign: (ByteArray) -> ByteArray
): Map<String, String> {
    val time = now.toString()
    return mapOf(
        "X-Device-Key" to publicKey,
        "X-Device-Time" to time,
        "X-Device-Signature" to Base64.getEncoder().encodeToString(sign(deviceAuthBytes(time)))
    )
}

/** What the phone signs to connect. The backend rebuilds exactly these bytes to check it. */
fun deviceAuthBytes(time: String): ByteArray = "babymonitor-hub\n$time".toByteArray()
