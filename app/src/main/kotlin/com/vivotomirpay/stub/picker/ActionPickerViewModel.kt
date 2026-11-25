package com.vivotomirpay.stub.picker

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vivotomirpay.stub.PrefsHelper
import com.vivotomirpay.stub.data.SettingsChange
import com.vivotomirpay.stub.data.SettingsRepository
import com.vivotomirpay.stub.platform.AppCatalog
import com.vivotomirpay.stub.platform.AppEntry
import com.vivotomirpay.stub.platform.AppScope
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

/** Пресеты со старыми ID хранилища; переход к ярлыкам не является сохраняемым действием. */
enum class ActionPreset(val id: String) {
    MirPay("ru.nspk.mirpay"),
    SbpPay("ru.nspk.sbpay"),
    VivoWallet("com.vivo.wallet"),
    Torch(PrefsHelper.SPECIAL_TORCH),
    Camera(PrefsHelper.SPECIAL_CAMERA),
    Vpn(PrefsHelper.SPECIAL_VPN),
    CircleToSearch(PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH),
    Menu(PrefsHelper.SPECIAL_MENU),
}

sealed interface ActionPickerIntent {
    data class PickPreset(val preset: ActionPreset) : ActionPickerIntent
    data class ChangeManualPackage(val value: String) : ActionPickerIntent
    data class Search(val query: String) : ActionPickerIntent
    data class PickApp(val packageName: String) : ActionPickerIntent
    data object ReloadApps : ActionPickerIntent
    data object PickManualPackage : ActionPickerIntent
    data object Exit : ActionPickerIntent
}

enum class PickerSelection { Idle, Saving, Complete, Failed }

data class ActionPickerState(
    val targetId: String? = null,
    val query: String = "",
    val manualPackage: String = "",
    val apps: List<AppEntry> = emptyList(),
    val visibleApps: List<AppEntry> = emptyList(),
    val loadingApps: Boolean = true,
    val appsFailed: Boolean = false,
    val selection: PickerSelection = PickerSelection.Idle,
) {
    val selectionComplete: Boolean get() = selection == PickerSelection.Complete
}

/** Подбирает целевое действие и завершает выбор только после записи в настройки. */
class ActionPickerViewModel(
    private val repository: SettingsRepository,
    private val catalog: AppCatalog,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ActionPickerState())
    val state = mutableState.asStateFlow()
    private var closed = false
    private var selectionCompletionClaimed = false
    private var appsJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observe().flowOn(dispatcher).collect { settings ->
                mutableState.update { it.copy(targetId = settings.targetId) }
            }
        }
        loadApps()
    }

    @MainThread
    fun onIntent(intent: ActionPickerIntent) {
        if (closed) return
        when (intent) {
            ActionPickerIntent.Exit -> {
                closed = true
                viewModelScope.coroutineContext.cancelChildren()
            }
            is ActionPickerIntent.PickPreset -> save(intent.preset.id)
            is ActionPickerIntent.PickApp -> if (state.value.apps.any { it.packageName == intent.packageName }) {
                save(intent.packageName)
            }
            ActionPickerIntent.PickManualPackage -> {
                state.value.manualPackage.trim().takeIf(String::isNotEmpty)?.let(::save)
            }
            is ActionPickerIntent.ChangeManualPackage -> mutableState.update { it.copy(manualPackage = intent.value) }
            is ActionPickerIntent.Search -> {
                mutableState.update { it.copy(query = intent.query) }
                searchApps()
            }
            ActionPickerIntent.ReloadApps -> loadApps()
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

    /** Загружает только значок запрошенной строки, не меняя результаты поиска. */
    suspend fun icon(packageName: String) = catalog.icon(packageName)

    override fun onCleared() {
        closed = true
    }

    private fun loadApps() {
        appsJob?.cancel()
        mutableState.update { it.copy(loadingApps = true, appsFailed = false) }
        appsJob = viewModelScope.launch {
            try {
                val apps = catalog.apps(AppScope.Installed)
                ensureActive()
                mutableState.update { it.copy(apps = apps, loadingApps = false) }
                searchApps()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                mutableState.update { it.copy(appsFailed = true, loadingApps = false) }
            }
        }
    }

    private fun searchApps() {
        searchJob?.cancel()
        val (apps, query) = state.value.let { it.apps to it.query.trim() }
        searchJob = viewModelScope.launch {
            val visible = withContext(dispatcher) { apps.filter { it.matchesPickerQuery(query) } }
            mutableState.update { it.copy(visibleApps = visible) }
        }
    }

    private fun save(id: String) {
        if (state.value.selection in setOf(PickerSelection.Saving, PickerSelection.Complete)) return
        mutableState.update { it.copy(selection = PickerSelection.Saving) }
        viewModelScope.launch {
            val saved = try {
                withContext(dispatcher) { repository.update(SettingsChange.Target(id)) }
                PickerSelection.Complete
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PickerSelection.Failed
            }
            mutableState.update { it.copy(selection = saved) }
        }
    }
}

internal fun AppEntry.matchesPickerQuery(query: String): Boolean =
    label.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true)
