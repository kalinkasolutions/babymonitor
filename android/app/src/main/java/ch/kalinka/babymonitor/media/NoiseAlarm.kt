package ch.kalinka.babymonitor.media

import android.content.Context
import androidx.core.content.edit
import kotlin.math.pow

/**
 * When the room being watched counts as loud enough to be worth waking somebody for.
 *
 * Not one loudness and one duration, but a curve between them: the louder the room, the less of it
 * you need. A shriek is an emergency in two seconds; grizzling at the edge of audible has to keep
 * going for half a minute before it means anything. One threshold with one timer can only be set
 * to miss the first or cry wolf about the second.
 */
data class NoiseAlarmSettings(
    val enabled: Boolean = true,

    /** The quietest sound that ever counts, against the same meter the watching screen draws. */
    val threshold: Float = 0.4f,

    /** How long a sound sitting exactly on the threshold has to last. Everything louder is faster. */
    val seconds: Int = 25,

    /**
     * Whether to raise the alarm when the phone in the room stops answering. A monitor that has
     * quietly died looks exactly like a quiet room, which is the failure worth waking for.
     */
    val warnOffline: Boolean = true
) {
    /**
     * How long this loudness has to go on. Falls from [seconds] at the threshold to [Floor] at
     * full scale, geometrically — so each step louder cuts the wait by the same proportion rather
     * than by the same number of seconds.
     */
    fun secondsFor(level: Float): Float {
        if (level < threshold) {
            return Float.MAX_VALUE
        }

        val over = ((level - threshold) / (1f - threshold).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        return seconds * (Floor / seconds).toDouble().pow(over.toDouble()).toFloat()
    }

    companion object {
        /** However loud it gets, something has to last this long to be more than a door closing. */
        const val Floor = 2f
    }
}

/**
 * Decides when noise has gone on long enough to be an alarm.
 *
 * A bucket that fills while the room is loud and drains while it is not. What the curve changes is
 * only the rate: at the threshold it takes the full patience to fill, at a shriek a couple of
 * seconds. Draining is deliberately slower than filling, because a crying baby breathes, and an
 * alarm that resets in every gap is one that never goes off.
 */
data class NoiseWatch(
    val settings: NoiseAlarmSettings = NoiseAlarmSettings(),

    /** Nought to one: how much of the way to an alarm the room has got. */
    val progress: Float = 0f,
    val lastAt: Long = 0,
    val firedAt: Long = 0
) {
    /** The new state, and whether this is the moment to raise the alarm. */
    fun on(level: Float, now: Long): Pair<NoiseWatch, Boolean> {
        if (!settings.enabled) {
            return copy(progress = 0f, lastAt = now) to false
        }

        // A gap means the call was interrupted or the app was asleep, not that the room was quiet
        // for an hour, so time only counts in the sizes a running call produces.
        val elapsed = (now - lastAt).coerceIn(0, MaxStep) / 1000f
        val required = settings.secondsFor(level)
        val next = when {
            required == Float.MAX_VALUE -> progress - elapsed / (settings.seconds * DrainPatience)
            else -> progress + elapsed / required
        }.coerceIn(0f, 1f)

        // Never having fired is not the same as having fired at the epoch, which would put the
        // very first alarm of a session inside its own quiet period.
        val rested = firedAt == 0L || now - firedAt >= CooldownMillis
        return when {
            next >= 1f && rested -> copy(progress = 0f, lastAt = now, firedAt = now) to true
            else -> copy(progress = next, lastAt = now) to false
        }
    }

    private companion object {
        /** Quiet drains a full bucket in this many times the patience — slower than it filled. */
        const val DrainPatience = 2f

        /** Long enough that one crying fit is one alarm, not twenty. */
        const val CooldownMillis = 60_000L

        const val MaxStep = 2_000L
    }
}

/** Remembered on the phone that does the watching, because it is the one being woken. */
class AlarmSettings(context: Context) {
    private val prefs = context.getSharedPreferences("alarm", Context.MODE_PRIVATE)

    var current: NoiseAlarmSettings
        get() = NoiseAlarmSettings(
            enabled = prefs.getBoolean(Enabled, true),
            threshold = prefs.getFloat(Threshold, 0.4f),
            seconds = prefs.getInt(Seconds, 25),
            warnOffline = prefs.getBoolean(WarnOffline, true)
        )
        set(value) = prefs.edit {
            putBoolean(Enabled, value.enabled)
            putFloat(Threshold, value.threshold)
            putInt(Seconds, value.seconds)
            putBoolean(WarnOffline, value.warnOffline)
        }

    private companion object {
        const val Enabled = "enabled"
        const val Threshold = "threshold"
        const val Seconds = "seconds"
        const val WarnOffline = "warnOffline"
    }
}
