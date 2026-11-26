package com.krayapp.duopress

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.IBinder
import android.os.Process
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Читает и запускает shortcuts других приложений через Shizuku, работающий от ADB shell.
 *
 * Privileged backend поддерживается на Android 11 (API 30) и новее: начиная с этой версии
 * shell имеет ACCESS_SHORTCUTS. Binder системного LauncherApps оборачивается в
 * [ShizukuBinderWrapper], поэтому вызовы выполняются с UID 2000. Root и Sui намеренно не
 * поддерживаются. Методы синхронные, не обращаются к UI и могут вызываться из фонового потока;
 * ошибки Binder и reflection передаются вызывающему.
 */
object ShizukuShortcuts {
    private const val SHELL_PACKAGE_NAME = "com.android.shell"
    private const val ILAUNCHER_APPS_CLASS = "android.content.pm.ILauncherApps"
    private const val ILAUNCHER_APPS_STUB_CLASS = "android.content.pm.ILauncherApps\$Stub"

    /** Результат проверки доступности Shizuku с требуемым ADB shell UID. */
    enum class Status {
        OK,
        UNAVAILABLE,
        PRE_V11,
        PERMISSION_DENIED,
        UNSUPPORTED_UID,
        UNSUPPORTED_API,
    }

    /**
     * Безопасно проверяет версию Android, binder, версию сервера, разрешение и UID. На Android 10
     * и ниже возвращает UNSUPPORTED_API до обращения к Shizuku. Любой сбой проверки считается
     * временной недоступностью Shizuku, поэтому метод не бросает исключения наружу.
     */
    @JvmStatic
    fun checkStatus(): Status {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return Status.UNSUPPORTED_API
            }
            if (!Shizuku.pingBinder()) {
                return Status.UNAVAILABLE
            }
            if (Shizuku.isPreV11()) {
                return Status.PRE_V11
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return Status.PERMISSION_DENIED
            }
            if (Shizuku.getUid() != Process.SHELL_UID) {
                return Status.UNSUPPORTED_UID
            }
            return Status.OK
        } catch (_: Throwable) {
            return Status.UNAVAILABLE
        }
    }

    /** Возвращает `true`, только если Shizuku доступен от ADB shell и разрешение выдано. */
    @JvmStatic
    fun isReady(): Boolean = checkStatus() == Status.OK

    /**
     * Возвращает включённые dynamic, pinned и manifest shortcuts указанного пакета для текущего
     * пользователя. В pinned-выборку включаются shortcuts, закреплённые любыми launcher-приложениями.
     */
    @JvmStatic
    @Throws(Exception::class)
    @SuppressLint("InlinedApi") // int-константа безопасно встраивается при компиляции.
    fun getShortcuts(context: Context?, packageName: String?): MutableList<ShortcutInfo?> {
        requireNonEmpty(packageName, "packageName")
        requireReady()

        val query = LauncherApps.ShortcutQuery()
        query.setPackage(packageName)
        query.setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER,
        )

        val shortcuts: List<ShortcutInfo?>? = createLauncherApps(context).getShortcuts(query, Process.myUserHandle())
        val result = ArrayList<ShortcutInfo?>()
        if (shortcuts != null) {
            for (shortcut in shortcuts) {
                if (shortcut != null && shortcut.isEnabled) {
                    result.add(shortcut)
                }
            }
        }
        return result
    }

    /** Загружает значок shortcut с плотностью экрана из ресурсов переданного контекста. */
    @JvmStatic
    @Throws(Exception::class)
    fun getShortcutIcon(context: Context?, shortcut: ShortcutInfo?): Drawable? {
        if (shortcut == null) {
            throw IllegalArgumentException("shortcut == null")
        }
        requireReady()

        val launcherApps = createLauncherApps(context)
        val densityDpi = context!!.resources.displayMetrics.densityDpi
        return launcherApps.getShortcutIconDrawable(shortcut, densityDpi)
    }

    /**
     * Запускает shortcut указанного пакета для текущего пользователя.
     *
     * На Android 14+ PendingIntent выполняется от имени publisher с явным разрешением background
     * activity start, чтобы холодный процесс поднимался даже после завершения прозрачной
     * SwipeEntryActivity. На Android 11–13 используется штатный LauncherApps.startShortcut.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun startShortcut(context: Context?, packageName: String?, shortcutId: String?) {
        requireNonEmpty(packageName, "packageName")
        requireNonEmpty(shortcutId, "shortcutId")
        requireReady()

        val launcherApps = createLauncherApps(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = launcherApps.getShortcutIntent(
                packageName!!, shortcutId!!, null, Process.myUserHandle(),
            )
            if (pendingIntent == null) {
                throw ActivityNotFoundException("Shortcut not found: $packageName/$shortcutId")
            }
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
            pendingIntent.send(options)
        } else {
            launcherApps.startShortcut(packageName!!, shortcutId!!, null, null, Process.myUserHandle())
        }
    }

    @JvmStatic
    private fun requireReady() {
        val status = checkStatus()
        if (status != Status.OK) {
            throw IllegalStateException(status.name)
        }
    }

    @JvmStatic
    private fun requireNonEmpty(value: String?, name: String) {
        // java.lang.String.trim() удаляет только символы с кодом <= U+0020.
        if (value == null || value.trim { it <= ' ' }.isEmpty()) {
            throw IllegalArgumentException("$name is empty")
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun createLauncherApps(context: Context?): LauncherApps {
        if (context == null) {
            throw IllegalArgumentException("context == null")
        }

        ensureLegacyHiddenApiExemptions()

        val binder = SystemServiceHelper.getSystemService(Context.LAUNCHER_APPS_SERVICE)
        if (binder == null) {
            throw IllegalStateException("Launcher apps service is unavailable")
        }
        val wrappedBinder = ShizukuBinderWrapper(binder)

        val serviceClass = Class.forName(ILAUNCHER_APPS_CLASS)
        val stubClass = Class.forName(ILAUNCHER_APPS_STUB_CLASS)
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        val service = asInterface.invoke(null, wrappedBinder)
        if (service == null) {
            throw IllegalStateException("ILauncherApps is unavailable")
        }

        val constructor = LauncherApps::class.java.getDeclaredConstructor(Context::class.java, serviceClass)
        constructor.isAccessible = true
        return constructor.newInstance(ShellContext(context), service)
    }

    /**
     * Передаёт в LauncherApps имя пакета ADB shell для системного verifyCallingPackage: фактический
     * Binder-вызов идёт от shell UID, поэтому имя нашего приложения не прошло бы эту проверку.
     * Все остальные операции и ресурсы делегируются исходному контексту приложения.
     */
    private class ShellContext(context: Context) : ContextWrapper(
        if (context.applicationContext != null) context.applicationContext else context,
    ) {
        override fun getPackageName(): String = SHELL_PACKAGE_NAME
    }
}
