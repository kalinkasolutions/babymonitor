package ch.kalinka.babymonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ch.kalinka.babymonitor.media.CallPath
import ch.kalinka.babymonitor.media.PhoneStatus

/**
 * How the phone in the room is doing, over the corner of its own picture — the same three things
 * it would show in its own status bar, because they are the three that say whether it will still
 * be there in the morning.
 *
 * Drawn rather than taken from an icon set: these are four rectangles and a rounded box, and the
 * extended Material icon pack is a large dependency to carry for them.
 */
@Composable
fun StatusOverlay(status: PhoneStatus, path: CallPath, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Scrim)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The route the call took, in the corner a phone keeps its connection state. Relayed is
        // tinted because it is the one worth noticing: everything still works, but through a
        // machine in the middle rather than between the two phones.
        if (path != CallPath.Unknown) {
            Canvas(modifier = Modifier.size(width = 20.dp, height = 12.dp)) {
                drawCallPath(
                    relayed = path == CallPath.Relayed,
                    overWifi = path == CallPath.Lan,
                    tint = if (path == CallPath.Relayed) Relayed else Color.White
                )
            }
            Spacer(Modifier.width(6.dp))
        }

        if (status.network != PhoneStatus.Network.None) {
            Canvas(modifier = Modifier.size(width = 14.dp, height = 12.dp)) {
                if (status.network == PhoneStatus.Network.Wifi) {
                    drawWifi(status.bars)
                } else {
                    drawBars(status.bars)
                }
            }
            Spacer(Modifier.width(6.dp))
        }

        status.batteryPercent?.let { percent ->
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
            Spacer(Modifier.width(4.dp))
        }

        // No battery reading yet means nothing has come down the data channel; drawing an empty
        // shell would be a claim about the other phone rather than an absence of one.
        if (status.batteryPercent != null) {
            Canvas(modifier = Modifier.size(width = 22.dp, height = 12.dp)) {
                drawBattery(status.batteryPercent, status.charging)
            }
        }
    }
}

/** Four bars, the ones above the strength lit. Unknown strength shows them all faint. */
private fun DrawScope.drawBars(bars: Int?) {
    val gap = size.width / 9f
    val width = (size.width - gap * 3) / 4f
    for (index in 0 until 4) {
        val height = size.height * (0.3f + 0.7f * (index + 1) / 4f)
        drawRect(
            color = if (bars == null || index < bars) Color.White else Dim,
            topLeft = Offset(index * (width + gap), size.height - height),
            size = Size(width, height)
        )
    }
}

/** Three arcs and a dot, lit up to the strength — the shape everybody already reads as WiFi. */
private fun DrawScope.drawWifi(bars: Int?) {
    val stroke = size.height / 7f
    val centre = Offset(size.width / 2f, size.height)
    for (index in 0 until 3) {
        val radius = size.height * (0.35f + index * 0.32f)
        val lit = bars == null || bars > index + 1
        drawArc(
            color = if (lit) Color.White else Dim,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(centre.x - radius, centre.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke)
        )
    }

    drawCircle(color = Color.White, radius = stroke, center = centre.copy(y = centre.y - stroke))
}

/** A box filled to the level, with a bolt across it while the charger is in. */
private fun DrawScope.drawBattery(percent: Int?, charging: Boolean) {
    val stroke = size.height / 8f
    val capWidth = size.width / 12f
    val bodyWidth = size.width - capWidth - stroke

    drawRoundRect(
        color = Color.White,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(bodyWidth, size.height - stroke),
        cornerRadius = CornerRadius(stroke * 1.5f),
        style = Stroke(width = stroke)
    )

    drawRoundRect(
        color = Color.White,
        topLeft = Offset(bodyWidth + stroke, size.height * 0.3f),
        size = Size(capWidth, size.height * 0.4f),
        cornerRadius = CornerRadius(stroke)
    )

    val level = (percent ?: 0).coerceIn(0, 100) / 100f
    val inset = stroke * 1.6f
    if (level > 0f) {
        drawRect(
            color = if (level <= 0.15f && !charging) Low else Color.White,
            topLeft = Offset(inset, inset),
            size = Size((bodyWidth - inset * 2) * level, size.height - inset * 2)
        )
    }

    if (charging) {
        // A bolt, drawn over whatever the level is: the one detail worth seeing at a glance from
        // across a dark room is whether the thing is still filling up.
        val bolt = Path().apply {
            moveTo(bodyWidth * 0.58f, size.height * 0.08f)
            lineTo(bodyWidth * 0.34f, size.height * 0.56f)
            lineTo(bodyWidth * 0.5f, size.height * 0.56f)
            lineTo(bodyWidth * 0.42f, size.height * 0.95f)
            lineTo(bodyWidth * 0.68f, size.height * 0.44f)
            lineTo(bodyWidth * 0.52f, size.height * 0.44f)
            close()
        }

        drawPath(path = bolt, color = Charge)
    }
}

private val Scrim = Color(0x99000000)
private val Relayed = Color(0xFFE3C86B)
private val Dim = Color(0x55FFFFFF)
private val Low = Color(0xFFDF6B60)
private val Charge = Color(0xFF7BDF8A)
