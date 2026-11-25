package com.vivotomirpay.stub.data

import android.content.Context
import android.content.SharedPreferences
import com.vivotomirpay.stub.PrefsHelper
import com.vivotomirpay.stub.menu.MenuEdit
import com.vivotomirpay.stub.menu.MenuEditResult
import com.vivotomirpay.stub.menu.applyMenuEdit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.util.Collections

/**
 * Использует существующие ключи, значения по умолчанию и нормализацию [PrefsHelper].
 *
 * @param context контекст установленного варианта приложения.
 * @param dispatcher диспетчер регистрации слушателя и чтения снимков из [observe].
 */
class SharedPrefsSettingsRepository(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SettingsRepository {
    private val appContext = context.applicationContext

    override fun observe(): Flow<SettingsSnapshot> = callbackFlow {
        val prefs = PrefsHelper.preferences(appContext)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .conflate()
        .map { snapshot() }
        .distinctUntilChanged()
        .flowOn(dispatcher)

    override fun snapshot(): SettingsSnapshot {
        val menu = PrefsHelper.getMenuItems(appContext)
        return SettingsSnapshot(
            targetId = PrefsHelper.getTargetPackage(appContext),
            lockscreenEnabled = PrefsHelper.isLockscreenEnabled(appContext),
            lockscreenActionId = PrefsHelper.getLockscreenAction(appContext),
            vibrateEnabled = PrefsHelper.isVibrateEnabled(appContext),
            vibrateDurationMs = PrefsHelper.getVibrateDurationMs(appContext),
            vibratePattern = PrefsHelper.getVibratePattern(appContext),
            vpnClient = PrefsHelper.getVpnClient(appContext),
            menuItems = Collections.unmodifiableList(List(menu.length()) { menu.optString(it) }),
            triggerLog = PrefsHelper.getLastTriggerLog(appContext).orEmpty(),
        )
    }

    override fun update(change: SettingsChange) {
        when (change) {
            is SettingsChange.Target -> PrefsHelper.setTargetPackage(appContext, change.id)
            is SettingsChange.LockscreenEnabled -> PrefsHelper.setLockscreenEnabled(appContext, change.enabled)
            is SettingsChange.LockscreenAction -> PrefsHelper.setLockscreenAction(appContext, change.id)
            is SettingsChange.VibrationEnabled -> PrefsHelper.setVibrateEnabled(appContext, change.enabled)
            is SettingsChange.VibrationDuration -> PrefsHelper.setVibrateDurationMs(appContext, change.ms)
            is SettingsChange.VibrationPattern -> PrefsHelper.setVibratePattern(appContext, change.pattern)
            is SettingsChange.VpnClient -> PrefsHelper.setVpnClient(appContext, change.client)
            SettingsChange.ClearLog -> PrefsHelper.clearTriggerLog(appContext)
        }
    }

    override fun editMenu(edit: MenuEdit): MenuEditResult = synchronized(menuLock) {
        val source = PrefsHelper.getMenuItems(appContext)
        val raw = List(source.length()) { source.opt(it) }
        val (result, items) = applyMenuEdit(raw, edit, { it?.toString().orEmpty() }, { it })
        if (result == MenuEditResult.Applied) {
            PrefsHelper.saveMenuItems(appContext, JSONArray().apply { items.forEach { put(it) } })
        }
        result
    }

    private companion object {
        val menuLock = Any()
    }
}
