package com.vivotomirpay.stub.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import androidx.annotation.WorkerThread
import androidx.compose.ui.graphics.ImageBitmap
import com.vivotomirpay.stub.ShizukuShortcuts
import com.vivotomirpay.stub.ShizukuShortcuts.Status
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Тип ярлыка для подписи в пикере. */
enum class ShortcutKind { Manifest, Dynamic, Pinned }

/** Метаданные без Bitmap: значок загружается отдельно для видимой строки. */
data class ShortcutEntry(
    val packageName: String,
    val id: String,
    val label: String,
    val kind: ShortcutKind,
    val rank: Int,
)

interface ShortcutGateway {
    /** Синхронная проверка Binder; вызывается только в фоновом потоке. */
    @WorkerThread
    fun status(): Status

    /** Вызывается в фоне после `PERMISSION_DENIED`, учитывает запрет повторного запроса. */
    @WorkerThread
    fun canRequestPermission(): Boolean = false

    suspend fun canOpenShizuku(): Boolean = false
    suspend fun shortcuts(packageName: String): List<ShortcutEntry>
    suspend fun icon(shortcut: ShortcutEntry): ImageBitmap? = null
}

/** Отправлен ли запрос, либо хосту уже можно завершить сценарий без ожидания callback. */
enum class ShortcutPermissionStart { WaitingForResult, AlreadyGranted, Rejected }

/**
 * Адаптер прежнего Shizuku backend без расширения прав. Экземпляр живёт в пределах маршрута
 * выбора ярлыка: метаданные `ShortcutInfo` нужны для ленивой загрузки значков его строк.
 */
class AndroidShortcutGateway(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ShortcutGateway {
    private val appContext = context.applicationContext
    private val infoByPackage = ConcurrentHashMap<String, Map<String, ShortcutInfo>>()

    @WorkerThread
    override fun status(): Status = ShizukuShortcuts.checkStatus()

    @WorkerThread
    override fun canRequestPermission(): Boolean =
        optionalPickerValue { !Shizuku.shouldShowRequestPermissionRationale() } == true

    override suspend fun canOpenShizuku(): Boolean = openShizukuIntent() != null

    /** Подготавливает прежний launch intent в фоне; открывает его только исполняющий хост. */
    suspend fun openShizukuIntent(): Intent? = withContext(dispatcher) {
        optionalPickerValue { appContext.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) }
    }

    /** После подтверждения команды хостом; listener уже должен быть привязан к этому requestCode. */
    suspend fun requestPermission(requestCode: Int): ShortcutPermissionStart = withContext(dispatcher) {
        when (status()) {
            Status.OK -> ShortcutPermissionStart.AlreadyGranted
            Status.PERMISSION_DENIED ->
                if (canRequestPermission() && optionalPickerValue { Shizuku.requestPermission(requestCode) } != null) {
                    ShortcutPermissionStart.WaitingForResult
                } else {
                    ShortcutPermissionStart.Rejected
                }
            else -> ShortcutPermissionStart.Rejected
        }
    }

    override suspend fun shortcuts(packageName: String): List<ShortcutEntry> = withContext(dispatcher) {
        val unique = LinkedHashMap<String, ShortcutInfo>()
        for (info in ShizukuShortcuts.getShortcuts(appContext, packageName)) {
            ensureActive()
            val id = info?.id ?: continue
            if (id.isNotBlank() && id !in unique) unique[id] = info
        }
        val entries = unique.map { (id, info) ->
            ensureActive()
            val label = optionalPickerValue { info.shortLabel?.toString() }?.takeIf(String::isNotBlank)
                ?: optionalPickerValue { info.longLabel?.toString() }?.takeIf(String::isNotBlank)
                ?: id
            val kind = when {
                info.isPinned -> ShortcutKind.Pinned
                info.isDynamic -> ShortcutKind.Dynamic
                else -> ShortcutKind.Manifest
            }
            ShortcutEntry(packageName, id, label, kind, info.rank)
        }.sortedWith(
            compareBy<ShortcutEntry> { it.rank }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy { it.label }
                .thenBy { it.id },
        )
        ensureActive()
        infoByPackage[packageName] = unique
        entries
    }

    override suspend fun icon(shortcut: ShortcutEntry): ImageBitmap? = withContext(dispatcher) {
        val info = infoByPackage[shortcut.packageName]?.get(shortcut.id) ?: return@withContext null
        optionalPickerValue { ShizukuShortcuts.getShortcutIcon(appContext, info)?.let(appContext::pickerIcon) }
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
