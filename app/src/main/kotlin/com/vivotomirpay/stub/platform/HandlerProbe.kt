package com.vivotomirpay.stub.platform

import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.vivotomirpay.stub.SwipeEntryActivity

/** Наблюдаемый результат маршрутизации вызова Android, не результат проверки самой кнопки. */
sealed interface HandlerStatus {
    data object Loading : HandlerStatus
    data object ThisApp : HandlerStatus
    data class OtherApp(val packageName: String, val label: String) : HandlerStatus
    data object NoSpecificHandler : HandlerStatus
    data object Unavailable : HandlerStatus
}

data class HandlerCandidate(val packageName: String, val label: String)

/** Текущий результат разрешения интента и список конкретных обработчиков для диагностики. */
data class HandlerSnapshot(val status: HandlerStatus, val candidates: List<HandlerCandidate>)

fun interface HandlerProbe {
    /** Читает состояние маршрутизации. Вызывается вне главного потока. */
    fun inspect(): HandlerSnapshot
}

/** Проверяет Vivo Intent через [PackageManager] для текущего варианта приложения. */
class AndroidHandlerProbe(context: Context) : HandlerProbe {
    private val appContext = context.applicationContext

    override fun inspect(): HandlerSnapshot {
        val pm = appContext.packageManager
        val intent = Intent(SwipeEntryActivity.ACTION_VIVO_SWIPE).addCategory(Intent.CATEGORY_DEFAULT)
        return try {
            val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.candidate(pm)
            val candidates = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.candidate(pm) }
            classifyHandler(appContext.packageName, resolved, candidates)
        } catch (_: SecurityException) {
            HandlerSnapshot(HandlerStatus.Unavailable, emptyList())
        }
    }

    private fun ResolveInfo.candidate(pm: PackageManager): HandlerCandidate? {
        val packageName = activityInfo?.packageName ?: return null
        return HandlerCandidate(packageName, loadLabel(pm)?.toString()?.takeIf(String::isNotBlank) ?: packageName)
    }
}

internal fun classifyHandler(
    ownPackage: String,
    resolved: HandlerCandidate?,
    candidates: List<HandlerCandidate>,
): HandlerSnapshot {
    val concrete = resolved?.takeIf(HandlerCandidate::isConcrete)
    val registered = candidates.filter(HandlerCandidate::isConcrete)
    val status = when {
        concrete?.packageName == ownPackage -> HandlerStatus.ThisApp
        concrete != null -> HandlerStatus.OtherApp(concrete.packageName, concrete.label)
        registered.isNotEmpty() -> HandlerStatus.NoSpecificHandler
        else -> HandlerStatus.Unavailable
    }
    return HandlerSnapshot(status, registered)
}

private fun HandlerCandidate.isConcrete(): Boolean =
    packageName.isNotBlank() && packageName != "android" && !packageName.contains("resolver", ignoreCase = true)
