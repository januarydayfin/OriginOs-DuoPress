package com.krayapp.duopress

import android.content.Context
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.krayapp.duopress.actions.ActionsViewModel
import com.krayapp.duopress.actions.LegacyActionLabelResolver
import com.krayapp.duopress.data.SharedPrefsSettingsRepository
import com.krayapp.duopress.diagnostics.DiagnosticsViewModel
import com.krayapp.duopress.menu.MenuEditorViewModel
import com.krayapp.duopress.picker.ActionPickerViewModel
import com.krayapp.duopress.picker.ShortcutPickerViewModel
import com.krayapp.duopress.platform.AndroidAppCatalog
import com.krayapp.duopress.platform.AndroidDeviceStatusProbe
import com.krayapp.duopress.platform.AndroidHandlerProbe
import com.krayapp.duopress.platform.AndroidShortcutControl
import com.krayapp.duopress.platform.AndroidShortcutGateway
import com.krayapp.duopress.platform.HandlerProbe
import com.krayapp.duopress.platform.ShortcutControl
import com.krayapp.duopress.platform.ShortcutGateway
import com.krayapp.duopress.settings.SettingsViewModel

/** Один набор зависимостей launcher, без ссылки на окно или Activity. */
internal class AppDependencies(
    context: Context,
    shortcuts: ShortcutGateway? = null,
    shortcutControl: ShortcutControl? = null,
    handlerProbe: HandlerProbe = AndroidHandlerProbe(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val repository = SharedPrefsSettingsRepository(appContext)
    private val device = AndroidDeviceStatusProbe(appContext)
    private val catalog = AndroidAppCatalog(appContext)
    private val labels = LegacyActionLabelResolver(appContext)
    private val androidShortcuts by lazy { AndroidShortcutGateway(appContext) }
    val shortcutControl = shortcutControl ?: AndroidShortcutControl { androidShortcuts }

    val routeModels = viewModelFactory {
        initializer { ActionsViewModel(repository, handlerProbe, labels) }
        initializer { SettingsViewModel(repository, device) }
        initializer { DiagnosticsViewModel(repository, handlerProbe, appContext.packageName) }
        initializer { ActionPickerViewModel(repository, catalog, savedStateHandle = createSavedStateHandle()) }
        initializer { ShortcutPickerViewModel(repository, catalog, shortcuts ?: androidShortcuts, createSavedStateHandle()) }
        initializer { MenuEditorViewModel(repository, catalog) }
    }
}
