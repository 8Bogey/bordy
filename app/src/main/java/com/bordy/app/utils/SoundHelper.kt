package com.bordy.app.utils

import android.media.AudioManager
import android.media.ToneGenerator

object SoundHelper {
    private val toneGenerator: ToneGenerator? by lazy {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 75)
        } catch (e: Exception) {
            null
        }
    }

    /** صوت إضافة ناجحة - "بِيب" إيجابي قصير مميز */
    fun playAddSuccess() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 70)
        } catch (_: Exception) { }
    }

    /** صوت تحذير - "بِيب" سلبي عند نقص المخزون */
    fun playWarning() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 180)
        } catch (_: Exception) { }
    }

    /** صوت لمسة خفيفة - للإجراءات السريعة */
    fun playTap() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 30)
        } catch (_: Exception) { }
    }
}