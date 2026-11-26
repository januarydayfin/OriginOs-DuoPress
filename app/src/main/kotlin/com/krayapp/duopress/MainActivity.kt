package com.krayapp.duopress

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import com.krayapp.duopress.actions.ActionsIntent
import com.krayapp.duopress.actions.ActionsScreen
import com.krayapp.duopress.actions.ActionsViewModel
import com.krayapp.duopress.actions.LockscreenScreen
import com.krayapp.duopress.diagnostics.DiagnosticsScreen
import com.krayapp.duopress.diagnostics.DiagnosticsViewModel
import com.krayapp.duopress.menu.MenuEditorIntent
import com.krayapp.duopress.menu.MenuAppPickerScreen
import com.krayapp.duopress.menu.MenuEditorScreen
import com.krayapp.duopress.menu.MenuEditorViewModel
import com.krayapp.duopress.menu.MenuPicker
import com.krayapp.duopress.picker.ActionPickerIntent
import com.krayapp.duopress.picker.ActionPickerScreen
import com.krayapp.duopress.picker.ActionPickerViewModel
import com.krayapp.duopress.picker.DoublePressAppPickerScreen
import com.krayapp.duopress.picker.PickerSelection
import com.krayapp.duopress.picker.ShortcutPickerIntent
import com.krayapp.duopress.picker.ShortcutPickerScreen
import com.krayapp.duopress.picker.ShortcutPickerViewModel
import com.krayapp.duopress.picker.ShortcutPlatformResult
import com.krayapp.duopress.platform.PendingPlatformRequests
import com.krayapp.duopress.platform.PlatformCommand
import com.krayapp.duopress.platform.PlatformOutcome
import com.krayapp.duopress.platform.PlatformRequest
import com.krayapp.duopress.platform.PlatformRequestHost
import com.krayapp.duopress.settings.SettingsScreen
import com.krayapp.duopress.settings.SettingsViewModel
import com.krayapp.duopress.ui.common.VendorWarningDialog
import com.krayapp.duopress.ui.navigation.AppNavHost
import com.krayapp.duopress.ui.navigation.AppNavigator
import com.krayapp.duopress.ui.navigation.AppRoute
import com.krayapp.duopress.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private val session by viewModels<LauncherSession> {
        viewModelFactory {
            initializer {
                val context = applicationContext
                LauncherSession(dependenciesOverride?.invoke(context) ?: AppDependencies(context))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val requestHost = PlatformRequestHost(this, session.requests)
        setContent {
            AppTheme { LauncherContent(session, requestHost) }
        }
    }

    internal companion object {
        @VisibleForTesting
        @Volatile
        var dependenciesOverride: ((Context) -> AppDependencies)? = null
    }
}

private class LauncherSession(val dependencies: AppDependencies) : ViewModel() {
    val requests = PendingPlatformRequests(viewModelScope, dependencies.shortcutControl)

    inline fun <reified T : ViewModel> model(entry: NavBackStackEntry): T =
        ViewModelProvider(entry, if (entry.destination.hasRoute<AppRoute.MenuShortcutPicker>())
            dependencies.menuShortcutModels else dependencies.routeModels)[T::class.java]

    fun back(entry: NavBackStackEntry, parent: NavBackStackEntry): Boolean {
        if (entry.destination.hasRoute<AppRoute.DoublePressAppPicker>()) {
            val selection = model<ActionPickerViewModel>(parent).state.value.selection
            if (selection == PickerSelection.Saving || selection == PickerSelection.Complete) return true
        }
        if (entry.destination.hasRoute<AppRoute.MenuAppPicker>()) {
            val editor = model<MenuEditorViewModel>(parent)
            if (editor.state.value.saving) return true
            if (editor.state.value.picker == MenuPicker.Apps) editor.onIntent(MenuEditorIntent.BackFromPicker)
        }
        if (entry.destination.hasRoute<AppRoute.MenuShortcutPicker>()) {
            val selection = model<ShortcutPickerViewModel>(entry).state.value.selection
            if (selection == PickerSelection.Saving || selection == PickerSelection.Complete) return true
        }
        if (entry.destination.hasRoute<AppRoute.MenuEditor>()) {
            val model = model<MenuEditorViewModel>(entry)
            if (model.state.value.picker != MenuPicker.None) {
                model.onIntent(MenuEditorIntent.BackFromPicker)
                return true
            }
            if (model.deferExit()) return true
        }
        exit(entry)
        return false
    }

    fun exit(entry: NavBackStackEntry) {
        requests.revoke(entry.id)
        when {
            entry.destination.hasRoute<AppRoute.ActionPicker>() ->
                model<ActionPickerViewModel>(entry).onIntent(ActionPickerIntent.Exit)
            entry.destination.hasRoute<AppRoute.ShortcutPicker>() || entry.destination.hasRoute<AppRoute.MenuShortcutPicker>() ->
                model<ShortcutPickerViewModel>(entry).onIntent(ShortcutPickerIntent.Exit)
        }
    }

    override fun onCleared() = requests.close()
}

@Composable
private fun LauncherContent(session: LauncherSession, host: PlatformRequestHost) {
    var warnAboutVendor by rememberSaveable { mutableStateOf(session.dependencies.shouldWarnAboutVendor()) }
    var shizukuInstalled by remember { mutableStateOf<Boolean?>(null) }
    val activityLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(activityLifecycle, session) {
        activityLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            shizukuInstalled = null
            shizukuInstalled = session.dependencies.shizukuInstalled()
        }
    }
    AppNavHost(onBack = session::back, menuEditorActions = { entry ->
        val model = session.model<MenuEditorViewModel>(entry)
        val state by model.state.collectAsStateWithLifecycle()
        IconButton(onClick = { model.onIntent(MenuEditorIntent.Preview) },
            enabled = state.previewEnabled && state.picker == MenuPicker.None) {
            Icon(painterResource(R.drawable.ic_action_preview), stringResource(R.string.btn_menu_preview))
        }
    }, render = { route, entry, navigator ->
        when (route) {
            AppRoute.Actions, AppRoute.Lockscreen -> {
                val model = session.model<ActionsViewModel>(
                    if (route == AppRoute.Actions) entry else navigator.actionsEntry(),
                )
                val state by model.state.collectAsStateWithLifecycle()
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.onIntent(ActionsIntent.RefreshHandler) }
                if (route == AppRoute.Actions) {
                    PlatformRequests(
                        entry, navigator, host, session.requests, model, model.state,
                        requestFor = { it.platformRequest }, acknowledge = model::acknowledgePlatformRequest,
                    ) { id, outcome ->
                        model.onPlatformResult(id, outcome == PlatformOutcome.Launched)
                    }
                    ActionsScreen(state, model::onIntent, onOpen = {
                        if (navigator.isResumed(entry)) navigator.open(it)
                    })
                } else {
                    LockscreenScreen(state, model::onIntent)
                }
            }
            AppRoute.Settings -> {
                val model = session.model<SettingsViewModel>(entry)
                val state by model.state.collectAsStateWithLifecycle()
                PlatformRequests(
                    entry, navigator, host, session.requests, model, model.state,
                    requestFor = { it.platformRequest }, acknowledge = model::acknowledgePlatformRequest,
                ) { id, outcome ->
                    model.onPlatformResult(id, outcome == PlatformOutcome.Launched)
                }
                SettingsScreen(state, model::onIntent)
            }
            AppRoute.Diagnostics -> {
                val model = session.model<DiagnosticsViewModel>(entry)
                val state by model.state.collectAsStateWithLifecycle()
                PlatformRequests(
                    entry, navigator, host, session.requests, model, model.state,
                    requestFor = { it.platformRequest }, acknowledge = model::acknowledgePlatformRequest,
                ) { id, outcome ->
                    model.onPlatformResult(id, outcome == PlatformOutcome.Launched)
                }
                DiagnosticsScreen(state, model::onIntent)
            }
            AppRoute.MenuEditor -> {
                val model = session.model<MenuEditorViewModel>(entry)
                val state by model.state.collectAsStateWithLifecycle()
                LaunchedEffect(entry, model) {
                    entry.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        model.state.first { it.exitReady }
                        navigator.back(entry, session::back)
                    }
                }
                PlatformRequests(
                    entry, navigator, host, session.requests, model, model.state,
                    requestFor = { it.platformRequest }, acknowledge = model::acknowledgePlatformRequest,
                ) { id, outcome ->
                    model.onPlatformResult(id, outcome == PlatformOutcome.Launched)
                }
                val lifecycle by entry.lifecycle.currentStateAsState()
                MenuEditorScreen(state, model::onIntent, loadIcon = model::icon,
                    showDialogs = lifecycle == Lifecycle.State.RESUMED,
                    shizukuInstalled = shizukuInstalled,
                    onOpenShizukuStore = host::openShizukuListing,
                    onOpenApps = {
                        if (navigator.isResumed(entry) && model.state.value.picker == MenuPicker.Grid) {
                            model.onIntent(MenuEditorIntent.OpenApps)
                            if (model.state.value.picker == MenuPicker.Apps) navigator.open(AppRoute.MenuAppPicker)
                        }
                    },
                    onOpenShortcuts = {
                        if (shizukuInstalled == true && navigator.isResumed(entry) &&
                            model.state.value.canAdd && model.state.value.picker == MenuPicker.Grid
                        ) {
                            navigator.open(AppRoute.MenuShortcutPicker)
                        }
                    })
            }
            AppRoute.MenuAppPicker -> {
                val editor = remember(entry) { session.model<MenuEditorViewModel>(navigator.menuEditorEntry()) }
                val state by editor.state.collectAsStateWithLifecycle()
                LaunchedEffect(entry, editor) {
                    entry.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        editor.state.first { it.picker == MenuPicker.None }
                        navigator.back(entry, session::back)
                    }
                }
                MenuAppPickerScreen(state, editor::onIntent, editor::icon)
            }
            AppRoute.ActionPicker, AppRoute.DoublePressAppPicker -> {
                val pickerEntry = remember(entry) {
                    if (route == AppRoute.ActionPicker) entry else navigator.actionPickerEntry()
                }
                val model = session.model<ActionPickerViewModel>(pickerEntry)
                val isActive = { navigator.isResumed(entry) }
                CompleteSelection(
                    entry, navigator, model.state, { it.selectionComplete }, model::claimSelectionCompletion, session::exit,
                )
                if (route == AppRoute.ActionPicker) {
                    val openPicker: (AppRoute) -> Unit = { destination ->
                        val selection = model.state.value.selection
                        if (isActive() &&
                            selection != PickerSelection.Saving && selection != PickerSelection.Complete
                        ) {
                            navigator.open(destination)
                        }
                    }
                    ActionPickerScreen(
                        model,
                        onOpenApps = { openPicker(AppRoute.DoublePressAppPicker) },
                        onOpenShortcuts = { if (shizukuInstalled == true) openPicker(AppRoute.ShortcutPicker) },
                        shizukuInstalled = shizukuInstalled,
                        onOpenShizukuStore = host::openShizukuListing,
                        isActive = isActive,
                    )
                } else {
                    DoublePressAppPickerScreen(model, isActive = isActive)
                }
            }
            AppRoute.ShortcutPicker, AppRoute.MenuShortcutPicker -> {
                val model = session.model<ShortcutPickerViewModel>(entry)
                CompleteSelection(
                    entry, navigator, model.state, { it.selectionComplete }, model::claimSelectionCompletion, session::exit,
                    finishEditor = if (route == AppRoute.MenuShortcutPicker) { editor ->
                        session.model<MenuEditorViewModel>(editor).onIntent(MenuEditorIntent.FinishMenuAddition)
                    } else null,
                )
                PlatformRequests(
                    entry, navigator, host, session.requests, model, model.state,
                    requestFor = { it.platformRequest }, acknowledge = model::acknowledgePlatformRequest,
                ) { id, outcome ->
                    model.onPlatformResult(
                        id,
                        when (outcome) {
                            PlatformOutcome.Launched -> ShortcutPlatformResult.Opened
                            PlatformOutcome.Failed -> ShortcutPlatformResult.Failed
                            PlatformOutcome.Granted -> ShortcutPlatformResult.Granted
                            PlatformOutcome.Denied -> ShortcutPlatformResult.Denied
                        },
                    )
                }
                ShortcutPickerScreen(model)
            }
        }
    })
    if (warnAboutVendor) {
        VendorWarningDialog(onClose = { doNotShowAgain ->
            if (doNotShowAgain) session.dependencies.skipVendorWarning()
            warnAboutVendor = false
        })
    }
}

@Composable
private fun <S> CompleteSelection(
    entry: NavBackStackEntry,
    navigator: AppNavigator,
    state: Flow<S>,
    isComplete: (S) -> Boolean,
    claim: (Boolean) -> Boolean,
    exit: (NavBackStackEntry) -> Unit,
    finishEditor: ((NavBackStackEntry) -> Unit)? = null,
) {
    LaunchedEffect(entry, navigator) {
        entry.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.map(isComplete).distinctUntilChanged().collect {
                if (it) {
                    if (finishEditor == null) navigator.completeActionSelection(entry, claim, exit)
                    else navigator.completeMenuAddition(entry, claim, finishEditor, exit)
                }
            }
        }
    }
}

@Composable
private fun <S> PlatformRequests(
    entry: NavBackStackEntry,
    navigator: AppNavigator,
    host: PlatformRequestHost,
    pending: PendingPlatformRequests,
    model: ViewModel,
    state: Flow<S>,
    requestFor: (S) -> PlatformRequest?,
    acknowledge: (Long) -> Boolean,
    onResult: (Long, PlatformOutcome) -> Unit,
) {
    LaunchedEffect(entry, host, model) {
        val entryId = entry.id
        entry.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.map(requestFor).distinctUntilChanged().collect { request ->
                if (request == null || !navigator.isResumed(entry)) return@collect
                val managerIntent = if (request.command == PlatformCommand.OpenShizuku) host.prepareManagerIntent() else null
                if (!navigator.isResumed(entry)) return@collect
                if (model.getCloseable<AutoCloseable>(REQUEST_OWNER) == null) {
                    // Fallback for stack disposal other than the explicit Back and selection paths.
                    model.addCloseable(REQUEST_OWNER, AutoCloseable { pending.revoke(entryId) })
                }
                if (acknowledge(request.id)) {
                    host.execute(request, entryId, managerIntent, onResult)
                }
            }
        }
    }
}

private const val REQUEST_OWNER = "launcher.platformRequestOwner"
