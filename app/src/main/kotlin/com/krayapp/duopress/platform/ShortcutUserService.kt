package com.krayapp.duopress.platform

import android.app.PendingIntent
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.UserHandle
import androidx.annotation.RequiresApi
import kotlin.system.exitProcess
import kotlin.math.roundToInt

/** Использует публичный LauncherApps от имени ADB shell, по запросу только из DuoPress. */
@RequiresApi(Build.VERSION_CODES.R)
class ShortcutUserService(private val context: Context) : IShortcutService.Stub() {
    private val launcherApps by lazy {
        check(Process.myUid() == Process.SHELL_UID) { "Shizuku must run as ADB shell" }
        val shellContext = try {
            context.createPackageContext("com.android.shell", 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw IllegalStateException("Android shell package is not available", e)
        }
        checkNotNull(shellContext.getSystemService(LauncherApps::class.java))
            .also { check(it.hasShortcutHostPermission()) { "Shell has no shortcut host permission" } }
    }

    override fun listShortcuts(packageName: String): List<ShortcutInfo> {
        val user = enforceAppCaller()
        val query = LauncherApps.ShortcutQuery().setPackage(packageName).setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER,
        )
        return launcherApps.getShortcuts(query, user).orEmpty().filter { it.isEnabled }
    }

    override fun createLaunchIntent(packageName: String, shortcutId: String): PendingIntent? {
        val user = enforceAppCaller()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) error("Shortcut PendingIntent requires Android 12")
        return launcherApps.getShortcutIntent(packageName, shortcutId, null, user)
    }

    override fun startShortcut(packageName: String, shortcutId: String) {
        val user = enforceAppCaller()
        check(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        launcherApps.startShortcut(packageName, shortcutId, null, null, user)
    }

    override fun getShortcutIcon(shortcut: ShortcutInfo, densityDpi: Int, sizePx: Int): Bitmap? {
        enforceAppCaller()
        return renderShortcutIcon(launcherApps.getShortcutIconDrawable(shortcut, densityDpi), sizePx)
    }

    override fun destroy() {
        check(Binder.getCallingUid() == Process.SHELL_UID)
        exitProcess(0)
    }

    private fun enforceAppCaller(): UserHandle =
        requireShortcutCaller(context.applicationInfo.uid, Binder.getCallingUid())
}

/**
 * Проверяет UID DuoPress и возвращает профиль приложения, а не процесса shell.
 *
 * @param appUid UID DuoPress из контекста, предоставленного Shizuku.
 * @param callingUid UID отправителя текущего Binder-вызова.
 * @return профиль, в котором установлена вызывающая копия DuoPress.
 */
internal fun requireShortcutCaller(appUid: Int, callingUid: Int): UserHandle {
    if (appUid != callingUid) throw SecurityException("Shortcut service only accepts calls from DuoPress")
    return UserHandle.getUserHandleForUid(appUid)
}

/** Ограничивает размер ответа Binder, сохраняя пропорции и не передавая hardware bitmap. */
internal fun renderShortcutIcon(icon: Drawable?, sizePx: Int): Bitmap? {
    if (icon == null) return null
    val size = sizePx.coerceIn(1, 128)
    val width = icon.intrinsicWidth.takeIf { it > 0 } ?: size
    val height = icon.intrinsicHeight.takeIf { it > 0 } ?: size
    val scale = size.toDouble() / maxOf(width, height)
    val outWidth = (width * scale).roundToInt().coerceIn(1, size)
    val outHeight = (height * scale).roundToInt().coerceIn(1, size)
    return Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also {
        icon.setBounds(0, 0, outWidth, outHeight)
        icon.draw(Canvas(it))
    }
}
