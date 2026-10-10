package ch.kalinka.babymonitor.device

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64

/** Another phone this one has paired with by scanning. Its key was read off its screen. */
@Serializable
data class Phone(val id: String, val name: String, val publicKey: String)

/**
 * This phone and the phones it has paired with. Kept on the phone only: the list is what decides
 * who may open the microphone, so nothing outside the phone gets to edit it.
 */
class Household(context: Context) {
    private val prefs = context.getSharedPreferences("household", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val deviceId: String by lazy { deviceIdOf(DeviceIdentity.publicKey()) }

    val name: String
        get() = DeviceIdentity.defaultName()

    fun peers(): List<Phone> =
        runCatching { json.decodeFromString<List<Phone>>(prefs.getString(Peers, "[]").orEmpty()) }
            .getOrDefault(emptyList())

    /** The key confirmed for that phone, or null when it was never paired. */
    fun keyOf(deviceId: String): String? = peers().firstOrNull { it.id == deviceId }?.publicKey

    fun remember(phone: Phone) {
        val kept = peers().filterNot { it.id == phone.id } + phone
        prefs.edit { putString(Peers, json.encodeToString(kept)) }
    }

    fun forget(deviceId: String) {
        prefs.edit { putString(Peers, json.encodeToString(peers().filterNot { it.id == deviceId })) }
    }

    private companion object {
        const val Peers = "peers"
    }
}

/**
 * A phone's id is a digest of its public key, so the server can check who is who without keeping
 * a list. The backend derives it the same way: the first 16 bytes of SHA-256 over the DER key.
 */
fun deviceIdOf(publicKey: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(Base64.getDecoder().decode(publicKey))
        .take(16)
        .joinToString("") { "%02x".format(it) }

/** 96 bits of the key in groups of four, short enough for two people to compare. */
fun fingerprintOf(publicKey: String): String {
    val hex = MessageDigest.getInstance("SHA-256")
        .digest(publicKey.toByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }

    return (0 until hex.length / 4).joinToString(" ") { hex.substring(it * 4, (it + 1) * 4) }
}
