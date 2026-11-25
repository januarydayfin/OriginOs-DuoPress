package com.vivotomirpay.stub.menu

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vivotomirpay.stub.data.SettingsRepository
import com.vivotomirpay.stub.picker.matchesPickerQuery
import com.vivotomirpay.stub.platform.AppCatalog
import com.vivotomirpay.stub.platform.AppEntry
import com.vivotomirpay.stub.platform.AppScope
import com.vivotomirpay.stub.platform.PlatformCommand
import com.vivotomirpay.stub.platform.PlatformRequest
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

enum class MenuPicker { None, Apps, Specials }

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
    val platformRequest: PlatformRequest? = null,
    val platformLaunchFailed: Boolean = false,
    internal val acknowledgedPreview: Long? = null,
) {
    val previewEnabled: Boolean
        get() = !loadingMenu && !saving && platformRequest == null && acknowledgedPreview == null
}

sealed interface MenuEditorIntent {
    data object OpenApps : MenuEditorIntent
    data object OpenSpecials : MenuEditorIntent
    data object ClosePicker : MenuEditorIntent
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
) : ViewModel() {
    private val mutableState = MutableStateFlow(MenuEditorState())
    val state = mutableState.asStateFlow()
    private var allApps = emptyList<AppEntry>()
    private var appsJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observe().flowOn(dispatcher).collect { saved ->
                mutableState.update { it.copy(menuItems = saved.menuItems, loadingMenu = false) }
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
            MenuEditorIntent.OpenSpecials -> openPicker(MenuPicker.Specials, current)
            MenuEditorIntent.ClosePicker -> {
                searchJob?.cancel()
                mutableState.update {
                    it.copy(
                        picker = MenuPicker.None,
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
                if (current.picker == MenuPicker.Specials && intent.id in menuSpecialIds) save(MenuEdit.Add(intent.id))
            is MenuEditorIntent.RemoveAt -> if (current.picker == MenuPicker.None) {
                save(MenuEdit.RemoveAt(intent.index, intent.expectedRaw))
            }
            is MenuEditorIntent.Move -> if (current.picker == MenuPicker.None) {
                save(MenuEdit.Move(intent.from, intent.to, intent.expectedRaw))
            }
            MenuEditorIntent.Preview -> if (current.previewEnabled && current.picker == MenuPicker.None) {
                mutableState.update {
                    it.copy(
                        platformRequest = PlatformRequest.create(PlatformCommand.PreviewMenu),
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

    private fun openPicker(picker: MenuPicker, current: MenuEditorState) {
        if (current.loadingMenu || current.saving || current.picker != MenuPicker.None) return
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
                mutableState.update {
                    val closePicker = result == MenuEditResult.Applied && edit is MenuEdit.Add
                    it.copy(
                        picker = if (closePicker) MenuPicker.None else it.picker,
                        query = if (closePicker) "" else it.query,
                        visibleApps = if (closePicker) emptyList() else it.visibleApps,
                        saving = false,
                        editResult = result.takeIf { value -> value != MenuEditResult.Applied },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                mutableState.update { it.copy(saving = false, editFailed = true) }
            }
        }
    }
}
