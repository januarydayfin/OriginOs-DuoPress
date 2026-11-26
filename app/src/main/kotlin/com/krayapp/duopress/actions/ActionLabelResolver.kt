package com.krayapp.duopress.actions

import android.content.Context
import android.content.pm.PackageManager
import com.krayapp.duopress.PrefsHelper
import com.krayapp.duopress.R
import com.krayapp.duopress.ShortcutAction
import com.krayapp.duopress.VpnController

fun interface ActionLabelResolver {
    /** Возвращает название сохранённого действия или его ID. Вызывается вне главного потока. */
    fun resolve(id: String, vpnClient: String): String
}

/** Отображает отдельный сохранённый ID без загрузки каталога приложений. */
class LegacyActionLabelResolver(context: Context) : ActionLabelResolver {
    private val appContext = context.applicationContext
    private val resources = context.resources

    override fun resolve(id: String, vpnClient: String): String = when {
        id.isBlank() -> resources.getString(R.string.action_none_selected)
        id == PrefsHelper.SPECIAL_TORCH -> resources.getString(R.string.name_torch)
        id == PrefsHelper.SPECIAL_CAMERA -> resources.getString(R.string.name_camera)
        id == PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH -> resources.getString(R.string.action_circle_unavailable)
        id == PrefsHelper.SPECIAL_VPN -> {
            val client = VpnController.resolveClientPackage(appContext, vpnClient)
            val name = client?.let(VpnController::clientLabel) ?: resources.getString(R.string.vpn_name_generic)
            resources.getString(R.string.name_vpn_short, name)
        }
        id == PrefsHelper.SPECIAL_MENU -> resources.getString(R.string.name_menu)
        ShortcutAction.isAction(id) -> shortcutLabel(id)
        else -> appLabel(PrefsHelper.appPackageOf(id) ?: id) ?: id
    }

    private fun shortcutLabel(id: String): String {
        val shortcut = ShortcutAction.parse(id) ?: return id
        val app = appLabel(shortcut.packageName) ?: return id
        return "$app — ${shortcut.label.ifBlank { shortcut.shortcutId }}"
    }

    private fun appLabel(packageName: String): String? {
        val pm = appContext.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
                .toString()
                .takeIf(String::isNotBlank)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
