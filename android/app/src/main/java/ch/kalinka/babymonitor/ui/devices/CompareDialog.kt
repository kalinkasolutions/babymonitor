package ch.kalinka.babymonitor.ui.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.kalinka.babymonitor.device.KeyTrust
import ch.kalinka.babymonitor.net.DeviceDto

/**
 * The camera-free way to reach 2 of 2: both phones show this string, and the two people check it
 * is the same on both. No part of it goes through the server, which is the point.
 *
 * It is also the way back from a key that changed — a reinstall looks exactly like an attempt to
 * listen in, and comparing the new key is what tells the two apart.
 */
@Composable
fun CompareDialog(
    device: DeviceDto,
    trust: KeyTrust,
    safetyNumber: String,
    onMatches: () -> Unit,
    onDismiss: () -> Unit
) {
    val changed = trust == KeyTrust.Changed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (changed) "${device.name} has a new key" else "Compare with ${device.name}") },
        text = {
            Column {
                if (changed) {
                    Text(
                        "This phone confirmed a different key for ${device.name} before. " +
                            "Reinstalling the app does that — so does somebody getting in between. " +
                            "Compare the number before trusting it again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                }

                Text(
                    "Open this same screen on the other phone. If both show the number below, " +
                        "nobody is sitting in between and both phones can trust each other.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = safetyNumber,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Reading it out over the phone works just as well — the checking is what " +
                        "matters, not how the number got there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onMatches) { Text("They match") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not yet") } }
    )
}
