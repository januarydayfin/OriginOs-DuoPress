package com.vivotomirpay.stub.platform

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vivotomirpay.stub.MenuActivity
import com.vivotomirpay.stub.SwipeEntryActivity
import com.vivotomirpay.stub.Vibe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

internal enum class PlatformOutcome { Launched, Failed, Granted, Denied }

internal interface ShortcutControl {
    suspend fun managerIntent(): Intent?
    suspend fun requestPermission(requestCode: Int): ShortcutPermissionStart

    /** Результат асинхронного запроса доставляется на Main. */
    @MainThread
    fun listen(onResult: (Int, Boolean) -> Unit): AutoCloseable
}

internal class AndroidShortcutControl(private val gateway: () -> AndroidShortcutGateway) : ShortcutControl {
    override suspend fun managerIntent(): Intent? = gateway().openShizukuIntent()
    override suspend fun requestPermission(requestCode: Int) = gateway().requestPermission(requestCode)

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
) : AutoCloseable {
    private val activityObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) = pending.onActivityPaused()
        override fun onResume(owner: LifecycleOwner) = pending.onActivityResumed()
    }

    init {
        activity.lifecycle.addObserver(activityObserver)
    }

    suspend fun prepareManagerIntent(): Intent? = platformOrNull { pending.shortcutControl.managerIntent() }

    fun execute(
        request: PlatformRequest,
        entryId: String,
        managerIntent: Intent? = null,
        onResult: (Long, PlatformOutcome) -> Unit,
    ) {
        val delivery = RequestDelivery(entryId, request.id, onResult)
        when (val command = request.command) {
            PlatformCommand.ResolveHandler -> startForReturn(delivery) {
                start(
                    Intent(SwipeEntryActivity.ACTION_VIVO_SWIPE)
                        .addCategory(Intent.CATEGORY_DEFAULT)
                        .putExtra("lb_from", "2"),
                )
            }
            is PlatformCommand.OpenAppDetails -> startForReturn(delivery) {
                start(appDetails(command.packageName))
            }
            PlatformCommand.OpenAssistantSettings -> startForReturn(delivery) { openAssistantSettings() }
            PlatformCommand.TestAction -> delivery.finish(
                start(
                    Intent(activity, SwipeEntryActivity::class.java)
                        .setAction(SwipeEntryActivity.ACTION_VIVO_SWIPE)
                        .putExtra("lb_from", "2"),
                ).asLaunchOutcome(),
            )
            PlatformCommand.PreviewMenu -> delivery.finish(
                start(Intent(activity, MenuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
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

    private inline fun startForReturn(delivery: RequestDelivery, launch: () -> Boolean) {
        if (!pending.awaitReturn(delivery)) {
            delivery.finish(PlatformOutcome.Failed)
        } else if (!launch()) {
            pending.failLaunch(delivery)
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

    override fun close() {
        activity.lifecycle.removeObserver(activityObserver)
    }
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
    private class Permission(val delivery: RequestDelivery, var job: Job? = null)
    private class Return(val delivery: RequestDelivery, var paused: Boolean = false)

    private val permissions = mutableMapOf<Int, Permission>()
    private var subscription: AutoCloseable? = null
    private var externalReturn: Return? = null

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
            when (platformOrNull { shortcutControl.requestPermission(code) }) {
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
        releaseListenerIfIdle()
        permission.job?.cancel()
        permission.delivery.finish(outcome)
    }

    fun awaitReturn(delivery: RequestDelivery): Boolean {
        if (externalReturn != null) return false
        externalReturn = Return(delivery)
        return true
    }

    fun failLaunch(delivery: RequestDelivery) {
        if (externalReturn?.delivery !== delivery) return
        externalReturn = null
        delivery.finish(PlatformOutcome.Failed)
    }

    fun onActivityPaused() {
        externalReturn?.paused = true
    }

    fun onActivityResumed() {
        val returned = externalReturn?.takeIf { it.paused } ?: return
        externalReturn = null
        returned.delivery.finish(PlatformOutcome.Launched)
    }

    fun revoke(entryId: String) {
        val iterator = permissions.values.iterator()
        while (iterator.hasNext()) {
            val permission = iterator.next()
            if (permission.delivery.entryId == entryId) {
                iterator.remove()
                permission.job?.cancel()
            }
        }
        releaseListenerIfIdle()
        if (externalReturn?.delivery?.entryId == entryId) externalReturn = null
    }

    private fun releaseListenerIfIdle() {
        if (permissions.isNotEmpty()) return
        val listener = subscription ?: return
        subscription = null
        platformOrNull { listener.close() }
    }

    override fun close() {
        val dispatches = permissions.values.mapNotNull(Permission::job)
        permissions.clear()
        externalReturn = null
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
