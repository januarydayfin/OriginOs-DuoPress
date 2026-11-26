package com.krayapp.duopress

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import androidx.annotation.WorkerThread
import com.krayapp.duopress.platform.ShortcutServiceClient
import rikka.shizuku.Shizuku

/** Проверка доступа и операции с ярлыками через UserService от имени ADB shell. */
object ShizukuShortcuts {
    enum class Status {
        OK,
        UNAVAILABLE,
        PRE_V13,
        PERMISSION_DENIED,
        UNSUPPORTED_UID,
        UNSUPPORTED_API,
    }

    /** Проверяет совместимость и разрешение до первого запроса к UserService. */
    @WorkerThread
    @JvmStatic
    fun checkStatus(): Status = try {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> Status.UNSUPPORTED_API
            !Shizuku.pingBinder() -> Status.UNAVAILABLE
            Shizuku.isPreV11() || Shizuku.getVersion() < 13 -> Status.PRE_V13
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.PERMISSION_DENIED
            Shizuku.getUid() != Process.SHELL_UID -> Status.UNSUPPORTED_UID
            else -> Status.OK
        }
    } catch (_: Exception) {
        Status.UNAVAILABLE
    } catch (_: LinkageError) {
        Status.UNAVAILABLE
    }

    internal suspend fun getShortcuts(context: Context?, packageName: String?): List<ShortcutInfo> {
        val pkg = requireNonEmpty(packageName, "packageName")
        return ShortcutServiceClient.get(requireNotNull(context)).getShortcuts(pkg)
    }

    internal suspend fun getShortcutIcon(
        context: Context?,
        shortcut: ShortcutInfo?,
        densityDpi: Int,
        sizePx: Int,
    ): Bitmap? {
        requireNotNull(shortcut) { "shortcut == null" }
        return ShortcutServiceClient.get(requireNotNull(context)).getShortcutIcon(shortcut, densityDpi, sizePx)
    }

    internal suspend fun startShortcut(context: Context?, packageName: String?, shortcutId: String?) {
        val pkg = requireNonEmpty(packageName, "packageName")
        val id = requireNonEmpty(shortcutId, "shortcutId")
        ShortcutServiceClient.get(requireNotNull(context)).startShortcut(pkg, id)
    }

    private fun requireNonEmpty(value: String?, name: String): String {
        require(value != null && value.trim { it <= ' ' }.isNotEmpty()) { "$name is empty" }
        return value
    }
}
