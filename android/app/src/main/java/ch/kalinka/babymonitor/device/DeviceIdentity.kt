package ch.kalinka.babymonitor.device

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This phone's long-term identity key, held in the Android keystore so the private half never
 * leaves the device — not even to this app, which can only ask the keystore to sign with it.
 *
 * The public half is what the other phone confirms by scanning a QR code, and what will sign the
 * DTLS fingerprint so a compromised backend cannot put itself in the middle of the stream.
 */
object DeviceIdentity {
    private const val Tag = "DeviceIdentity"
    private const val Alias = "babymonitor-identity"
    private const val KeyStoreType = "AndroidKeyStore"
    private const val SignatureAlgorithm = "SHA256withECDSA"

    /** Base64 X.509 public key, stable for the life of the install. */
    fun publicKey(): String {
        val entry = existingEntry() ?: generate()
        return Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    fun sign(payload: ByteArray): ByteArray {
        val entry = existingEntry() ?: generate()
        return Signature.getInstance(SignatureAlgorithm).run {
            initSign(entry.privateKey)
            update(payload)
            sign()
        }
    }

    /** What the phone calls itself, used as the device's default name. */
    fun defaultName(): String =
        listOf(Build.MANUFACTURER.replaceFirstChar(Char::uppercase), Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android phone" }

    private fun existingEntry(): KeyStore.PrivateKeyEntry? {
        val keyStore = KeyStore.getInstance(KeyStoreType).apply { load(null) }
        return keyStore.getEntry(Alias, null) as? KeyStore.PrivateKeyEntry
    }

    /**
     * Logged because it should happen exactly once in the life of an install. A second line here
     * means the keystore lost the key — which every other phone will see as the key changing.
     */
    private fun generate(): KeyStore.PrivateKeyEntry {
        Log.i(Tag, "Generating this phone's identity key")
        val spec = KeyGenParameterSpec
            .Builder(Alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KeyStoreType).run {
            initialize(spec)
            generateKeyPair()
        }

        return checkNotNull(existingEntry()) { "Keystore did not return the key it just generated." }
    }
}
