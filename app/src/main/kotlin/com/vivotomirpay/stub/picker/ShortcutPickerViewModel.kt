package com.vivotomirpay.stub.picker

import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vivotomirpay.stub.ShizukuShortcuts.Status
import com.vivotomirpay.stub.ShortcutAction
import com.vivotomirpay.stub.data.SettingsChange
import com.vivotomirpay.stub.data.SettingsRepository
import com.vivotomirpay.stub.platform.AppCatalog
import com.vivotomirpay.stub.platform.AppEntry
import com.vivotomirpay.stub.platform.AppScope
import com.vivotomirpay.stub.platform.PlatformCommand
import com.vivotomirpay.stub.platform.PlatformRequest
import com.vivotomirpay.stub.platform.ShortcutEntry
import com.vivotomirpay.stub.platform.ShortcutGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ShortcutAccess {
    Checking, Ready, Unavailable, PreV11, UnsupportedUid, UnsupportedApi,
    PermissionAvailable, PermissionDenied, PermissionInterrupted,
}

/** Результат Android-хоста; `Opened` означает только успешный запуск менеджера Shizuku. */
enum class ShortcutPlatformResult { Granted, Denied, Failed, Opened }

sealed interface ShortcutLoad {
    data object Loading : ShortcutLoad
    data class Loaded(val entries: List<ShortcutEntry>) : ShortcutLoad
    data class Failed(val detail: String) : ShortcutLoad
}

data class ShortcutAppRow(val app: AppEntry, val children: ShortcutLoad?)

/** Состояние маршрута; только `platformRequest` можно передать на исполнение хосту. */
data class ShortcutPickerState(
    val access: ShortcutAccess = ShortcutAccess.Checking,
    val query: String = "",
    val selectedShortcut: ShortcutAction? = null,
    val platformRequest: PlatformRequest? = null,
    val permissionRequestId: Long? = null,
    val canOpenShizuku: Boolean = false,
    val openingShizuku: Boolean = false,
    val openShizukuFailed: Boolean = false,
    val loadingApps: Boolean = false,
    val appsError: String? = null,
    val visibleApps: List<ShortcutAppRow> = emptyList(),
    val selection: PickerSelection = PickerSelection.Idle,
) {
    val selectionComplete: Boolean get() = selection == PickerSelection.Complete
}

sealed interface ShortcutPickerIntent {
    data object RequestPermission : ShortcutPickerIntent
    data object RetryAccess : ShortcutPickerIntent
    data object OpenShizuku : ShortcutPickerIntent
    data object RetryApps : ShortcutPickerIntent
    data object Exit : ShortcutPickerIntent
    data class ToggleApp(val packageName: String) : ShortcutPickerIntent
    data class Search(val query: String) : ShortcutPickerIntent
    data class RetryShortcuts(val packageName: String) : ShortcutPickerIntent
    data class PickShortcut(val packageName: String, val shortcutId: String) : ShortcutPickerIntent
}

/**
 * Управляет подбором ярлыка и разрешением в пределах одного back-stack entry.
 * ViewModel не подписывается на Binder: listener принадлежит исполняющему хосту.
 */
class ShortcutPickerViewModel(
    private val repository: SettingsRepository,
    private val catalog: AppCatalog,
    private val gateway: ShortcutGateway,
    private val savedStateHandle: SavedStateHandle,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ShortcutPickerState(query = savedStateHandle.get<String>(SHORTCUT_QUERY).orEmpty()),
    )
    val state = mutableState.asStateFlow()
    private var acknowledgedRequest: PlatformRequest? = null
    private var closed = false
    private var selectionCompletionClaimed = false
    private var generation = 0L
    private var nextLoadId = 0L
    private val activeLoads = mutableMapOf<String, Long>()
    private var accessJob: Job? = null
    private var appsJob: Job? = null
    private var searchJob: Job? = null
    private var appsLoaded = false
    private var apps = emptyList<AppEntry>()
    private var loads = emptyMap<String, ShortcutLoad>()
    private var expanded = savedStateHandle.get<ArrayList<String>>(EXPANDED_APPS).orEmpty().toSet()
    private var collapsedInSearch = savedStateHandle.get<ArrayList<String>>(COLLAPSED_IN_SEARCH).orEmpty().toSet()

    init {
        viewModelScope.launch {
            repository.observe().flowOn(dispatcher).collect { settings ->
                mutableState.update { it.copy(selectedShortcut = ShortcutAction.parse(settings.targetId)) }
            }
        }
        if (savedStateHandle.get<Boolean>(PERMISSION_IN_FLIGHT) == true) {
            // После смерти процесса ID и слушатель не восстанавливаются: только ручное продолжение.
            mutableState.update { it.copy(access = ShortcutAccess.PermissionInterrupted) }
        } else {
            refreshAccess()
        }
    }

    /** Принимает UI-намерения и сигнал об окончательном выходе, но не о повороте экрана. */
    @MainThread
    fun onIntent(intent: ShortcutPickerIntent) {
        if (closed) return
        when (intent) {
            ShortcutPickerIntent.Exit -> close()
            ShortcutPickerIntent.RequestPermission -> if (state.value.access == ShortcutAccess.PermissionAvailable) {
                enqueue(PlatformCommand.RequestShortcutPermission)
            }
            ShortcutPickerIntent.OpenShizuku -> if (state.value.canOpenShizuku) enqueue(PlatformCommand.OpenShizuku)
            ShortcutPickerIntent.RetryAccess -> {
                val current = state.value
                if ((current.access == ShortcutAccess.Unavailable || current.access == ShortcutAccess.PermissionInterrupted) &&
                    current.platformRequest == null && acknowledgedRequest == null
                ) {
                    savedStateHandle.remove<Boolean>(PERMISSION_IN_FLIGHT)
                    refreshAccess()
                }
            }
            ShortcutPickerIntent.RetryApps -> if (state.value.access == ShortcutAccess.Ready) loadApps()
            is ShortcutPickerIntent.Search -> {
                collapsedInSearch = emptySet()
                savedStateHandle.remove<ArrayList<String>>(COLLAPSED_IN_SEARCH)
                savedStateHandle[SHORTCUT_QUERY] = intent.query
                mutableState.update { it.copy(query = intent.query) }
                rebuildRows()
            }
            is ShortcutPickerIntent.ToggleApp -> toggleApp(intent.packageName)
            is ShortcutPickerIntent.RetryShortcuts -> if (loads[intent.packageName] is ShortcutLoad.Failed) {
                loadShortcuts(intent.packageName)
            }
            is ShortcutPickerIntent.PickShortcut -> pick(intent.packageName, intent.shortcutId)
        }
    }

    /** Вызывается Android-хостом на Main непосредственно перед запуском команды. */
    @MainThread
    fun acknowledgePlatformRequest(id: Long): Boolean {
        val request = state.value.platformRequest ?: return false
        if (closed || request.id != id || acknowledgedRequest != null) return false
        acknowledgedRequest = request
        if (request.command == PlatformCommand.RequestShortcutPermission) {
            savedStateHandle[PERMISSION_IN_FLIGHT] = true
        }
        mutableState.update { it.copy(platformRequest = null) }
        return true
    }

    /** Принимает ответы только на подтверждённый запрос этого активного маршрута. */
    @MainThread
    fun onPlatformResult(id: Long, result: ShortcutPlatformResult) {
        val request = acknowledgedRequest ?: return
        if (closed || request.id != id) return
        when (request.command) {
            PlatformCommand.RequestShortcutPermission -> {
                if (result == ShortcutPlatformResult.Opened) return
                acknowledgedRequest = null
                savedStateHandle.remove<Boolean>(PERMISSION_IN_FLIGHT)
                if (result == ShortcutPlatformResult.Denied) {
                    mutableState.update { it.copy(access = ShortcutAccess.PermissionDenied, permissionRequestId = null) }
                } else {
                    // При сбое отправки сервис мог стать недоступен; причину определяет новая проверка.
                    refreshAccess()
                }
            }
            PlatformCommand.OpenShizuku -> {
                if (result != ShortcutPlatformResult.Opened && result != ShortcutPlatformResult.Failed) return
                acknowledgedRequest = null
                mutableState.update {
                    it.copy(openingShizuku = false, openShizukuFailed = result == ShortcutPlatformResult.Failed)
                }
            }
            else -> return
        }
    }

    /**
     * Однократно подтверждает завершение выбора. Хост на Main сначала проверяет, что именно
     * эта запись навигации текущая и находится в `RESUMED`, затем сразу выполняет переход.
     */
    @MainThread
    fun claimSelectionCompletion(isActiveEntry: Boolean): Boolean {
        if (!isActiveEntry || closed || selectionCompletionClaimed || !state.value.selectionComplete) return false
        selectionCompletionClaimed = true
        return true
    }

    suspend fun appIcon(packageName: String) = catalog.icon(packageName)

    suspend fun shortcutIcon(shortcut: ShortcutEntry) = gateway.icon(shortcut) ?: catalog.icon(shortcut.packageName)

    override fun onCleared() = close()

    private fun enqueue(command: PlatformCommand) {
        if (state.value.platformRequest != null || acknowledgedRequest != null) return
        val request = PlatformRequest.create(command)
        mutableState.update {
            it.copy(
                platformRequest = request,
                permissionRequestId = request.id.takeIf { command == PlatformCommand.RequestShortcutPermission },
                openingShizuku = command == PlatformCommand.OpenShizuku,
                openShizukuFailed = false,
            )
        }
    }

    private fun refreshAccess() {
        accessJob?.cancel()
        val expectedGeneration = generation
        mutableState.update {
            it.copy(access = ShortcutAccess.Checking, permissionRequestId = null, canOpenShizuku = false, openShizukuFailed = false)
        }
        accessJob = viewModelScope.launch {
            val (access, canOpenShizuku) = withContext(dispatcher) {
                val access = when (gateway.status()) {
                    Status.OK -> ShortcutAccess.Ready
                    Status.UNAVAILABLE -> ShortcutAccess.Unavailable
                    Status.PRE_V11 -> ShortcutAccess.PreV11
                    Status.UNSUPPORTED_UID -> ShortcutAccess.UnsupportedUid
                    Status.UNSUPPORTED_API -> ShortcutAccess.UnsupportedApi
                    Status.PERMISSION_DENIED -> if (gateway.canRequestPermission()) {
                        ShortcutAccess.PermissionAvailable
                    } else {
                        ShortcutAccess.PermissionDenied
                    }
                }
                access to (access == ShortcutAccess.Unavailable && gateway.canOpenShizuku())
            }
            if (generation != expectedGeneration) return@launch
            mutableState.update { it.copy(access = access, canOpenShizuku = canOpenShizuku) }
            if (access == ShortcutAccess.Ready && !appsLoaded) loadApps()
        }
    }

    private fun loadApps() {
        if (appsJob?.isActive == true) return
        val expectedGeneration = generation
        mutableState.update { it.copy(loadingApps = true, appsError = null) }
        appsJob = viewModelScope.launch {
            try {
                val loaded = catalog.apps(AppScope.Launcher)
                ensureActive()
                if (generation != expectedGeneration) return@launch
                appsLoaded = true
                apps = loaded
                mutableState.update { it.copy(loadingApps = false) }
                for (app in loaded) {
                    if (app.packageName in expanded && app.packageName !in loads) loadShortcuts(app.packageName)
                }
                rebuildRows()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureActive()
                if (generation != expectedGeneration) return@launch
                mutableState.update { it.copy(loadingApps = false, appsError = failure.describePickerFailure()) }
            }
        }
    }

    private fun toggleApp(packageName: String) {
        if (state.value.access != ShortcutAccess.Ready || apps.none { it.packageName == packageName }) return
        val query = state.value.query.trim()
        val loaded = loads[packageName] as? ShortcutLoad.Loaded
        if (query.isNotEmpty() && loaded?.entries?.any { it.matches(query) } == true) {
            collapsedInSearch = collapsedInSearch.toggled(packageName)
            savedStateHandle[COLLAPSED_IN_SEARCH] = ArrayList(collapsedInSearch)
        } else {
            expanded = expanded.toggled(packageName)
            savedStateHandle[EXPANDED_APPS] = ArrayList(expanded)
            if (packageName in expanded && packageName !in loads) {
                loadShortcuts(packageName)
                return
            }
        }
        rebuildRows()
    }

    private fun loadShortcuts(packageName: String) {
        if (state.value.access != ShortcutAccess.Ready || packageName in activeLoads) return
        val expectedGeneration = generation
        val id = ++nextLoadId
        activeLoads[packageName] = id
        if (packageName !in expanded) {
            expanded = expanded + packageName
            savedStateHandle[EXPANDED_APPS] = ArrayList(expanded)
        }
        loads = loads + (packageName to ShortcutLoad.Loading)
        rebuildRows()
        viewModelScope.launch {
            val result = try {
                ShortcutLoad.Loaded(gateway.shortcuts(packageName))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ShortcutLoad.Failed(failure.describePickerFailure())
            }
            ensureActive()
            if (generation != expectedGeneration || activeLoads[packageName] != id) return@launch
            activeLoads.remove(packageName)
            loads = loads + (packageName to result)
            rebuildRows()
            if (result is ShortcutLoad.Failed &&
                withContext(dispatcher) { gateway.status() } != Status.OK &&
                generation == expectedGeneration && state.value.access == ShortcutAccess.Ready
            ) {
                refreshAccess()
            }
        }
    }

    private fun rebuildRows() {
        searchJob?.cancel()
        val query = state.value.query.trim()
        val allApps = apps
        val allLoads = loads
        val expandedApps = expanded
        val searchCollapsed = collapsedInSearch
        searchJob = viewModelScope.launch {
            val rows = withContext(dispatcher) {
                allApps.mapNotNull { app ->
                    ensureActive()
                    val packageName = app.packageName
                    val load = allLoads[packageName]
                    val hits = if (query.isEmpty()) emptyList() else (load as? ShortcutLoad.Loaded)?.entries
                        ?.filter { it.matches(query) }
                        .orEmpty()
                    if (!app.matchesPickerQuery(query) && hits.isEmpty()) return@mapNotNull null
                    val children = when {
                        hits.isNotEmpty() -> if (packageName in searchCollapsed) null else ShortcutLoad.Loaded(hits)
                        packageName in expandedApps -> load
                        else -> null
                    }
                    ShortcutAppRow(app, children)
                }
            }
            mutableState.update { it.copy(visibleApps = rows) }
        }
    }

    private fun pick(packageName: String, id: String) {
        val current = state.value
        if (current.access != ShortcutAccess.Ready ||
            current.selection == PickerSelection.Saving || current.selectionComplete
        ) return
        val shortcut = (loads[packageName] as? ShortcutLoad.Loaded)?.entries
            ?.firstOrNull { it.id == id && it.packageName == packageName }
            ?: return
        val action = ShortcutAction(packageName, shortcut.id, shortcut.label)
        mutableState.update { it.copy(selection = PickerSelection.Saving) }
        viewModelScope.launch {
            val saved = try {
                withContext(dispatcher) { repository.update(SettingsChange.Target(action.toActionId())) }
                PickerSelection.Complete
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PickerSelection.Failed
            }
            mutableState.update { it.copy(selection = saved) }
        }
    }

    private fun close() {
        if (closed) return
        closed = true
        generation++
        activeLoads.clear()
        acknowledgedRequest = null
        savedStateHandle.remove<Boolean>(PERMISSION_IN_FLIGHT)
        viewModelScope.coroutineContext.cancelChildren()
        mutableState.update { it.copy(platformRequest = null, permissionRequestId = null, openingShizuku = false) }
    }

    private companion object {
        const val PERMISSION_IN_FLIGHT = "shortcut_permission_in_flight"
        const val SHORTCUT_QUERY = "shortcut_picker_query"
        const val EXPANDED_APPS = "shortcut_picker_expanded_apps"
        const val COLLAPSED_IN_SEARCH = "shortcut_picker_collapsed_in_search"
    }
}

private fun ShortcutEntry.matches(query: String): Boolean =
    label.contains(query, ignoreCase = true) || id.contains(query, ignoreCase = true)

private fun Set<String>.toggled(packageName: String): Set<String> =
    if (packageName in this) this - packageName else this + packageName

private fun Exception.describePickerFailure(): String =
    message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
