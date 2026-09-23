package ch.kalinka.babymonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Matte Orchid — derived from #DF73FF, chroma reduced for a matte finish. Low saturation
// throughout: this app is looked at in a dark room in the middle of the night.

private val MatteOrchidLight = lightColorScheme(
    primary = Color(0xFF8B5AA6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEEDCF5),
    onPrimaryContainer = Color(0xFF341442),
    inversePrimary = Color(0xFFD2A8E4),

    secondary = Color(0xFF6B5C72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DFEA),
    onSecondaryContainer = Color(0xFF251A2A),

    tertiary = Color(0xFF5E7C6E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDE5D8),
    onTertiaryContainer = Color(0xFF17301F),

    error = Color(0xFFBA5A50),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF7DCD8),
    onErrorContainer = Color(0xFF3A100C),

    background = Color(0xFFF7F4F7),
    onBackground = Color(0xFF1D1A20),
    surface = Color(0xFFF7F4F7),
    onSurface = Color(0xFF1D1A20),
    surfaceVariant = Color(0xFFE7DFE8),
    onSurfaceVariant = Color(0xFF4A444D),
    surfaceTint = Color(0xFF8B5AA6),
    inverseSurface = Color(0xFF322E35),
    inverseOnSurface = Color(0xFFF5EFF5),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3EFF4),
    surfaceContainer = Color(0xFFEEE9F0),
    surfaceContainerHigh = Color(0xFFE8E3EB),
    surfaceContainerHighest = Color(0xFFE2DDE5),

    outline = Color(0xFF7B7480),
    outlineVariant = Color(0xFFCCC4CE),
    scrim = Color(0xFF000000)
)

private val MatteOrchidDark = darkColorScheme(
    primary = Color(0xFFD2A8E4),
    onPrimary = Color(0xFF40234C),
    primaryContainer = Color(0xFF5A3A69),
    onPrimaryContainer = Color(0xFFF0DBF9),
    inversePrimary = Color(0xFF7C4E93),

    secondary = Color(0xFFCFC0D4),
    onSecondary = Color(0xFF372F3C),
    secondaryContainer = Color(0xFF4E4553),
    onSecondaryContainer = Color(0xFFEBDCEF),

    tertiary = Color(0xFFA8C9B5),
    onTertiary = Color(0xFF1F3A2A),
    tertiaryContainer = Color(0xFF36523F),
    onTertiaryContainer = Color(0xFFC4E5D1),

    error = Color(0xFFDFA9A2),
    onError = Color(0xFF4A1F1A),
    errorContainer = Color(0xFF67332C),
    onErrorContainer = Color(0xFFF6DAD5),

    background = Color(0xFF141216),
    onBackground = Color(0xFFE6E0E8),
    surface = Color(0xFF141216),
    onSurface = Color(0xFFE6E0E8),
    surfaceVariant = Color(0xFF4A444D),
    onSurfaceVariant = Color(0xFFCCC4CE),
    surfaceTint = Color(0xFFD2A8E4),
    inverseSurface = Color(0xFFE6E0E8),
    inverseOnSurface = Color(0xFF322E35),

    surfaceContainerLowest = Color(0xFF0E0C10),
    surfaceContainerLow = Color(0xFF1C191E),
    surfaceContainer = Color(0xFF201D22),
    surfaceContainerHigh = Color(0xFF2B272D),
    surfaceContainerHighest = Color(0xFF363138),

    outline = Color(0xFF958E9A),
    outlineVariant = Color(0xFF4A444D),
    scrim = Color(0xFF000000)
)

/**
 * The app's colours. Fixed rather than taken from the wallpaper: Material You would repaint a
 * monitor that is read at a glance in the dark in whatever the phone's wallpaper suggests, and
 * the badges telling one trust level from another depend on being the colour they always are.
 */
@Composable
fun BabymonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(colorScheme = if (darkTheme) MatteOrchidDark else MatteOrchidLight) {
        // Compose has to paint its own background. Without this the window keeps whatever the
        // XML theme gave it, and a light colour scheme ends up drawing dark text onto it.
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

/**
 * The card colour for a phone that is not this one. The scheme cannot be asked which of the two
 * it is, and the two sides call for different steps off the background.
 */
@Composable
fun otherDeviceContainer(): Color = if (isSystemInDarkTheme()) {
    MaterialTheme.colorScheme.surfaceContainer
} else {
    MaterialTheme.colorScheme.surfaceContainerHigh
}
