package com.krayapp.duopress

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import java.util.Locale

/**
 * Управление VPN-клиентами Happ и Incy без root — полностью в фоне,
 * без открытия интерфейса приложений.
 *
 * Happ: deeplink с хостом на `_without_ui`, затем обработчик виджета (broadcast).
 * Incy: broadcast с учётом stopped-state, затем та же команда через ShortcutActivity.
 * Активный туннель определяется публичным API: перебор сетей ConnectivityManager
 * и проверка транспорта TRANSPORT_VPN.
 */
object VpnController {
    // Happ: актуальный пакет (GitHub-релизы / APK с happ.su) и старый пакет (Play Market).
    const val HAPP_PKG_NEW = "su.happ.proxyutility"
    const val HAPP_PKG_OLD = "com.happproxy"
    const val INCY_PKG = "llc.itdev.incy"

    /** Проверяет наличие пакета; пустое или недоступное имя считается отсутствующим. */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun isPackageInstalled(context: Context?, pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        try {
            context!!.packageManager.getPackageInfo(pkg, 0)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    @JvmStatic
    fun isHappInstalled(context: Context?): Boolean =
        isPackageInstalled(context, HAPP_PKG_NEW) || isPackageInstalled(context, HAPP_PKG_OLD)

    @JvmStatic
    fun isIncyInstalled(context: Context?): Boolean = isPackageInstalled(context, INCY_PKG)

    /** Возвращает пакет установленного Happ-клиента или null, отдавая приоритет новому пакету. */
    @JvmStatic
    fun getHappPackage(context: Context?): String? {
        if (isPackageInstalled(context, HAPP_PKG_NEW)) return HAPP_PKG_NEW
        if (isPackageInstalled(context, HAPP_PKG_OLD)) return HAPP_PKG_OLD
        return null
    }

    /** Возвращает пакет выбранного VPN-клиента; в режиме «Авто» сначала проверяет Happ. */
    @JvmStatic
    fun resolveClientPackage(context: Context?, clientSetting: String?): String? {
        if (clientSetting == "happ") {
            return getHappPackage(context)
        }
        if (clientSetting == "incy") {
            return if (isIncyInstalled(context)) INCY_PKG else null
        }
        val happ = getHappPackage(context)
        if (happ != null) return happ
        return if (isIncyInstalled(context)) INCY_PKG else null
    }

    /** Человекочитаемое имя клиента по пакету («Happ» / «incy»). */
    @JvmStatic
    fun clientLabel(pkg: String?): String {
        if (pkg == HAPP_PKG_NEW || pkg == HAPP_PKG_OLD) return "Happ"
        if (pkg == INCY_PKG) return "incy"
        return pkg ?: "?"
    }

    /** Есть ли сейчас активный VPN-туннель в системе (любой клиент). */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun isAnyVpnActive(context: Context?): Boolean {
        try {
            val cm = context!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager? ?: return false
            val networks: Array<Network>? = cm.allNetworks
            if (networks == null) return false
            for (network in networks) {
                val capabilities = cm.getNetworkCapabilities(network)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Переключает VPN выбранного клиента в фоне. Happ сначала получает неявный deeplink
     * с `setPackage`, а при недоступности — broadcast виджета. Incy получает один и тот же
     * идемпотентный verb через broadcast и ShortcutActivity. Если пути нет, обычный UI не открывается.
     *
     * @param mode `toggle`, `connect` или `disconnect`
     * @return человекочитаемый статус для журнала.
     */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun controlVpn(context: Context?, clientSetting: String?, mode: String?): String {
        val pkg = resolveClientPackage(context, clientSetting)
        if (pkg == null) {
            val wanted = when (clientSetting) {
                "happ" -> "Happ"
                "incy" -> "incy"
                else -> "Happ / incy"
            }
            return context!!.getString(R.string.vpn_status_not_installed, wanted)
        }

        val wasActive = isAnyVpnActive(context)
        val label = clientLabel(pkg)
        val verb = when (mode) {
            "connect" -> "connect"
            "disconnect" -> "disconnect"
            else -> "toggle"
        }
        @Suppress("UNUSED_VARIABLE")
        val verbUpper = verb.uppercase(Locale.US)
        // resolveClientPackage не возвращает установленный пакет без контекста.
        val clientContext = context!!

        if (pkg == HAPP_PKG_NEW || pkg == HAPP_PKG_OLD) {
            // Резолвится по фильтрам самой версии Happ, без имён классов.
            try {
                val deeplink = Intent(Intent.ACTION_VIEW, Uri.parse("happ://${verb}_without_ui"))
                deeplink.addCategory(Intent.CATEGORY_DEFAULT)
                deeplink.setPackage(pkg)
                deeplink.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                if (deeplink.resolveActivity(clientContext.packageManager) != null) {
                    clientContext.startActivity(deeplink)
                    return clientContext.getString(R.string.vpn_status_ok, label, verb, vpnStateHint(clientContext, wasActive))
                }
            } catch (_: Exception) {
                // Ниже — broadcast-путь.
            }

            // Проверяем наличие обработчика виджета до отправки.
            if (sendCheckedBroadcast(clientContext, "$pkg.action.widget.click", pkg)) {
                return clientContext.getString(R.string.vpn_status_ok, label, verb, vpnStateHint(clientContext, wasActive))
            }
            return clientContext.getString(R.string.vpn_status_no_path, label)
        }

        if (pkg == INCY_PKG) {
            val verbResolved = if (verb == "toggle") {
                if (wasActive) "disconnect" else "connect"
            } else {
                verb
            }
            val verbResolvedUpper = verbResolved.uppercase(Locale.US)
            var delivered = false

            // С флагом INCLUDE_STOPPED_PACKAGES broadcast доставляется и после остановки Incy.
            try {
                val broadcast = Intent("llc.itdev.incy.$verbResolvedUpper")
                broadcast.setPackage(pkg)
                broadcast.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                val receivers: List<ResolveInfo>? = clientContext.packageManager.queryBroadcastReceivers(broadcast, 0)
                if (receivers != null && receivers.isNotEmpty()) {
                    clientContext.sendBroadcast(broadcast)
                    delivered = true
                }
            } catch (_: Exception) {
            }

            // Та же команда через прозрачную ShortcutActivity также будит процесс.
            try {
                val shortcut = Intent("llc.itdev.incy.VOICE_$verbResolvedUpper")
                shortcut.setComponent(ComponentName(pkg, "llc.itdev.incy.ShortcutActivity"))
                shortcut.addCategory(Intent.CATEGORY_DEFAULT)
                shortcut.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                if (shortcut.resolveActivity(clientContext.packageManager) != null) {
                    clientContext.startActivity(shortcut)
                    delivered = true
                }
            } catch (_: Exception) {
            }

            if (delivered) {
                return clientContext.getString(
                    R.string.vpn_status_ok, label, verbResolved, vpnStateHint(clientContext, wasActive),
                )
            }
            return clientContext.getString(R.string.vpn_status_no_path, label)
        }
        return clientContext.getString(R.string.vpn_status_no_path, label)
    }

    /** Отправляет broadcast в конкретный пакет, только если в нём найден обработчик. */
    @JvmStatic
    @Suppress("DEPRECATION")
    private fun sendCheckedBroadcast(context: Context, action: String, pkg: String): Boolean {
        try {
            val broadcast = Intent(action)
            broadcast.setPackage(pkg)
            broadcast.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            val pm = context.packageManager
            val receivers: List<ResolveInfo>? = pm.queryBroadcastReceivers(broadcast, 0)
            if (receivers == null || receivers.isEmpty()) {
                return false
            }
            context.sendBroadcast(broadcast)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    @JvmStatic
    private fun vpnStateHint(context: Context, wasActive: Boolean): String =
        if (wasActive) {
            context.getString(R.string.vpn_state_was_active)
        } else {
            context.getString(R.string.vpn_state_was_inactive)
        }
}
