package ch.kalinka.babymonitor.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * The first eight characters of the signing certificate this copy of the app was signed with.
 *
 * The app is published in two places, and they are signed with different keys: the releases on
 * GitHub with the project's own, the F-Droid build with F-Droid's. Both are genuine and neither
 * can update the other — Android identifies an app by its signature, so an update from the wrong
 * one is refused rather than installed.
 *
 * Which is fine until somebody meets it. F-Droid lists an app it did not install as installed and
 * offers the update anyway, and what they get is a failure with no reason attached. This is the
 * reason, in a form that can be read off a screen and put in a mail: two people comparing builds
 * can see at a glance that they are not running the same one.
 */
fun signingKeyId(context: Context): String = runCatching {
    val packageManager = context.packageManager
    val name = context.packageName

    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(
            name,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    // The certificate in use now. A key rotated under v3 leaves the older ones in the history,
    // which is not what identifies this install.
    val signature = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching Unknown

    MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray())
        .take(4)
        .joinToString("") { "%02x".format(it) }
}.getOrDefault(Unknown)

private const val Unknown = "unsigned"
