package ch.lqy.babyphone.ui.settings

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.lqy.babyphone.device.ConnectionViewModel
import ch.lqy.babyphone.media.MonitorViewModel
import ch.lqy.babyphone.media.NoiseAlarmSettings

/**
 * How the app behaves. Who you are lives behind the avatar instead — the split Google's own apps
 * use, and it keeps "change my password" away from "how loud is the alarm".
 */
@Composable
fun SettingsScreen(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    viewModel: ConnectionViewModel = viewModel(),
    monitor: MonitorViewModel = viewModel()
) {
    var url by rememberSaveable { mutableStateOf(serverUrl) }
    val ice by viewModel.ice.collectAsState()
    val alarm by monitor.alarm.collectAsState()
    val relayOnly by monitor.relayOnly.collectAsState()

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
            Column(modifier = Modifier.weight(1f)) {
                Text("Wake me if the room stops answering", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "A phone that has died is silent in the same way a sleeping baby is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = alarm.warnOffline,
                onCheckedChange = { monitor.setAlarm(alarm.copy(warnOffline = it)) }
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Always use the relay", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "For testing only. Calls normally go straight between the phones and " +
                        "only fall back to the relay when they cannot; this forces the slow, " +
                        "expensive path so it can be seen working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = relayOnly,
                onCheckedChange = monitor::setRelayOnly
            )
        }

        Spacer(Modifier.height(24.dp))
        SettingsSection("Video")
        Pending("Start with video off")
        Pending("Light for a dark room: night light, screen or torch")

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

        Spacer(Modifier.height(24.dp))
        SettingsSection("How the phones would reach each other")
        val config = ice
        if (config == null) {
            Pending("Asking the server…")
        } else {
            Text(
                text = if (config.hasRelay) {
                    "A relay is available, for the times a direct connection cannot be made — " +
                        "mostly one phone on mobile data. It is never used on the same WiFi."
                } else {
                    "No relay configured. Direct connections only: same WiFi always works, and " +
                        "most connections across the internet do too, but the rest will fail."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            for (url in config.stunUrls + config.turnUrls) {
                Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (config.hasRelay) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Relay credential expires ${config.servers.expiresAt.take(16).replace('T', ' ')} UTC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
