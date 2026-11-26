package com.krayapp.duopress.menu

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.krayapp.duopress.data.SettingsRepository
import com.krayapp.duopress.picker.matchesPickerQuery
import com.krayapp.duopress.platform.AppCatalog
import com.krayapp.duopress.platform.AppEntry
import com.krayapp.duopress.platform.AppScope
import com.krayapp.duopress.platform.PlatformCommand
import com.krayapp.duopress.platform.PlatformRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MenuPicker { None, Grid, Apps }

/**
 * Состояние редактора и его встроенных списков добавления.
 *
 * @property menuItems сырые позиции, включая пустые и повторяющиеся элементы старого меню.
 * @property platformRequest команда, ещё не подтверждённая Android-хостом.
 */
data class MenuEditorState(
    val menuItems: List<String> = emptyList(),
    val loadingMenu: Boolean = true,
    val picker: MenuPicker = MenuPicker.None,
    val query: String = "",
    val appLabels: Map<String, String> = emptyMap(),
    val visibleApps: List<AppEntry> = emptyList(),
    val loadingApps: Boolean = true,
    val appsFailed: Boolean = false,
    val saving: Boolean = false,
    val editFailed: Boolean = false,
    val editResult: MenuEditResult? = null,
    // При отказе снимок меню может не измениться. Отдельный ключ сбрасывает временный порядок UI.
    val moveRollback: Long = 0,
    val rowKeys: List<String> = menuRowKeys(menuItems),
    val exitReady: Boolean = false,
    val platformRequest: PlatformRequest? = null,
    val platformLaunchFailed: Boolean = false,
    internal val acknowledgedPreview: Long? = null,
) {
    val canAdd: Boolean get() = !loadingMenu && !saving && menuItems.size < com.krayapp.duopress.PrefsHelper.MAX_MENU_ITEMS
    val previewEnabled: Boolean
        get() = !loadingMenu && !saving && platformRequest == null && acknowledgedPreview == null
}

sealed interface MenuEditorIntent {
    data object OpenApps : MenuEditorIntent
    data object OpenGrid : MenuEditorIntent
    data object BackFromPicker : MenuEditorIntent
    data object FinishMenuAddition : MenuEditorIntent
    data object ReloadApps : MenuEditorIntent
    data class SearchApps(val query: String) : MenuEditorIntent
    data class PickApp(val packageName: String) : MenuEditorIntent
    data class PickSpecial(val id: String) : MenuEditorIntent
    data class RemoveAt(val index: Int, val expectedRaw: List<String>) : MenuEditorIntent
    data class Move(val from: Int, val to: Int, val expectedRaw: List<String>) : MenuEditorIntent
    data object Preview : MenuEditorIntent
}

/**
 * Редактирует существующее меню, оставляя исполнение предпросмотра Android-хосту.
 *
 * @param repository общий источник сохранённых настроек.
 * @param catalog источник устанавливаемых приложений и значков.
 * @param dispatcher фоновый диспетчер для настроек и поиска.
 */
class MenuEditorViewModel(
    private val repository: SettingsRepository,
    private val catalog: AppCatalog,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(MenuEditorState(
        picker = MenuPicker.entries.firstOrNull { it.name == savedState.get<String>(PICKER_STEP) } ?: MenuPicker.None,
    ))
    val state = mutableState.asStateFlow()
    private var allApps = emptyList<AppEntry>()
    private var appsJob: Job? = null
    private var searchJob: Job? = null
    private var draft = restoredDraft()
    private var removedIndex: Int? = null

    init {
        viewModelScope.launch {
            repository.observe().flowOn(dispatcher).collect { saved ->
                if (state.value.loadingMenu || saved.menuItems != draft.base) {
                    updateDraft(draft.observed(saved.menuItems, removedIndex))
                    removedIndex = null
                }
            }
        }
        loadApps()
    }

    /**
     * Принимает события от редактора и его встроенного списка добавления на Main.
     *
     * @param intent событие текущего экрана или встроенного списка.
     */
    @MainThread
    fun onIntent(intent: MenuEditorIntent) {
        val current = state.value
        when (intent) {
            MenuEditorIntent.OpenApps -> openPicker(MenuPicker.Apps, current)
            MenuEditorIntent.OpenGrid -> openPicker(MenuPicker.Grid, current)
            MenuEditorIntent.BackFromPicker, MenuEditorIntent.FinishMenuAddition -> {
                if (current.saving) return
                searchJob?.cancel()
                val step = if (intent == MenuEditorIntent.BackFromPicker && current.picker == MenuPicker.Apps)
                    MenuPicker.Grid else MenuPicker.None
                savedState[PICKER_STEP] = step.name
                mutableState.update {
                    it.copy(
                        picker = step,
                        query = "",
                        visibleApps = emptyList(),
                        editResult = null,
                        editFailed = false,
                    )
                }
            }
            MenuEditorIntent.ReloadApps -> if (current.picker == MenuPicker.Apps) loadApps()
            is MenuEditorIntent.SearchApps -> if (current.picker == MenuPicker.Apps && intent.query != current.query) {
                mutableState.update { it.copy(query = intent.query, visibleApps = emptyList()) }
                searchApps()
            }
            is MenuEditorIntent.PickApp -> if (current.picker == MenuPicker.Apps && !current.loadingApps &&
                current.visibleApps.any { it.packageName == intent.packageName }
            ) {
                save(MenuEdit.Add("app:${intent.packageName}"))
            }
            is MenuEditorIntent.PickSpecial ->
                if (current.picker == MenuPicker.Grid && intent.id in menuSpecialIds) save(MenuEdit.Add(intent.id))
            is MenuEditorIntent.RemoveAt -> if (current.picker == MenuPicker.None) {
                if (intent.expectedRaw != current.menuItems || intent.index !in draft.order.indices) {
                    mutableState.update { it.copy(editResult = MenuEditResult.NoChange) }
                } else if (!current.saving) {
                    removedIndex = draft.order[intent.index]
                    save(MenuEdit.RemoveAt(draft.order[intent.index], draft.base))
                }
            }
            is MenuEditorIntent.Move -> {
                if (current.picker != MenuPicker.None || current.saving || current.loadingMenu ||
                    intent.expectedRaw != current.menuItems || intent.from !in draft.order.indices || intent.to !in draft.order.indices) {
                    mutableState.update { it.copy(moveRollback = it.moveRollback + 1) }
                } else {
                    updateDraft(draft.moved(intent.from, intent.to))
                }
            }
            MenuEditorIntent.Preview -> if (current.previewEnabled && current.picker == MenuPicker.None) {
                mutableState.update {
                    it.copy(
                        platformRequest = PlatformRequest.create(PlatformCommand.PreviewMenu(visibleMenuItems(current.menuItems))),
                        platformLaunchFailed = false,
                    )
                }
            }
        }
    }

    /**
     * Активный хост на Main подтверждает ID перед явным запуском MenuActivity.
     * Только `true` разрешает старт; второй хост или повторная подписка получат `false`.
     *
     * @param id идентификатор полученного [PlatformRequest].
     * @return разрешено ли активному хосту выполнить именно этот запрос.
     */
    @MainThread
    fun acknowledgePlatformRequest(id: Long): Boolean {
        if (state.value.platformRequest?.id != id) return false
        mutableState.update { it.copy(platformRequest = null, acknowledgedPreview = id) }
        return true
    }

    /**
     * Принимает результат вызова `startActivity`, а не результат выбора в меню.
     *
     * @param id идентификатор подтверждённого запроса.
     * @param launched удалось ли запустить панель.
     */
    @MainThread
    fun onPlatformResult(id: Long, launched: Boolean) {
        if (state.value.acknowledgedPreview != id) return
        mutableState.update { it.copy(acknowledgedPreview = null, platformLaunchFailed = !launched) }
    }

    /**
     * Передаёт загрузку значка строки общему каталогу.
     *
     * @param packageName пакет отображаемого приложения.
     * @return цветной значок или `null`, если требуется запасной ресурс.
     */
    suspend fun icon(packageName: String) = catalog.icon(packageName)

    /** Обе кнопки «Назад» ждут записи до снятия записи навигации и её ViewModel. */
    fun deferExit(): Boolean {
        val current = state.value
        if (current.exitReady) return false
        if (current.saving) return true
        if (!draft.dirty) return false
        if (!current.loadingMenu) save(MenuEdit.Reorder(draft.base, draft.order))
        return true
    }

    private fun restoredDraft(): MenuOrderDraft {
        val base = savedState.get<ArrayList<String>>(DRAFT_BASE).orEmpty()
        val order = savedState.get<IntArray>(DRAFT_ORDER)?.toList()
        return if (order != null && order.sorted() == base.indices.toList()) MenuOrderDraft(base, order)
            else MenuOrderDraft(base)
    }

    private fun updateDraft(next: MenuOrderDraft) {
        draft = next
        if (next.dirty) {
            savedState[DRAFT_BASE] = ArrayList(next.base)
            savedState[DRAFT_ORDER] = next.order.toIntArray()
        } else {
            savedState.remove<ArrayList<String>>(DRAFT_BASE)
            savedState.remove<IntArray>(DRAFT_ORDER)
        }
        val keys = menuRowKeys(next.base)
        mutableState.update { it.copy(menuItems = next.items, rowKeys = next.order.map(keys::get), loadingMenu = false) }
    }

    private fun openPicker(picker: MenuPicker, current: MenuEditorState) {
        val previous = if (picker == MenuPicker.Apps) MenuPicker.Grid else MenuPicker.None
        if (!current.canAdd || current.picker != previous) return
        savedState[PICKER_STEP] = picker.name
        mutableState.update {
            it.copy(picker = picker, query = "", editResult = null, editFailed = false)
        }
        if (picker == MenuPicker.Apps) searchApps()
    }

    private fun loadApps() {
        appsJob?.cancel()
        searchJob?.cancel()
        mutableState.update { it.copy(loadingApps = true, appsFailed = false) }
        appsJob = viewModelScope.launch {
            try {
                val apps = catalog.apps(AppScope.Installed)
                val labels = withContext(dispatcher) { apps.associate { it.packageName to it.label } }
                allApps = apps
                mutableState.update { it.copy(loadingApps = false, appLabels = labels) }
                searchApps()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                mutableState.update { it.copy(loadingApps = false, appsFailed = true) }
            }
        }
    }

    private fun searchApps() {
        searchJob?.cancel()
        val current = state.value
        if (current.picker != MenuPicker.Apps || current.loadingApps || current.appsFailed) return
        val apps = allApps
        val query = current.query.trim()
        searchJob = viewModelScope.launch {
            val visible = withContext(dispatcher) { apps.filter { it.matchesPickerQuery(query) } }
            mutableState.update {
                if (it.picker == MenuPicker.Apps && it.query == current.query) it.copy(visibleApps = visible) else it
            }
        }
    }

    private fun save(edit: MenuEdit) {
        if (state.value.saving || state.value.loadingMenu) return
        mutableState.update { it.copy(saving = true, editResult = null, editFailed = false) }
        viewModelScope.launch {
            try {
                val result = withContext(dispatcher) { repository.editMenu(edit) }
                var orderSaved = edit is MenuEdit.Reorder && result == MenuEditResult.Applied
                if (edit is MenuEdit.Reorder && result == MenuEditResult.NoChange) {
                    val latest = withContext(dispatcher) { repository.snapshot().menuItems }
                    orderSaved = latest == edit.permutation.map(edit.expectedRaw::get)
                    updateDraft(draft.observed(latest, null))
                }
                val closePicker = result == MenuEditResult.Applied && edit is MenuEdit.Add
                if (closePicker) savedState[PICKER_STEP] = MenuPicker.None.name
                mutableState.update {
                    it.copy(
                        picker = if (closePicker) MenuPicker.None else it.picker,
                        query = if (closePicker) "" else it.query,
                        visibleApps = if (closePicker) emptyList() else it.visibleApps,
                        saving = false,
                        editFailed = edit is MenuEdit.Reorder && !orderSaved,
                        editResult = result.takeIf { value -> value != MenuEditResult.Applied },
                        exitReady = orderSaved,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                mutableState.update {
                    it.copy(saving = false, editFailed = true)
                }
            }
        }
    }

    private companion object {
        const val PICKER_STEP = "menu_editor_picker"
        const val DRAFT_BASE = "menu_editor_base"
        const val DRAFT_ORDER = "menu_editor_order"
    }
}
