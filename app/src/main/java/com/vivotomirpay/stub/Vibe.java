package com.vivotomirpay.stub;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Виброотклик при перехвате двойного нажатия кнопки питания.
 * Настраивается в приложении: вкл/выкл, длительность импульса (20..500 мс)
 * и паттерн — один импульс, двойной или длинный.
 */
public final class Vibe {

    private Vibe() {
    }

    public static void trigger(Context context) {
        try {
            trigger(context, PrefsHelper.getVibrateDurationMs(context), PrefsHelper.getVibratePattern(context));
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    public static boolean trigger(Context context, int duration, String pattern) {
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.os.VibratorManager vm =
                        (android.os.VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) {
                return true;
            }

            long[] waveform;
            if ("double".equals(pattern)) {
                // Импульс — пауза — импульс.
                long pause = Math.max(60, duration);
                waveform = new long[]{0, duration, pause, duration};
            } else if ("long".equals(pattern)) {
                waveform = new long[]{0, Math.min(600, duration * 4L)};
            } else {
                waveform = new long[]{0, duration};
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(waveform, -1));
            } else {
                v.vibrate(waveform, -1);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
