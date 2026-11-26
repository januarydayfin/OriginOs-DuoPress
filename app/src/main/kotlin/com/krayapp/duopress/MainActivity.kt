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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.krayapp.duopress.menu.MenuEditorScreen
import com.krayapp.duopress.menu.MenuEditorViewModel
import com.krayapp.duopress.menu.MenuPicker
import com.krayapp.duopress.picker.ActionPickerIntent
import com.krayapp.duopress.picker.ActionPickerScreen
import com.krayapp.duopress.picker.ActionPickerViewModel
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
import com.krayapp.duopress.ui.navigation.AppNavHost
import com.krayapp.duopress.ui.navigation.AppNavigator
import com.krayapp.duopress.ui.navigation.AppRoute
import com.krayapp.duopress.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
        ViewModelProvider(entry, dependencies.routeModels)[T::class.java]

    fun back(entry: NavBackStackEntry): Boolean {
        if (entry.destination.hasRoute<AppRoute.MenuEditor>()) {
            val model = model<MenuEditorViewModel>(entry)
            if (model.state.value.picker != MenuPicker.None) {
                model.onIntent(MenuEditorIntent.ClosePicker)
                return true
            }
        }
        exit(entry)
        return false
    }

    fun exit(entry: NavBackStackEntry) {
        requests.revoke(entry.id)
        when {
            entry.destination.hasRoute<AppRoute.ActionPicker>() ->
                model<ActionPickerViewModel>(entry).onIntent(ActionPickerIntent.Exit)
            entry.destination.hasRoute<AppRoute.ShortcutPicker>() ->
                model<ShortcutPickerViewModel>(entry).onIntent(ShortcutPickerIntent.Exit)
        }
    }

    override fun onCleared() = requests.close()
}

@Composable
private fun LauncherContent(session: LauncherSession, host: PlatformRequestHost) {
    AppNavHost(onBack = session::back, render = { route, entry, navigator ->
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
                PlatformRequests(
                    entry, navigator, host, session.requests, model, model.state,
                    requestFor = { it.platformRequest }, acknowledge = model::acknowledgePlatformRequest,
                ) { id, outcome ->
                    model.onPlatformResult(id, outcome == PlatformOutcome.Launched)
                }
                MenuEditorScreen(state, model::onIntent, loadIcon = model::icon)
            }
            AppRoute.ActionPicker -> {
                val model = session.model<ActionPickerViewModel>(entry)
                CompleteSelection(
                    entry, navigator, model.state, { it.selectionComplete }, model::claimSelectionCompletion, session::exit,
                )
                ActionPickerScreen(model, onOpenShortcuts = {
                    val selection = model.state.value.selection
                    if (navigator.isResumed(entry) &&
                        selection != PickerSelection.Saving && selection != PickerSelection.Complete
                    ) {
                        navigator.open(AppRoute.ShortcutPicker)
                    }
                })
            }
            AppRoute.ShortcutPicker -> {
                val model = session.model<ShortcutPickerViewModel>(entry)
                CompleteSelection(
                    entry, navigator, model.state, { it.selectionComplete }, model::claimSelectionCompletion, session::exit,
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
}

@Composable
private fun <S> CompleteSelection(
    entry: NavBackStackEntry,
    navigator: AppNavigator,
    state: Flow<S>,
    isComplete: (S) -> Boolean,
    claim: (Boolean) -> Boolean,
    exit: (NavBackStackEntry) -> Unit,
) {
    LaunchedEffect(entry, navigator) {
        entry.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.map(isComplete).distinctUntilChanged().collect {
                if (it) navigator.completeActionSelection(entry, claim, exit)
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
