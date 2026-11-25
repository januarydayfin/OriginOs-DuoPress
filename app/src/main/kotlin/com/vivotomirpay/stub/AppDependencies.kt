package com.vivotomirpay.stub

import android.content.Context
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vivotomirpay.stub.actions.ActionsViewModel
import com.vivotomirpay.stub.actions.LegacyActionLabelResolver
import com.vivotomirpay.stub.data.LegacySettingsRepository
import com.vivotomirpay.stub.diagnostics.DiagnosticsViewModel
import com.vivotomirpay.stub.menu.MenuEditorViewModel
import com.vivotomirpay.stub.picker.ActionPickerViewModel
import com.vivotomirpay.stub.picker.ShortcutPickerViewModel
import com.vivotomirpay.stub.platform.AndroidAppCatalog
import com.vivotomirpay.stub.platform.AndroidDeviceStatusProbe
import com.vivotomirpay.stub.platform.AndroidHandlerProbe
import com.vivotomirpay.stub.platform.AndroidShortcutControl
import com.vivotomirpay.stub.platform.AndroidShortcutGateway
import com.vivotomirpay.stub.platform.HandlerProbe
import com.vivotomirpay.stub.platform.ShortcutControl
import com.vivotomirpay.stub.platform.ShortcutGateway
import com.vivotomirpay.stub.settings.SettingsViewModel

/** Один набор зависимостей launcher, без ссылки на окно или Activity. */
internal class AppDependencies(
    context: Context,
    shortcuts: ShortcutGateway? = null,
    shortcutControl: ShortcutControl? = null,
    handlerProbe: HandlerProbe = AndroidHandlerProbe(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val repository = LegacySettingsRepository(appContext)
    private val device = AndroidDeviceStatusProbe(appContext)
    private val catalog = AndroidAppCatalog(appContext)
    private val labels = LegacyActionLabelResolver(appContext)
    private val androidShortcuts by lazy { AndroidShortcutGateway(appContext) }
    val shortcutControl = shortcutControl ?: AndroidShortcutControl { androidShortcuts }

    val routeModels = viewModelFactory {
        initializer { ActionsViewModel(repository, handlerProbe, labels) }
        initializer { SettingsViewModel(repository, device) }
        initializer { DiagnosticsViewModel(repository, handlerProbe) }
        initializer { ActionPickerViewModel(repository, catalog, savedStateHandle = createSavedStateHandle()) }
        initializer { ShortcutPickerViewModel(repository, catalog, shortcuts ?: androidShortcuts, createSavedStateHandle()) }
        initializer { MenuEditorViewModel(repository, catalog) }
    }
}
