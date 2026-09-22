package ch.lqy.babyphone.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The few shapes this app draws rather than imports. The extended Material icon set is a large
 * dependency to carry for a speaker and some sound waves.
 */
fun DrawScope.drawSpeaker(muted: Boolean, tint: Color = Color.White) {
    val stroke = size.height / 10f
    val bodyHeight = size.height * 0.42f
    val coneWidth = size.width * 0.34f

    // Box and cone: a rectangle with a triangle opening out of it.
    val speaker = Path().apply {
        moveTo(size.width * 0.06f, size.height / 2 - bodyHeight / 2)
        lineTo(size.width * 0.2f, size.height / 2 - bodyHeight / 2)
        lineTo(coneWidth + size.width * 0.06f, size.height * 0.18f)
        lineTo(coneWidth + size.width * 0.06f, size.height * 0.82f)
        lineTo(size.width * 0.2f, size.height / 2 + bodyHeight / 2)
        lineTo(size.width * 0.06f, size.height / 2 + bodyHeight / 2)
        close()
    }

    drawPath(path = speaker, color = tint)

    // Two arcs of sound, dropped while muted: the shape says silence before the line does.
    if (!muted) {
        for (index in 0 until 2) {
            val radius = size.height * (0.26f + index * 0.19f)
            drawArc(
                color = tint,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(size.width * 0.44f - radius, size.height / 2 - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke)
            )
        }
    } else {
        drawLine(
            color = tint,
            start = Offset(size.width * 0.12f, size.height * 0.86f),
            end = Offset(size.width * 0.9f, size.height * 0.14f),
            strokeWidth = stroke * 1.4f
        )
    }
}

/** A camera, struck through when it is not running. The same shape either way, so it reads fast. */
fun DrawScope.drawCamera(on: Boolean, tint: Color = Color.White) {
    val stroke = size.height / 10f
    val bodyHeight = size.height * 0.52f
    val bodyWidth = size.width * 0.64f
    val top = (size.height - bodyHeight) / 2

    drawRoundRect(
        color = tint,
        topLeft = Offset(size.width * 0.04f, top),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke * 1.5f)
    )

    // The lens barrel: a wedge off the side, the way every camera icon says camera.
    val lens = Path().apply {
        moveTo(size.width * 0.96f, top)
        lineTo(size.width * 0.7f, size.height / 2 - bodyHeight * 0.16f)
        lineTo(size.width * 0.7f, size.height / 2 + bodyHeight * 0.16f)
        lineTo(size.width * 0.96f, top + bodyHeight)
        close()
    }

    drawPath(path = lens, color = tint)

    if (!on) {
        drawLine(
            color = tint,
            start = Offset(size.width * 0.06f, size.height * 0.9f),
            end = Offset(size.width * 0.94f, size.height * 0.1f),
            strokeWidth = stroke * 1.4f
        )
    }
}

/**
 * How the two phones are reaching each other, drawn as the shape of the route itself: two ends
 * joined directly, or joined through something in the middle. The relay is the one worth
 * noticing, so it is the one that gets a colour.
 */
fun DrawScope.drawCallPath(relayed: Boolean, overWifi: Boolean, tint: Color = Color.White) {
    val dot = size.height / 7f
    val middle = size.height / 2

    drawCircle(color = tint, radius = dot, center = Offset(dot, middle))
    drawCircle(color = tint, radius = dot, center = Offset(size.width - dot, middle))
    drawLine(
        color = tint,
        start = Offset(dot * 2, middle),
        end = Offset(size.width - dot * 2, middle),
        strokeWidth = dot * 0.8f
    )

    if (relayed) {
        // Something in the middle of the line, which is exactly what a relay is.
        val box = size.height / 3.4f
        drawRect(
            color = tint,
            topLeft = Offset(size.width / 2 - box / 2, middle - box / 2),
            size = Size(box, box)
        )
    } else if (overWifi) {
        // Two arcs over the line: the same network, nothing between them.
        val stroke = size.height / 10f
        for (index in 0 until 2) {
            val radius = size.height * (0.24f + index * 0.18f)
            drawArc(
                color = tint,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(size.width / 2 - radius, middle - radius - dot),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke)
            )
        }
    }
}
