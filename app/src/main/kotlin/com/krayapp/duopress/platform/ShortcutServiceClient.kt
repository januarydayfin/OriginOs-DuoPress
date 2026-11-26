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
import android.os.Process
import com.krayapp.duopress.ShizukuShortcuts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Переиспользует Shizuku UserService в процессе DuoPress и выполняет операции с ярлыками в фоне.
 *
 * @param context контекст для имени пакета и компонента сервиса; клиент не удерживает Activity.
 * @param dispatcher диспетчер чтения и запуска ярлыков после подключения.
 * @param bindingDispatcher диспетчер callbacks Shizuku; сериализует операции регистрации на Main.
 */
internal class ShortcutServiceClient(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bindingDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ShortcutUserService::class.java))
        .tag("duopress-shortcuts-${Process.myUid()}")
        .processNameSuffix("shortcuts")
        .daemon(false)
        .version(1)

    private val connection = ShortcutServiceConnection(::bind, { it.asBinder().isBinderAlive })

    suspend fun getShortcuts(packageName: String): List<ShortcutInfo> = withService { service ->
        service.listShortcuts(packageName)
    }

    suspend fun getShortcutIcon(shortcut: ShortcutInfo, densityDpi: Int, sizePx: Int): Bitmap? =
        withService { it.getShortcutIcon(shortcut, densityDpi, sizePx) }

    /**
     * Отправляет запрос на запуск ярлыка, не дублируя его при отмене после подготовки.
     *
     * @param packageName пакет приложения-владельца ярлыка.
     * @param shortcutId сохранённый ID ярлыка в приложении-владельце.
     */
    @Suppress("DEPRECATION") // На Android 14+ сохраняем прежний режим разрешения фонового запуска.
    suspend fun startShortcut(packageName: String, shortcutId: String) = withService { service ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // В фоне shell не может напрямую запустить Activity ярлыка, даже если RPC вернулся успешно.
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            sendPreparedShortcut(
                prepare = {
                    service.createLaunchIntent(packageName, shortcutId)
                        ?: throw ActivityNotFoundException("Shortcut not found: $packageName/$shortcutId")
                },
                send = { pendingIntent ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) pendingIntent.send(options)
                    else pendingIntent.send()
                },
            )
        } else {
            service.startShortcut(packageName, shortcutId)
        }
    }

    private suspend fun <T> withService(operation: suspend (IShortcutService) -> T): T = withContext(dispatcher) {
        val status = ShizukuShortcuts.checkStatus()
        check(status == ShizukuShortcuts.Status.OK) { status.name }
        val service = connection.awaitService()
        ensureActive()
        operation(service)
    }

    private suspend fun bind(
        onConnected: (IShortcutService) -> Unit,
        onDisconnected: () -> Unit,
    ): suspend () -> Unit {
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                onConnected(IShortcutService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                onDisconnected()
            }
        }
        val unbind: suspend () -> Unit = {
            withContext(NonCancellable + bindingDispatcher) {
                // remove=true удаляет и ещё не подключившийся UserService, false снимает callback SDK.
                runCatching { Shizuku.unbindUserService(args, callback, true) }
                runCatching { Shizuku.unbindUserService(args, callback, false) }
                Unit
            }
        }
        try {
            // SDK обходит свои callbacks на Main без синхронизации: регистрация и снятие идут там же.
            withContext(bindingDispatcher) { Shizuku.bindUserService(args, callback) }
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

/**
 * Проверяет отмену после синхронного IPC, прежде чем отправить полученный запрос.
 *
 * @param prepare получение запроса от UserService.
 * @param send отправка запроса, если действие ещё актуально.
 */
internal suspend fun <T> sendPreparedShortcut(prepare: () -> T, send: (T) -> Unit) {
    val prepared = prepare()
    currentCoroutineContext().ensureActive()
    send(prepared)
}
