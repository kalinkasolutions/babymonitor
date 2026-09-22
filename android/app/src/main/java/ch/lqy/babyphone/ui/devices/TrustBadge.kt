package ch.lqy.babyphone.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ch.lqy.babyphone.device.KeyTrust

/** The trust level, coloured so it reads before it is read, and the way in to fixing it. */
@Composable
fun TrustBadge(trust: KeyTrust, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (label, colors) = when (trust) {
        KeyTrust.Confirmed ->
            "2 of 2 — key confirmed in person" to (scheme.tertiaryContainer to scheme.onTertiaryContainer)

        KeyTrust.ServerVouched ->
            "1 of 2 — tap to compare keys" to (scheme.secondaryContainer to scheme.onSecondaryContainer)

        // The one badge that is a warning, and it borrows the colour warnings use everywhere else.
        KeyTrust.Changed ->
            "Key changed — tap to check" to (scheme.errorContainer to scheme.onErrorContainer)
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = colors.second,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.first)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
