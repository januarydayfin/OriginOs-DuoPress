package com.krayapp.duopress.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PatternMatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
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
 * Сигнализирует проверить наличие Shizuku при подписке и после установки или удаления пакета.
 *
 * @param context контекст приложения для регистрации системного приёмника.
 * @return поток сигналов, приёмник работает только пока поток собирают.
 */
internal fun shizukuInstallChecks(context: Context): Flow<Unit> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(Unit)
        }
    }
    val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addDataScheme("package")
        addDataSchemeSpecificPart(SHIZUKU_PACKAGE, PatternMatcher.PATTERN_LITERAL)
    }
    // Обе рассылки защищены системой. Сначала регистрируем приёмник, чтобы не пропустить изменение.
    context.registerReceiver(receiver, filter)
    trySend(Unit)
    awaitClose { context.unregisterReceiver(receiver) }
}.conflate()

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
