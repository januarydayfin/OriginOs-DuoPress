package com.vivotomirpay.stub;

import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

/**
 * Добавляет верхний системный inset к исходному отступу View.
 */
public final class SystemBarInsets {

    private SystemBarInsets() {
    }

    /**
     * Учитывает status bar и вырез экрана, сохраняя остальные отступы исходными.
     */
    public static void applyTopInset(final View view) {
        final int initialLeft = view.getPaddingLeft();
        final int initialTop = view.getPaddingTop();
        final int initialRight = view.getPaddingRight();
        final int initialBottom = view.getPaddingBottom();

        view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View target, WindowInsets insets) {
                int topInset = insets.getSystemWindowInsetTop();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        DisplayCutout cutout = insets.getDisplayCutout();
                        if (cutout != null) {
                            topInset = Math.max(topInset, cutout.getSafeInsetTop());
                        }
                    } catch (RuntimeException ignored) {
                        // Сохраняем базовый inset, если вендорская реализация cutout недоступна.
                    }
                }

                target.setPadding(
                        initialLeft,
                        initialTop + topInset,
                        initialRight,
                        initialBottom
                );
                return insets;
            }
        });
        view.requestApplyInsets();
    }
}
