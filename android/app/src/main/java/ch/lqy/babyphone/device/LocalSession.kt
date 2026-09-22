package ch.lqy.babyphone.device

import android.content.Context
import androidx.core.content.edit
import ch.lqy.babyphone.net.DeviceDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Running with no account at all: two phones that know each other and a WiFi between them.
 *
 * Everything the backend was for has a local answer here. Identity is the key already in the
 * keystore; the device id is a number this phone made up; the list of other phones is whatever
 * has been scanned. What is lost is everything that has to cross the internet — an invitation by
 * email, a phone reachable from outside the house, recovery when a phone is lost — which is a
 * fair trade for a monitor that works in a house with no server in it.
 */
class LocalSession(context: Context) {
    private val prefs = context.getSharedPreferences("local", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Whether this phone is being used without an account. */
    var enabled: Boolean
        get() = prefs.getBoolean(Enabled, false)
        set(value) = prefs.edit { putBoolean(Enabled, value) }

    /**
     * This phone's own id. Made up once and kept, because the other phone remembers it and mDNS
     * announces it — a new one every launch would be a new phone every launch.
     */
    val deviceId: String
        get() = prefs.getString(DeviceId, null) ?: UUID.randomUUID().toString()
            .also { prefs.edit { putString(DeviceId, it) } }

    var name: String
        get() = prefs.getString(Name, null) ?: DeviceIdentity.defaultName()
        set(value) = prefs.edit { putString(Name, value.trim()) }

    /** The phones this one has scanned, as the rest of the app already expects to see them. */
    fun peers(): List<DeviceDto> = stored().map {
        DeviceDto(
            id = it.id,
            name = it.name,
            isMine = true,
            ownerId = Owner,
            ownerName = "This household",
            publicKey = it.publicKey,
            keyFingerprint = fingerprint(it.publicKey)
        )
    }

    /** Remembers a phone that was scanned, or updates the one already there. */
    fun remember(id: String, name: String, publicKey: String) {
        val kept = stored().filterNot { it.id == id } + Peer(id, name, publicKey)
        prefs.edit { putString(Peers, json.encodeToString(kept)) }
    }

    fun forget(id: String) {
        prefs.edit { putString(Peers, json.encodeToString(stored().filterNot { it.id == id })) }
    }

    private fun stored(): List<Peer> =
        runCatching { json.decodeFromString<List<Peer>>(prefs.getString(Peers, "[]").orEmpty()) }
            .getOrDefault(emptyList())

    /** The same shape the backend produces, so one list can hold phones from either. */
    private fun fingerprint(publicKey: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(publicKey.toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }

        return (0 until 3).joinToString(" ") { digest.substring(it * 4, (it + 1) * 4) }
    }

    @Serializable
    private data class Peer(val id: String, val name: String, val publicKey: String)

    private companion object {
        const val Enabled = "enabled"
        const val DeviceId = "deviceId"
        const val Name = "name"
        const val Peers = "peers"

        /** One household, so every phone in the list belongs to it. */
        const val Owner = "local"
    }
}
