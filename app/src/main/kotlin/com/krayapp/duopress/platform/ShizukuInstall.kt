package com.krayapp.duopress.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/**
 * Проверяет наличие пакета, не обращаясь к сервису Shizuku и не проверяя разрешение.
 *
 * @param context контекст приложения для доступа к диспетчеру пакетов.
 * @param dispatcher фоновый диспетчер для вызова системы.
 * @return установлен ли Shizuku для текущего пользователя.
 */
@Suppress("DEPRECATION") // Перегрузка с Int доступна на всех поддерживаемых версиях Android.
internal suspend fun isShizukuInstalled(
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean = withContext(dispatcher) {
    try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * Сначала направляет пользователя в Google Play, а если не удалось — на веб-страницу Shizuku.
 *
 * @param start попытка открыть интент, возвращающая `false` при неудаче.
 * @return удалось ли передать ссылку внешнему приложению.
 */
internal fun openShizukuStore(start: (Intent) -> Boolean): Boolean {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
        .setPackage("com.android.vending")
    return start(market) ||
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")))
}
