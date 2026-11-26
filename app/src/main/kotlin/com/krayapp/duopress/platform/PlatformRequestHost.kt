package com.krayapp.duopress.platform

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import com.krayapp.duopress.MenuActivity
import com.krayapp.duopress.SwipeEntryActivity
import com.krayapp.duopress.Vibe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

internal enum class PlatformOutcome { Launched, Failed, Granted, Denied }

internal interface ShortcutControl {
    suspend fun managerIntent(): Intent?
    suspend fun requestPermission(requestCode: Int, sendGate: PermissionSendGate): ShortcutPermissionStart

    /** Результат асинхронного запроса доставляется на Main. */
    @MainThread
    fun listen(onResult: (Int, Boolean) -> Unit): AutoCloseable
}

internal class AndroidShortcutControl(private val gateway: () -> AndroidShortcutGateway) : ShortcutControl {
    override suspend fun managerIntent(): Intent? = gateway().openShizukuIntent()
    override suspend fun requestPermission(requestCode: Int, sendGate: PermissionSendGate) =
        gateway().requestPermission(requestCode, sendGate)

    override fun listen(onResult: (Int, Boolean) -> Unit): AutoCloseable {
        val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
            onResult(code, result == PackageManager.PERMISSION_GRANTED)
        }
        Shizuku.addRequestPermissionResultListener(listener, Handler(Looper.getMainLooper()))
        return AutoCloseable { Shizuku.removeRequestPermissionResultListener(listener) }
    }
}

/**
 * Исполняет только что подтверждённые команды. Открывает Android Activity, но не передаёт
 * её в удерживаемую при пересоздании таблицу запросов.
 */
@MainThread
internal class PlatformRequestHost(
    private val activity: ComponentActivity,
    private val pending: PendingPlatformRequests,
) {
    suspend fun prepareManagerIntent(): Intent? = platformOrNull { pending.shortcutControl.managerIntent() }

    fun openShizukuListing(): Boolean = openShizukuStore(::start)

    fun execute(
        request: PlatformRequest,
        entryId: String,
        managerIntent: Intent? = null,
        onResult: (Long, PlatformOutcome) -> Unit,
    ) {
        val delivery = RequestDelivery(entryId, request.id, onResult)
        when (val command = request.command) {
            // A successful dispatch need not pause the Activity (e.g. multi-resume).
            // Each active screen re-probes separately on its own resume.
            PlatformCommand.ResolveHandler -> delivery.finish(
                start(
                    Intent(SwipeEntryActivity.ACTION_VIVO_SWIPE)
                        .addCategory(Intent.CATEGORY_DEFAULT)
                        .putExtra("lb_from", "2"),
                ).asLaunchOutcome(),
            )
            is PlatformCommand.OpenAppDetails -> delivery.finish(start(appDetails(command.packageName)).asLaunchOutcome())
            PlatformCommand.OpenAssistantSettings -> delivery.finish(openAssistantSettings().asLaunchOutcome())
            PlatformCommand.TestAction -> delivery.finish(
                start(
                    Intent(activity, SwipeEntryActivity::class.java)
                        .setAction(SwipeEntryActivity.ACTION_VIVO_SWIPE)
                        .putExtra("lb_from", "2"),
                ).asLaunchOutcome(),
            )
            is PlatformCommand.PreviewMenu -> delivery.finish(
                start(Intent(activity, MenuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putStringArrayListExtra(MenuActivity.EXTRA_PREVIEW_ITEMS, ArrayList(command.items)))
                    .asLaunchOutcome(),
            )
            is PlatformCommand.PreviewVibration -> delivery.finish(
                Vibe.trigger(activity, command.durationMs, command.pattern).asLaunchOutcome(),
            )
            PlatformCommand.OpenShizuku -> delivery.finish(
                (managerIntent != null && start(managerIntent)).asLaunchOutcome(),
            )
            PlatformCommand.RequestShortcutPermission -> pending.requestPermission(delivery)
        }
    }

    private fun openAssistantSettings(): Boolean {
        val actions = listOf(
            "android.settings.VOICE_INPUT_SETTINGS",
            "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
            "android.settings.MANAGE_ASSISTANT",
        )
        return actions.any { start(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ||
            start(appDetails("com.google.android.googlequicksearchbox").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun start(intent: Intent): Boolean = platformOrNull { activity.startActivity(intent) } != null
}

internal class RequestDelivery(
    val entryId: String,
    private val platformId: Long,
    private val onResult: (Long, PlatformOutcome) -> Unit,
) {
    fun finish(outcome: PlatformOutcome) = onResult(platformId, outcome)
}

/**
 * Принадлежит удерживаемой ViewModel launcher. Слушатель Shizuku живёт только пока есть
 * запросы; связывает код с ID и моделью записи, не с экземпляром Activity/NavBackStackEntry.
 */
@MainThread
internal class PendingPlatformRequests(
    private val scope: CoroutineScope,
    val shortcutControl: ShortcutControl,
) : AutoCloseable {
    private class Permission(val delivery: RequestDelivery) {
        val sendGate = PermissionSendGate()
        var job: Job? = null
    }

    private val permissions = mutableMapOf<Int, Permission>()
    private var subscription: AutoCloseable? = null

    fun requestPermission(delivery: RequestDelivery) {
        // Saturate rather than wrap: a delayed callback must never match a reused code.
        val previous = lastPermissionCode.getAndUpdate { if (it < Int.MAX_VALUE) it + 1 else it }
        if (previous == Int.MAX_VALUE) {
            delivery.finish(PlatformOutcome.Failed)
            return
        }
        val code = previous + 1
        val permission = Permission(delivery)
        permissions[code] = permission
        if (subscription == null) {
            subscription = platformOrNull { shortcutControl.listen(::onPermissionResult) }
            if (subscription == null) {
                finishPermission(code, PlatformOutcome.Failed)
                return
            }
        }
        permission.job = scope.launch {
            when (platformOrNull { shortcutControl.requestPermission(code, permission.sendGate) }) {
                ShortcutPermissionStart.WaitingForResult -> Unit
                ShortcutPermissionStart.AlreadyGranted -> finishPermission(code, PlatformOutcome.Granted)
                ShortcutPermissionStart.Rejected, null -> finishPermission(code, PlatformOutcome.Failed)
            }
        }
    }

    private fun onPermissionResult(code: Int, granted: Boolean) {
        finishPermission(code, if (granted) PlatformOutcome.Granted else PlatformOutcome.Denied)
    }

    private fun finishPermission(code: Int, outcome: PlatformOutcome) {
        val permission = permissions.remove(code) ?: return
        permission.sendGate.revoke()
        releaseListenerIfIdle()
        permission.job?.cancel()
        permission.delivery.finish(outcome)
    }

    fun revoke(entryId: String) {
        val iterator = permissions.values.iterator()
        while (iterator.hasNext()) {
            val permission = iterator.next()
            if (permission.delivery.entryId == entryId) {
                permission.sendGate.revoke()
                iterator.remove()
                permission.job?.cancel()
            }
        }
        releaseListenerIfIdle()
    }

    private fun releaseListenerIfIdle() {
        if (permissions.isNotEmpty()) return
        val listener = subscription ?: return
        subscription = null
        platformOrNull { listener.close() }
    }

    override fun close() {
        permissions.values.forEach { it.sendGate.revoke() }
        val dispatches = permissions.values.mapNotNull(Permission::job)
        permissions.clear()
        releaseListenerIfIdle()
        dispatches.forEach { it.cancel() }
    }

    private companion object {
        val lastPermissionCode = AtomicInteger()
    }
}

private fun appDetails(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null))

private fun Boolean.asLaunchOutcome() = if (this) PlatformOutcome.Launched else PlatformOutcome.Failed

private inline fun <T> platformOrNull(operation: () -> T): T? = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
