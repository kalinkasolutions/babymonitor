package ch.kalinka.babymonitor.media

import kotlin.math.log10
import kotlin.math.sqrt

/** About sixty decibels below full scale is the quietest worth drawing. */
private const val QuietDecibels = 60.0

/** How loud a block of 16 bit samples is, from nought (silence) to one (full scale). */
fun loudnessOf(sumOfSquares: Double, count: Int): Float {
    if (count == 0) {
        return 0f
    }

    val rms = sqrt(sumOfSquares / count)
    val decibels = 20 * log10((rms / Short.MAX_VALUE).coerceAtLeast(1e-7))
    return ((decibels + QuietDecibels) / QuietDecibels).toFloat().coerceIn(0f, 1f)
}
