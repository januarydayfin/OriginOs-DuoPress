package com.vivotomirpay.stub.platform

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Области подбора приложений для действия и для ярлыка. */
enum class AppScope { Installed, Launcher }

data class AppEntry(val packageName: String, val label: String)

interface AppCatalog {
    /** Возвращает отсортированный каталог без загрузки значков. Безопасно вызывать с Main. */
    suspend fun apps(scope: AppScope): List<AppEntry>

    /** Загружает значок конкретной строки; `null` означает, что нужен запасной значок. */
    suspend fun icon(packageName: String): ImageBitmap?
}

/** Сохраняет общий набор MAIN/LAUNCHER прежних двух пикеров; остальные пакеты вводятся вручную. */
class AndroidAppCatalog(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppCatalog {
    private val appContext = context.applicationContext

    override suspend fun apps(scope: AppScope): List<AppEntry> = withContext(dispatcher) {
        val pm = appContext.packageManager
        val ownPackage = appContext.packageName
        val seen = mutableSetOf<String>()
        val entries = pm.queryIntentActivities(launcherIntent(), 0).mapNotNull { activity ->
            ensureActive()
            val packageName = activity.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == ownPackage || !seen.add(packageName)) return@mapNotNull null
            val label = optionalPickerValue { activity.loadLabel(pm)?.toString() }
            AppEntry(packageName, label?.takeIf(String::isNotBlank) ?: packageName)
        }
        when (scope) {
            AppScope.Installed -> entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            AppScope.Launcher -> entries.sortedWith(
                compareBy<AppEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                    .thenBy { it.label }
                    .thenBy { it.packageName },
            )
        }
    }

    override suspend fun icon(packageName: String): ImageBitmap? = withContext(dispatcher) {
        optionalPickerValue {
            val pm = appContext.packageManager
            pm.queryIntentActivities(launcherIntent().setPackage(packageName), 0)
                .firstOrNull()
                ?.loadIcon(pm)
                ?.let(appContext::pickerIcon)
        }
    }

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
}

internal fun Context.pickerIcon(drawable: Drawable): ImageBitmap {
    val size = (32 * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    return drawable.toBitmap(size, size).asImageBitmap()
}

internal inline fun <T> optionalPickerValue(read: () -> T): T? = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
