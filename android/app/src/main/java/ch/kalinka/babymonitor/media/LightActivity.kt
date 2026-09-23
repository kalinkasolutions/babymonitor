package ch.kalinka.babymonitor.media

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ch.kalinka.babymonitor.net.LightMode
import kotlinx.coroutines.delay

/**
 * The light. Phones have no infrared, so the only way to see a dark room is to light it, and the
 * only lamp this phone has is its own screen.
 *
 * An activity rather than anything smaller because only an activity can turn a locked phone's
 * screen on and show over the keyguard. It shows nothing but a colour, dismisses on a tap, and in
 * [LightMode.Glance] puts itself out after a few seconds so a look at the room does not become a
 * light left on in it.
 */
class LightActivity : ComponentActivity() {
    /** What is on screen now. Held as state so a slider being dragged only updates it. */
    private var spec by mutableStateOf(LightSpec())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = this
        showOverLockedScreen()
        apply(LightSpec.of(intent))

        setContent {
            val current = spec
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colourFor(current.mode, current.brightness))
                    .clickable { finish() }
            )

            // Zero seconds stays on. A glance is the same light with an end to it.
            if (current.seconds > 0) {
                LaunchedEffect(current) {
                    delay(current.seconds * 1000L)
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        if (showing === this) {
            showing = null
        }

        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        apply(LightSpec.of(intent))
    }

    /** Changes what the screen is doing without rebuilding anything. */
    fun apply(next: LightSpec) {
        if (next.mode == LightMode.Off) {
            finish()
            return
        }

        // Straight onto the window: this is the phone's own screen brightness, which is the only
        // lamp it has.
        window.attributes = window.attributes.apply { screenBrightness = next.brightness }
        spec = next
    }

    /**
     * Below about a fifth, the backlight is as low as it goes and the only way further down is to
     * darken the colour itself — which is what makes a night light dim enough to be worth having.
     */
    private fun colourFor(mode: LightMode, brightness: Float): Color {
        val floor = (brightness / LowLightFloor).coerceIn(MinimumInk, 1f)
        return when (mode) {
            LightMode.Red -> Color(red = floor, green = 0f, blue = 0f)
            else -> Color(red = floor, green = floor, blue = floor)
        }
    }

    private fun showOverLockedScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Wakes the screen without unlocking anything: the keyguard stays where it is, with this
        // over it. Nobody has to be trusted with the phone to be trusted with the light.
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
    }

    companion object {
        private const val ExtraMode = "mode"
        private const val ExtraBrightness = "brightness"
        private const val ExtraSeconds = "seconds"

        /**
         * The one on screen, so it can be put out from anywhere. Held only between onCreate and
         * onDestroy: the thing that has to be turned off is precisely this instance.
         */
        @Volatile
        private var showing: LightActivity? = null

        private const val LowLightFloor = 0.2f

        /** Never quite black: an unlit screen is indistinguishable from a broken one. */
        private const val MinimumInk = 0.06f

        fun intent(context: Context, mode: LightMode, brightness: Float, seconds: Int): Intent =
            Intent(context, LightActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ExtraMode, mode.name)
                .putExtra(ExtraBrightness, brightness)
                .putExtra(ExtraSeconds, seconds)

        fun isShowing(): Boolean = showing != null

        /**
         * Retunes the light already on screen. The route that got it there — an alarm, a
         * notification, a permission — is only needed to raise it the first time; a slider being
         * dragged must not go back through any of that.
         */
        fun update(mode: LightMode, brightness: Float, seconds: Int): Boolean {
            val activity = showing ?: return false
            activity.runOnUiThread { activity.apply(LightSpec(mode, brightness, seconds)) }
            return true
        }

        /** Silently refused when the phone is locked, which is what the other routes are for. */
        fun show(context: Context, mode: LightMode, brightness: Float, seconds: Int) {
            runCatching { context.startActivity(intent(context, mode, brightness, seconds)) }
        }

        fun dismiss(@Suppress("UNUSED_PARAMETER") context: Context) {
            showing?.let { it.runOnUiThread(it::finish) }
        }
    }
}

/** One setting of the light: what colour, how bright, and whether it ends by itself. */
data class LightSpec(
    val mode: LightMode = LightMode.White,
    val brightness: Float = 1f,
    val seconds: Int = 0
) {
    companion object {
        fun of(intent: Intent): LightSpec = LightSpec(
            mode = runCatching { LightMode.valueOf(intent.getStringExtra("mode").orEmpty()) }
                .getOrDefault(LightMode.White),
            brightness = intent.getFloatExtra("brightness", 1f).coerceIn(0f, 1f),
            seconds = intent.getIntExtra("seconds", 0)
        )
    }
}
