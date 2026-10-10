package ch.kalinka.babymonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ch.kalinka.babymonitor.nav.AppNavHost
import ch.kalinka.babymonitor.ui.theme.BabymonitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BabymonitorTheme {
                AppNavHost()
            }
        }
    }
}
