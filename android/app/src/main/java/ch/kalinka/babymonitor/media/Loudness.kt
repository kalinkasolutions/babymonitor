package ch.kalinka.babymonitor.media

import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.sqrt

/** About sixty decibels below full scale is the quietest worth drawing. */
private const val QuietDecibels = 60.0

/**
 * The microphone input for both the call and the room check, so their meters read alike. Android
 * requires this one on every phone, with gain control and noise suppression off.
 */
const val RoomAudioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION

/** Every loudness reading covers this much sound, wherever it is measured. */
const val LoudnessWindowMillis = 200L

/** How loud a block of 16 bit samples is, from nought (silence) to one (full scale). */
fun loudnessOf(sumOfSquares: Double, count: Int): Float {
    if (count == 0) {
        return 0f
    }

    val rms = sqrt(sumOfSquares / count)
    val decibels = 20 * log10((rms / Short.MAX_VALUE).coerceAtLeast(1e-7))
    return ((decibels + QuietDecibels) / QuietDecibels).toFloat().coerceIn(0f, 1f)
}
