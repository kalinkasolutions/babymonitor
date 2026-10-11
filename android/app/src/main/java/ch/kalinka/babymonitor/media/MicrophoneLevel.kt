package ch.kalinka.babymonitor.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val SampleRate = 16_000
private const val SamplesPerReading = (SampleRate * LoudnessWindowMillis / 1000).toInt()

/**
 * How loud the room is right now, read from this phone's own microphone, about five times a
 * second. For checking where to put the phone before the night; a call measures its own, from the
 * same input over the same window, so the meter shows what the other phone will hear.
 */
fun microphoneLevel(): Flow<Float> = flow {
    val bufferSize = AudioRecord.getMinBufferSize(
        SampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(SamplesPerReading * 2)

    val recorder = openRecorder(bufferSize) ?: return@flow

    try {
        recorder.startRecording()
        val samples = ShortArray(SamplesPerReading)
        while (true) {
            val read = recorder.read(samples, 0, samples.size)
            if (read <= 0) {
                break
            }

            var sum = 0.0
            for (i in 0 until read) {
                sum += samples[i].toDouble() * samples[i]
            }
            emit(loudnessOf(sum, read))
        }
    } finally {
        runCatching { recorder.stop() }
        recorder.release()
    }
}.flowOn(Dispatchers.IO)

@SuppressLint("MissingPermission")
private fun openRecorder(bufferSize: Int): AudioRecord? {
    val recorder = AudioRecord(RoomAudioSource, SampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
    if (recorder.state == AudioRecord.STATE_INITIALIZED) {
        return recorder
    }
    recorder.release()
    return null
}
