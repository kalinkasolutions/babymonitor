package ch.kalinka.babymonitor.net

import java.util.Base64

/**
 * The headers that let a phone use the relay. The signature proves which phone is asking, so
 * nobody can collect another phone's messages; the server key, when there is one, also unlocks the
 * home relay. [sign] is the identity key's signer, passed in so this can be tested without a keystore.
 */
fun serverAuthHeaders(
    serverKey: String,
    publicKey: String,
    now: Long,
    sign: (ByteArray) -> ByteArray
): Map<String, String> {
    val time = now.toString()
    val signed = mapOf(
        "X-Device-Key" to publicKey,
        "X-Device-Time" to time,
        "X-Device-Signature" to Base64.getEncoder().encodeToString(sign(deviceAuthBytes(time)))
    )
    return if (serverKey.isBlank()) signed else signed + (ServerKeyHeader to serverKey)
}

const val ServerKeyHeader = "X-Server-Key"

/** What the phone signs to connect. The backend rebuilds exactly these bytes to check it. */
fun deviceAuthBytes(time: String): ByteArray = "babymonitor-hub\n$time".toByteArray()
