package com.wanderwildwood.shoka.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat

/**
 * What a scan sounds and feels like, so the screen needn't be watched: one beep for a new
 * book, two for one already here, a low buzz for a barcode that isn't a book's.
 */
class Feedback(context: Context) {
    private val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java)
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()

    fun good() {
        buzz(longArrayOf(0, 40))
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
    }

    fun again() {
        buzz(longArrayOf(0, 40, 90, 40))
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
    }

    fun bad() {
        buzz(longArrayOf(0, 300))
        tone?.startTone(ToneGenerator.TONE_PROP_NACK, 250)
    }

    private fun buzz(pattern: LongArray) {
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun release() {
        tone?.release()
    }
}
