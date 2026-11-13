package com.fibstein.wakestop.alarm

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {
    val PRE_ALERT = longArrayOf(0, 400, 200, 400, 200, 400)
    val SIGNAL_LOST = longArrayOf(0, 200, 150, 200)

    fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun pulse(context: Context, pattern: LongArray) {
        val v = vibrator(context)
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
