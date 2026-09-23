package ch.kalinka.babymonitor.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import ch.kalinka.babymonitor.nav.Destination as Dest

/**
 * The bottom bar, in order. Icons come from the core set rather than material-icons-extended,
 * which is deprecated and would add thousands of unused glyphs for the sake of three.
 */
enum class Tab(val label: String, val icon: ImageVector, val destination: Any) {
    Monitor("Monitor", Icons.Filled.Notifications, Dest.Monitor),
    Devices("Devices", Icons.Filled.Phone, Dest.Devices),
    Settings("Settings", Icons.Filled.Settings, Dest.Settings)
}
