package ch.kalinka.babymonitor.net

import ch.kalinka.babymonitor.device.DeviceIdentity
import java.util.Base64

/**
 * Stamps a signal with the time and signs it with this phone's identity key.
 *
 * The keystore does the signing and will not hand the private half over, so this is the one place
 * a message becomes attributable to this phone — and the reason a backend relaying it cannot
 * substitute an offer of its own.
 *
 * Signing touches the keystore, so call it off the main thread; every caller is already on one.
 */
fun SignalMessage.signed(
    fromDeviceId: String,
    now: Long = System.currentTimeMillis()
): SignalMessage? {
    val stamped = copy(fromDeviceId = fromDeviceId, sentAt = now)

    val signature = runCatching {
        Base64.getEncoder().encodeToString(DeviceIdentity.sign(stamped.signedBytes()))
    }.getOrNull() ?: return null

    return stamped.copy(signature = signature)
}
