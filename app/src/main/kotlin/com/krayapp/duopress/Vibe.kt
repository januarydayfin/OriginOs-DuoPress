package com.krayapp.duopress

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Виброотклик при перехвате двойного нажатия кнопки питания.
 * Настраивается в приложении: вкл/выкл, длительность импульса (20..500 мс)
 * и паттерн — один импульс, двойной или длинный.
 */
object Vibe {
    @JvmStatic
    fun trigger(context: Context?) {
        try {
            trigger(context, PrefsHelper.getVibrateDurationMs(context!!), PrefsHelper.getVibratePattern(context))
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun trigger(context: Context?, durationMs: Int, pattern: String?): Boolean {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context!!.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager?
                manager?.defaultVibrator
            } else {
                context!!.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
            }
            if (vibrator == null || !vibrator.hasVibrator()) {
                return true
            }

            val waveform = vibrationWaveform(durationMs, pattern)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(waveform, -1))
            } else {
                vibrator.vibrate(waveform, -1)
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }
}

/** Строит прежнюю последовательность импульсов и пауз для заданного паттерна. */
@JvmSynthetic
internal fun vibrationWaveform(durationMs: Int, pattern: String?): LongArray = when (pattern) {
    "double" -> longArrayOf(0, durationMs.toLong(), Math.max(60, durationMs).toLong(), durationMs.toLong())
    "long" -> longArrayOf(0, Math.min(600L, durationMs * 4L))
    else -> longArrayOf(0, durationMs.toLong())
}
