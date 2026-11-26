package com.krayapp.duopress.platform

import android.content.Context
import com.krayapp.duopress.VpnController

/**
 * Состояние устройства, независимое от выбранного в приложении VPN-клиента.
 *
 * @property vpnActive есть хотя бы один VPN-туннель, независимо от клиента.
 */
data class DeviceStatus(
    val happAvailable: Boolean,
    val incyAvailable: Boolean,
    val vpnActive: Boolean,
)

fun interface DeviceStatusProbe {
    /** Проверяет установленные VPN-клиенты и туннель вне главного потока. */
    fun inspect(): DeviceStatus
}

/**
 * Читает данные PackageManager и ConnectivityManager о VPN.
 *
 * @param context контекст установленного варианта приложения; удерживается только application context.
 */
class AndroidDeviceStatusProbe(context: Context) : DeviceStatusProbe {
    private val appContext = context.applicationContext

    override fun inspect(): DeviceStatus = DeviceStatus(
        happAvailable = VpnController.isHappInstalled(appContext),
        incyAvailable = VpnController.isIncyInstalled(appContext),
        vpnActive = VpnController.isAnyVpnActive(appContext),
    )
}
