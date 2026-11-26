package com.krayapp.duopress.platform

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import com.krayapp.duopress.ShizukuShortcuts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

internal class ShortcutServiceClient(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ShortcutUserService::class.java))
        .processNameSuffix("shortcuts")
        .daemon(false)
        .version(1)

    private val connection = ShortcutServiceConnection(::bind, { it.asBinder().isBinderAlive })

    suspend fun getShortcuts(packageName: String): List<ShortcutInfo> = withService { service ->
        service.listShortcuts(packageName)
    }

    suspend fun getShortcutIcon(shortcut: ShortcutInfo, densityDpi: Int, sizePx: Int): Bitmap? =
        withService { it.getShortcutIcon(shortcut, densityDpi, sizePx) }

    @Suppress("DEPRECATION") // На Android 14+ сохраняем прежний режим разрешения фонового запуска.
    suspend fun startShortcut(packageName: String, shortcutId: String) = withService { service ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // В фоне shell не может напрямую запустить Activity ярлыка, даже если RPC вернулся успешно.
            val pendingIntent = service.createLaunchIntent(packageName, shortcutId)
                ?: throw ActivityNotFoundException("Shortcut not found: $packageName/$shortcutId")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
                pendingIntent.send(options)
            } else {
                pendingIntent.send()
            }
        } else {
            service.startShortcut(packageName, shortcutId)
        }
    }

    private suspend fun <T> withService(operation: (IShortcutService) -> T): T = withContext(dispatcher) {
        val status = ShizukuShortcuts.checkStatus()
        check(status == ShizukuShortcuts.Status.OK) { status.name }
        val service = connection.awaitService()
        ensureActive()
        operation(service)
    }

    private fun bind(onConnected: (IShortcutService) -> Unit, onDisconnected: () -> Unit): () -> Unit {
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                onConnected(IShortcutService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                onDisconnected()
            }
        }
        val unbind: () -> Unit = { runCatching { Shizuku.unbindUserService(args, callback, false) }; Unit }
        try {
            Shizuku.bindUserService(args, callback)
        } catch (e: Exception) {
            unbind()
            throw e
        }
        return unbind
    }

    companion object {
        private var instance: ShortcutServiceClient? = null

        @Synchronized
        fun get(context: Context): ShortcutServiceClient =
            instance ?: ShortcutServiceClient(context.applicationContext).also { instance = it }
    }
}
