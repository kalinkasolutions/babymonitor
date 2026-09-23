package ch.kalinka.babymonitor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * How loud the room is, as a column that fills from the bottom.
 *
 * The point of it is watching a room you are not listening to: with the sound off this is the only
 * thing that says whether anything is happening in there. Measured on the phone in the room, so it
 * keeps working however this end has its volume set.
 */
@Composable
fun NoiseMeter(level: Float, threshold: Float, arming: Float, modifier: Modifier = Modifier) {
    // Eased here as well as at the source, so a burst rises at once and settles gently rather
    // than flickering between two frames of a voice.
    val shown by animateFloatAsState(targetValue = level.coerceIn(0f, 1f), label = "noise")

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Scrim)
            .padding(4.dp)
            .size(width = 16.dp, height = 96.dp)
    ) {
        // Two columns: how loud it is now, and how close that has brought it to an alarm. The
        // second is the one that explains why nothing happened, or why something did.
        val barWidth = size.width * 0.55f
        val radius = CornerRadius(barWidth / 2)
        drawRoundRect(color = Track, size = Size(barWidth, size.height), cornerRadius = radius)

        // The line the noise has to stay above to become an alarm. Drawn on the meter itself,
        // because a threshold you cannot see against the thing it measures is just a number.
        if (threshold in 0.01f..0.99f) {
            val y = size.height * (1f - threshold)
            drawLine(
                color = Mark,
                start = Offset(0f, y),
                end = Offset(barWidth, y),
                strokeWidth = barWidth / 5f
            )
        }

        if (shown > 0f) {
            val height = size.height * shown
            drawRoundRect(
                color = colourFor(shown),
                topLeft = Offset(0f, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = radius
            )
        }

        val armWidth = size.width * 0.25f
        val armLeft = size.width - armWidth
        val armRadius = CornerRadius(armWidth / 2)
        drawRoundRect(
            color = Track,
            topLeft = Offset(armLeft, 0f),
            size = Size(armWidth, size.height),
            cornerRadius = armRadius
        )

        if (arming > 0f) {
            val height = size.height * arming.coerceIn(0f, 1f)
            drawRoundRect(
                color = Arming,
                topLeft = Offset(armLeft, size.height - height),
                size = Size(armWidth, height),
                cornerRadius = armRadius
            )
        }
    }
}

/** Quiet is the normal state and draws calm; loud is the thing worth looking up for. */
private fun colourFor(level: Float): Color = when {
    level > 0.75f -> Loud
    level > 0.45f -> Stirring
    else -> Calm
}

private val Scrim = Color(0x99000000)
private val Track = Color(0x33FFFFFF)
private val Calm = Color(0xFF7BDF8A)
private val Stirring = Color(0xFFE3C86B)
private val Loud = Color(0xFFDF6B60)
private val Mark = Color(0xCCFFFFFF)
private val Arming = Color(0xFFD2A8E4)
