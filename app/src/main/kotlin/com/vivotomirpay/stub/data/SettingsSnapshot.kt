package com.vivotomirpay.stub.data

/** Сохранённые настройки без временного состояния экранов. */
data class SettingsSnapshot(
    val targetId: String,
    val lockscreenEnabled: Boolean,
    val lockscreenActionId: String,
    val vibrateEnabled: Boolean,
    val vibrateDurationMs: Int,
    val vibratePattern: String,
    val vpnClient: String,
    val menuItems: List<String>,
    val triggerLog: String,
)

/** Точечные изменения настроек: каждое обновляет только своё значение. */
sealed interface SettingsChange {
    data class Target(val id: String) : SettingsChange
    data class LockscreenEnabled(val enabled: Boolean) : SettingsChange
    data class LockscreenAction(val id: String) : SettingsChange
    data class VibrationEnabled(val enabled: Boolean) : SettingsChange
    data class VibrationDuration(val ms: Int) : SettingsChange
    data class VibrationPattern(val pattern: String) : SettingsChange
    data class VpnClient(val client: String) : SettingsChange
    data object ClearLog : SettingsChange
}
