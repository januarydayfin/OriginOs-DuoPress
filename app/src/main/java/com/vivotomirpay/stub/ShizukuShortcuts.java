package com.vivotomirpay.stub;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Читает и запускает shortcuts других приложений через Shizuku, работающий от ADB shell.
 *
 * <p>Privileged backend поддерживается на Android 11 (API 30) и новее: начиная с этой версии
 * shell имеет ACCESS_SHORTCUTS. Binder системного LauncherApps оборачивается в
 * {@link ShizukuBinderWrapper}, поэтому вызовы выполняются с UID 2000. Root и Sui намеренно не
 * поддерживаются. Методы синхронные, не обращаются к UI и могут вызываться из фонового потока;
 * ошибки Binder и reflection передаются вызывающему.
 */
public final class ShizukuShortcuts {

    private static final String SHELL_PACKAGE_NAME = "com.android.shell";
    private static final String ILAUNCHER_APPS_CLASS = "android.content.pm.ILauncherApps";
    private static final String ILAUNCHER_APPS_STUB_CLASS = "android.content.pm.ILauncherApps$Stub";

    private ShizukuShortcuts() {
    }

    /** Результат проверки доступности Shizuku с требуемым ADB shell UID. */
    public enum Status {
        OK,
        UNAVAILABLE,
        PRE_V11,
        PERMISSION_DENIED,
        UNSUPPORTED_UID,
        UNSUPPORTED_API
    }

    /**
     * Безопасно проверяет версию Android, binder, версию сервера, разрешение и UID. На Android 10
     * и ниже возвращает UNSUPPORTED_API до обращения к Shizuku. Любой сбой проверки считается
     * временной недоступностью Shizuku, поэтому метод не бросает исключения наружу.
     */
    public static Status checkStatus() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return Status.UNSUPPORTED_API;
            }
            if (!Shizuku.pingBinder()) {
                return Status.UNAVAILABLE;
            }
            if (Shizuku.isPreV11()) {
                return Status.PRE_V11;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return Status.PERMISSION_DENIED;
            }
            if (Shizuku.getUid() != Process.SHELL_UID) {
                return Status.UNSUPPORTED_UID;
            }
            return Status.OK;
        } catch (Throwable ignored) {
            return Status.UNAVAILABLE;
        }
    }

    /** Возвращает {@code true}, только если Shizuku доступен от ADB shell и разрешение выдано. */
    public static boolean isReady() {
        return checkStatus() == Status.OK;
    }

    /**
     * Возвращает включённые dynamic, pinned и manifest shortcuts указанного пакета для текущего
     * пользователя. В pinned-выборку включаются shortcuts, закреплённые любыми launcher-приложениями.
     */
    @android.annotation.SuppressLint("InlinedApi") // int-константа безопасно встраивается при компиляции.
    public static List<ShortcutInfo> getShortcuts(Context context, String packageName)
            throws Exception {
        requireNonEmpty(packageName, "packageName");
        requireReady();

        LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
        query.setPackage(packageName);
        query.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER);

        List<ShortcutInfo> shortcuts = createLauncherApps(context)
                .getShortcuts(query, Process.myUserHandle());
        List<ShortcutInfo> result = new ArrayList<>();
        if (shortcuts != null) {
            for (ShortcutInfo shortcut : shortcuts) {
                if (shortcut != null && shortcut.isEnabled()) {
                    result.add(shortcut);
                }
            }
        }
        return result;
    }

    /** Загружает значок shortcut с плотностью экрана из ресурсов переданного контекста. */
    public static Drawable getShortcutIcon(Context context, ShortcutInfo shortcut) throws Exception {
        if (shortcut == null) {
            throw new IllegalArgumentException("shortcut == null");
        }
        requireReady();

        LauncherApps launcherApps = createLauncherApps(context);
        int densityDpi = context.getResources().getDisplayMetrics().densityDpi;
        return launcherApps.getShortcutIconDrawable(shortcut, densityDpi);
    }

    /**
     * Запускает shortcut указанного пакета для текущего пользователя.
     *
     * <p>На Android 14+ PendingIntent выполняется от имени publisher с явным разрешением background
     * activity start, чтобы холодный процесс поднимался даже после завершения прозрачной
     * SwipeEntryActivity. На Android 11–13 используется штатный LauncherApps.startShortcut.
     */
    public static void startShortcut(Context context, String packageName, String shortcutId)
            throws Exception {
        requireNonEmpty(packageName, "packageName");
        requireNonEmpty(shortcutId, "shortcutId");
        requireReady();

        LauncherApps launcherApps = createLauncherApps(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pendingIntent = launcherApps.getShortcutIntent(
                    packageName, shortcutId, null, Process.myUserHandle());
            if (pendingIntent == null) {
                throw new ActivityNotFoundException(
                        "Shortcut not found: " + packageName + "/" + shortcutId);
            }
            Bundle options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle();
            pendingIntent.send(options);
        } else {
            launcherApps.startShortcut(
                    packageName, shortcutId, null, null, Process.myUserHandle());
        }
    }

    private static void requireReady() {
        Status status = checkStatus();
        if (status != Status.OK) {
            throw new IllegalStateException(status.name());
        }
    }

    private static void requireNonEmpty(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is empty");
        }
    }

    private static LauncherApps createLauncherApps(Context context) throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }

        HiddenApiCompat.ensureExemptions();

        IBinder binder = SystemServiceHelper.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (binder == null) {
            throw new IllegalStateException("Launcher apps service is unavailable");
        }
        IBinder wrappedBinder = new ShizukuBinderWrapper(binder);

        Class<?> serviceClass = Class.forName(ILAUNCHER_APPS_CLASS);
        Class<?> stubClass = Class.forName(ILAUNCHER_APPS_STUB_CLASS);
        Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
        Object service = asInterface.invoke(null, wrappedBinder);
        if (service == null) {
            throw new IllegalStateException("ILauncherApps is unavailable");
        }

        Constructor<LauncherApps> constructor = LauncherApps.class.getDeclaredConstructor(
                Context.class, serviceClass);
        constructor.setAccessible(true);
        return constructor.newInstance(new ShellContext(context), service);
    }

    /**
     * Передаёт в LauncherApps имя пакета ADB shell для системного verifyCallingPackage: фактический
     * Binder-вызов идёт от shell UID, поэтому имя нашего приложения не прошло бы эту проверку.
     * Все остальные операции и ресурсы делегируются исходному контексту приложения.
     */
    private static final class ShellContext extends ContextWrapper {

        ShellContext(Context context) {
            super(context.getApplicationContext() != null ? context.getApplicationContext() : context);
        }

        @Override
        public String getPackageName() {
            return SHELL_PACKAGE_NAME;
        }
    }
}
