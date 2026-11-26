package com.krayapp.duopress.diagnostics

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krayapp.duopress.data.SettingsChange
import com.krayapp.duopress.data.SettingsRepository
import com.krayapp.duopress.platform.HandlerProbe
import com.krayapp.duopress.platform.HandlerSnapshot
import com.krayapp.duopress.platform.HandlerStatus
import com.krayapp.duopress.platform.PlatformCommand
import com.krayapp.duopress.platform.PlatformRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Журнал, маршрутизация Vivo Intent и состояние запроса к системному хосту.
 *
 * @property triggerLog `null` до первого снимка репозитория, пустая строка для пустого журнала.
 * @property platformRequest запрос, который ещё не подтверждён хостом.
 * @property handlerRefreshing старый статус виден, но выдача и подтверждение команд настройки запрещены.
 */
data class DiagnosticsUiState(
    val triggerLog: String? = null,
    val handler: HandlerSnapshot = HandlerSnapshot(HandlerStatus.Loading, emptyList()),
    val platformRequest: PlatformRequest? = null,
    val platformLaunchFailed: Boolean = false,
    internal val inFlightRequest: PlatformRequest? = null,
    internal val handlerRefreshing: Boolean = false,
) {
    internal val platformBusy: Boolean get() = platformRequest != null || inFlightRequest != null
    internal val canOpenHandlerSettings: Boolean
        get() = !platformBusy && !handlerRefreshing && handler.status != HandlerStatus.Loading
}

/** События диагностики. [TestAction] не запускает настройку или проверку текущего назначения. */
sealed interface DiagnosticsIntent {
    data object Resume : DiagnosticsIntent
    data object ConfigureHandler : DiagnosticsIntent
    data object OpenAppDetails : DiagnosticsIntent
    data object TestAction : DiagnosticsIntent
    data object ClearLog : DiagnosticsIntent
}

/**
 * Проверяет маршрутизацию и показывает журнал, не смешивая тест действия с назначением обработчика.
 *
 * @param repository источник журнала того же приложения, откуда его читает старый экран.
 * @param handlerProbe проверяет системный выбор и список зарегистрированных обработчиков.
 * @param appPackageName фактический пакет текущего варианта для системных сведений о приложении.
 * @param dispatcher фоновый диспетчер для пробы и очистки журнала.
 */
class DiagnosticsViewModel(
    private val repository: SettingsRepository,
    private val handlerProbe: HandlerProbe,
    private val appPackageName: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiagnosticsUiState())
    val state = mutableState.asStateFlow()
    private var handlerRefresh: Job? = null

    init {
        viewModelScope.launch {
            repository.observe().collect { saved ->
                mutableState.update { it.copy(triggerLog = saved.triggerLog) }
            }
        }
    }

    /**
     * Принимает интенты экрана в главном потоке.
     *
     * @param intent событие пользователя или жизненного цикла экрана.
     */
    @MainThread
    fun onIntent(intent: DiagnosticsIntent) {
        when (intent) {
            DiagnosticsIntent.Resume -> refreshHandler()
            DiagnosticsIntent.ConfigureHandler -> configureHandler()
            DiagnosticsIntent.OpenAppDetails -> openAppDetails()
            DiagnosticsIntent.TestAction -> requestPlatformCommand(PlatformCommand.TestAction)
            DiagnosticsIntent.ClearLog -> viewModelScope.launch(dispatcher) {
                repository.update(SettingsChange.ClearLog)
            }
        }
    }

    /**
     * Хост подтверждает запрос непосредственно перед исполнением на главном потоке.
     *
     * @param id идентификатор запроса, полученного хостом.
     * @return `true` только для актуального необработанного запроса, который не отозван проверкой.
     */
    @MainThread
    fun acknowledgePlatformRequest(id: Long): Boolean {
        val current = state.value
        val request = current.platformRequest ?: return false
        if (request.id != id || (current.handlerRefreshing && request.command != PlatformCommand.TestAction)) {
            return false
        }
        mutableState.value = current.copy(platformRequest = null, inFlightRequest = request)
        return true
    }

    /**
     * Только успех системной настройки запускает фоновую проверку; тест обработчика не меняет.
     *
     * @param id идентификатор подтверждённого запроса; несовпадающий результат игнорируется.
     * @param launched удалось ли выполнить команду, независимо от выбора пользователя на системном экране.
     */
    @MainThread
    fun onPlatformResult(id: Long, launched: Boolean) {
        val current = state.value
        val acknowledged = current.inFlightRequest ?: return
        if (acknowledged.id != id) return
        val recheckHandler = launched && acknowledged.command != PlatformCommand.TestAction
        mutableState.value = current.copy(
            inFlightRequest = null,
            platformLaunchFailed = !launched,
            handlerRefreshing = recheckHandler || current.handlerRefreshing,
        )
        if (recheckHandler) startHandlerProbe()
    }

    private fun configureHandler() {
        val current = state.value
        if (!current.canOpenHandlerSettings || current.handler.status != HandlerStatus.NoSpecificHandler) return
        requestPlatformCommand(PlatformCommand.ResolveHandler)
    }

    private fun openAppDetails() {
        val current = state.value
        if (!current.canOpenHandlerSettings) return
        val other = current.handler.status as? HandlerStatus.OtherApp
        requestPlatformCommand(PlatformCommand.OpenAppDetails(other?.packageName ?: appPackageName))
    }

    private fun requestPlatformCommand(command: PlatformCommand) {
        val current = state.value
        if (current.platformBusy) return
        mutableState.value = current.copy(
            platformRequest = PlatformRequest.create(command),
            platformLaunchFailed = false,
        )
    }

    private fun refreshHandler() {
        // Снимок ещё старый: отзываем неподтверждённую команду до запуска пробы.
        mutableState.update {
            it.copy(
                handlerRefreshing = true,
                platformRequest = it.platformRequest?.takeIf { request -> request.command == PlatformCommand.TestAction },
            )
        }
        startHandlerProbe()
    }

    private fun startHandlerProbe() {
        handlerRefresh?.cancel()
        handlerRefresh = viewModelScope.launch {
            val handler = withContext(dispatcher) { handlerProbe.inspect() }
            mutableState.update { it.copy(handler = handler, handlerRefreshing = false) }
        }
    }
}
