package ch.kalinka.babymonitor.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.kalinka.babymonitor.BuildConfig
import androidx.compose.runtime.remember
import ch.kalinka.babymonitor.device.LocalSession
import ch.kalinka.babymonitor.device.signingKeyId
import ch.kalinka.babymonitor.media.MonitorViewModel
import ch.kalinka.babymonitor.media.NoiseAlarmSettings
import ch.kalinka.babymonitor.ui.WithoutAccountCannot

/**
 * How the app behaves. Who you are lives behind the avatar instead — the split Google's own apps
 * use, and it keeps "change my password" away from "how loud is the alarm".
 */
@Composable
fun SettingsScreen(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onSignedOut: () -> Unit = {},
    monitor: MonitorViewModel = viewModel()
) {
    var url by rememberSaveable { mutableStateOf(serverUrl) }
    val context = LocalContext.current
    val alarm by monitor.alarm.collectAsState()
    val startWithVideoOff by monitor.startWithVideoOff.collectAsState()
    val local = remember { LocalSession(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        SettingsSection("Alerts")
        NoiseAlarmSettingsBlock(alarm, monitor::setAlarm)

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Wake me if the room stops answering",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = alarm.warnOffline,
                onCheckedChange = { monitor.setAlarm(alarm.copy(warnOffline = it)) }
            )
        }

        Spacer(Modifier.height(24.dp))
        SettingsSection("Video")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Start with video off",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = startWithVideoOff,
                onCheckedChange = monitor::setStartWithVideoOff
            )
        }

        Spacer(Modifier.height(24.dp))
        SettingsSection("Account")
        if (local.enabled) {
            Text(
                text = "Running without an account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (limit in WithoutAccountCannot) {
                Text(
                    text = "•  $limit",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { local.enabled = false; onSignedOut() }) {
                Text("Sign in with an account instead")
            }
        } else {
            Text(
                text = "Signed in. Your phones can reach each other from anywhere, and people can " +
                    "be invited by email.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
        SettingsSection("About")
        About()

        Spacer(Modifier.height(24.dp))
        SettingsSection("Connection")
        Text(
            text = "Where the backend lives. Only needed for pairing and for listening from " +
                "outside the house — on the same WiFi the phones talk directly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onServerUrlChange(url) },
            enabled = url.isNotBlank() && url != serverUrl,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save") }
        Pending("LAN only — never connect through the server")

    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
}

/** A setting that is designed but not built yet, shown so the shape of the screen is honest. */
@Composable
private fun Pending(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

/**
 * When to be woken, as the curve it actually is: loudness up the side, how long that loudness has
 * to last along the bottom. Two handles move it — where it starts, and how patient it is at that
 * quietest end — and the shape between them is what stops one setting having to serve both a
 * shriek and half an hour of grumbling.
 */
@Composable
private fun NoiseAlarmSettingsBlock(
    settings: NoiseAlarmSettings,
    onChange: (NoiseAlarmSettings) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Wake me when the room is loud",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = settings.enabled,
            onCheckedChange = { onChange(settings.copy(enabled = it)) }
        )
    }

    if (!settings.enabled) {
        return
    }

    Spacer(Modifier.height(12.dp))
    AlarmCurve(settings)
    Spacer(Modifier.height(12.dp))

    Text(
        text = "Ignore anything quieter than ${(settings.threshold * 100).toInt()}%",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = settings.threshold,
        onValueChange = { onChange(settings.copy(threshold = it)) },
        valueRange = 0.15f..0.8f
    )

    Text(
        text = "At that quietest level, wait ${settings.seconds} seconds",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = settings.seconds.toFloat(),
        onValueChange = { onChange(settings.copy(seconds = it.toInt())) },
        valueRange = 5f..60f
    )

    Text(
        text = "A shriek still counts after ${NoiseAlarmSettings.Floor.toInt()} seconds, however " +
            "these are set. Only raised when the sound is off or you are not looking — a room " +
            "you are already watching needs no notification about being loud.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** The curve itself, drawn from the same function the alarm uses, so it cannot describe a lie. */
@Composable
private fun AlarmCurve(settings: NoiseAlarmSettings) {
    val line = MaterialTheme.colorScheme.primary
    val ignored = MaterialTheme.colorScheme.surfaceVariant
    val axis = MaterialTheme.colorScheme.outlineVariant

    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val longest = settings.seconds.toFloat()

            // Everything under the threshold: no amount of time makes it an alarm.
            drawRect(
                color = ignored,
                topLeft = Offset(0f, size.height * (1f - settings.threshold)),
                size = Size(size.width, size.height * settings.threshold)
            )

            drawLine(
                color = axis,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 2f
            )

            // One point per loudness from the threshold up, placed at the time it needs.
            val curve = Path()
            var first = true
            var level = settings.threshold
            while (level <= 1f) {
                val seconds = settings.secondsFor(level).coerceAtMost(longest)
                val x = size.width * (seconds / longest)
                val y = size.height * (1f - level)
                if (first) {
                    curve.moveTo(x, y)
                    first = false
                } else {
                    curve.lineTo(x, y)
                }

                level += 0.02f
            }

            drawPath(path = curve, color = line, style = Stroke(width = 6f))
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "now",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${settings.seconds}s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Which build this is, and the two ways to say something about it. The version and the commit go
 * into the subject of the mail: a report that says which build it came from is worth several
 * that do not.
 *
 * The signing key is in there because the app is published in two places signed with two
 * different keys, and neither can update the other. When somebody writes in because an update
 * would not install, this is the line that says why.
 */
@Composable
private fun About() {
    val context = LocalContext.current
    val signer = remember(context) { signingKeyId(context) }
    val build = "${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT}) · $signer"

    Text(
        text = "Babymonitor $build",
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(8.dp))
    Text("Support", style = MaterialTheme.typography.bodyMedium)
    TextButton(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${BuildConfig.SUPPORT_EMAIL}"))
                        .putExtra(Intent.EXTRA_SUBJECT, "Babymonitor $build")
                )
            }
        }
    ) { Text(BuildConfig.SUPPORT_EMAIL) }

    Text("Source", style = MaterialTheme.typography.bodyMedium)
    TextButton(
        onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Repository)))
            }
        }
    ) { Text(Repository.removePrefix("https://")) }
}

/** Where the thing on this phone came from, and where it can be read. */
private const val Repository = "https://github.com/kalinkasolutions/babymonitor"
