package com.krayapp.duopress.platform;

import android.app.PendingIntent;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;

interface IShortcutService {
    List<ShortcutInfo> listShortcuts(String packageName) = 1;
    PendingIntent createLaunchIntent(String packageName, String shortcutId) = 2;
    void startShortcut(String packageName, String shortcutId) = 3;
    Bitmap getShortcutIcon(in ShortcutInfo shortcut, int densityDpi, int sizePx) = 4;
    void destroy() = 16777114;
}
