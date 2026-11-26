package com.krayapp.duopress.platform

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.krayapp.duopress.VpnController

/**
 * Состояние устройства, независимое от выбранного в приложении VPN-клиента.
 *
 * @property vpnActive есть хотя бы один VPN-туннель, независимо от клиента.
 * @property assistantPackage пакет системного ассистента или `null`, если не удалось его определить.
 * @property assistantLabel подпись пакета, если она доступна и отличается от имени пакета.
 * @property assistantReadFailed чтение настройки завершилось ошибкой, а не пустым значением.
 */
data class DeviceStatus(
    val happAvailable: Boolean,
    val incyAvailable: Boolean,
    val vpnActive: Boolean,
    val assistantPackage: String? = null,
    val assistantLabel: String? = null,
    val assistantReadFailed: Boolean = false,
)

fun interface DeviceStatusProbe {
    /** Проверяет установленные клиенты, туннель и системного ассистента вне главного потока. */
    fun inspect(): DeviceStatus
}

/**
 * Читает те же данные PackageManager, ConnectivityManager и Settings.Secure, что старый экран.
 *
 * @param context контекст установленного варианта приложения; удерживается только application context.
 */
class AndroidDeviceStatusProbe(context: Context) : DeviceStatusProbe {
    private val appContext = context.applicationContext

    override fun inspect(): DeviceStatus {
        val status = DeviceStatus(
            happAvailable = VpnController.isHappInstalled(appContext),
            incyAvailable = VpnController.isIncyInstalled(appContext),
            vpnActive = VpnController.isAnyVpnActive(appContext),
        )
        val assistantPackage = try {
            Settings.Secure.getString(appContext.contentResolver, "assistant")
                ?.substringBefore('/')
                ?.takeIf(String::isNotBlank)
        } catch (_: RuntimeException) {
            return status.copy(assistantReadFailed = true)
        } ?: return status
        return status.copy(assistantPackage = assistantPackage, assistantLabel = assistantLabel(assistantPackage))
    }

    private fun assistantLabel(packageName: String): String? {
        val pm = appContext.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                .takeIf { it.isNotBlank() && it != packageName }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
