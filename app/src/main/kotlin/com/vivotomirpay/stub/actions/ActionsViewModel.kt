package com.vivotomirpay.stub.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vivotomirpay.stub.PrefsHelper
import com.vivotomirpay.stub.data.SettingsChange
import com.vivotomirpay.stub.data.SettingsRepository
import com.vivotomirpay.stub.data.SettingsSnapshot
import com.vivotomirpay.stub.platform.HandlerProbe
import com.vivotomirpay.stub.platform.HandlerSnapshot
import com.vivotomirpay.stub.platform.HandlerStatus
import com.vivotomirpay.stub.platform.PlatformCommand
import com.vivotomirpay.stub.platform.PlatformRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Состояние общего сценария «Действия» и экрана блокировки.
 *
 * @property settings сохранённые настройки; `null`, пока не получен первый снимок.
 * @property platformRequest запрос, ещё не подтверждённый исполняющим хостом.
 * @property handlerRefreshing пока проверка не завершена, прежний статус виден, но команды заблокированы.
 */
data class ActionsState(
    val settings: SettingsSnapshot? = null,
    val targetLabel: String? = null,
    val lockscreenLabel: String? = null,
    val handler: HandlerSnapshot = HandlerSnapshot(HandlerStatus.Loading, emptyList()),
    val platformRequest: PlatformRequest? = null,
    val platformLaunchFailed: Boolean = false,
    internal val inFlightRequestId: Long? = null,
    internal val handlerRefreshing: Boolean = false,
) {
    internal val platformBusy: Boolean get() = handlerRefreshing || platformRequest != null || inFlightRequestId != null
}

sealed interface ActionsIntent {
    data object RefreshHandler : ActionsIntent
    data object ResolveHandler : ActionsIntent
    data class OpenHandlerDetails(val packageName: String) : ActionsIntent
    data class SetLockscreenEnabled(val enabled: Boolean) : ActionsIntent
    data class SelectLockscreenAction(val id: String) : ActionsIntent
}

/**
 * Хранит состояние двух экранов; Android-навигацию и исполнение внешних команд оставляет хосту.
 *
 * @param repository общий источник сохранённых настроек.
 * @param handlerProbe источник текущего результата маршрутизации Vivo Intent.
 * @param labelResolver источник подписей для сохранённых действий; без него отображаются ID.
 * @param dispatcher фоновый диспетчер для чтений, записей и платформенных проб.
 */
class ActionsViewModel(
    private val repository: SettingsRepository,
    private val handlerProbe: HandlerProbe,
    private val labelResolver: ActionLabelResolver = ActionLabelResolver { id, _ -> id },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ActionsState())
    val state = mutableState.asStateFlow()
    private val settingsWrites = Mutex()
    private var handlerRefresh: Job? = null

    init {
        viewModelScope.launch(dispatcher) {
            repository.observe().collect { settings ->
                val targetLabel = labelResolver.resolve(settings.targetId, settings.vpnClient)
                val lockscreenLabel = labelResolver.resolve(settings.lockscreenActionId, settings.vpnClient)
                mutableState.update {
                    it.copy(settings = settings, targetLabel = targetLabel, lockscreenLabel = lockscreenLabel)
                }
            }
        }
        refreshHandler()
    }

    /** Принимает намерение от экрана или от хоста в главном потоке. */
    fun onIntent(intent: ActionsIntent) {
        when (intent) {
            ActionsIntent.RefreshHandler -> refreshHandler()
            ActionsIntent.ResolveHandler -> requestPlatformCommand(PlatformCommand.ResolveHandler)
            is ActionsIntent.OpenHandlerDetails ->
                requestPlatformCommand(PlatformCommand.OpenAppDetails(intent.packageName))
            is ActionsIntent.SetLockscreenEnabled -> save(SettingsChange.LockscreenEnabled(intent.enabled))
            is ActionsIntent.SelectLockscreenAction -> if (intent.id in lockscreenActionIds) {
                save(SettingsChange.LockscreenAction(intent.id))
            }
        }
    }

    /**
     * Помечает запрос исполняемым перед вызовом Android, чтобы другой хост не повторил запуск.
     *
     * @param id идентификатор запроса, который получил хост.
     * @return `true` только у хоста, который первым подтвердил ещё актуальный запрос.
     */
    fun acknowledgePlatformRequest(id: Long): Boolean {
        while (true) {
            val current = state.value
            if (current.handlerRefreshing || current.platformRequest?.id != id) return false
            val acknowledged = current.copy(platformRequest = null, inFlightRequestId = id)
            if (mutableState.compareAndSet(current, acknowledged)) return true
        }
    }

    /**
     * Завершает исполнение подтверждённого запроса на Main. После успешного возврата перечитывает статус.
     *
     * @param id идентификатор запроса, который исполнил хост.
     * @param launched удалось ли открыть системный экран, независимо от выбора пользователя в нём.
     */
    fun onPlatformResult(id: Long, launched: Boolean) {
        while (true) {
            val current = state.value
            if (current.inFlightRequestId != id) return
            val completed = current.copy(
                inFlightRequestId = null,
                platformLaunchFailed = !launched,
                handlerRefreshing = launched || current.handlerRefreshing,
            )
            if (mutableState.compareAndSet(current, completed)) break
        }
        if (launched) startHandlerProbe()
    }

    private fun refreshHandler() {
        mutableState.update { it.copy(handlerRefreshing = true, platformRequest = null) }
        startHandlerProbe()
    }

    private fun startHandlerProbe() {
        handlerRefresh?.cancel()
        handlerRefresh = viewModelScope.launch {
            val handler = withContext(dispatcher) { handlerProbe.inspect() }
            mutableState.update { it.copy(handler = handler, handlerRefreshing = false) }
        }
    }

    private fun requestPlatformCommand(command: PlatformCommand) {
        var request: PlatformRequest? = null
        mutableState.update {
            if (it.platformBusy || !command.matches(it.handler.status)) it
            else it.copy(
                platformRequest = request ?: PlatformRequest.create(command).also { created -> request = created },
                platformLaunchFailed = false,
            )
        }
    }

    private fun save(change: SettingsChange) {
        viewModelScope.launch {
            settingsWrites.withLock {
                withContext(dispatcher) { repository.update(change) }
            }
        }
    }
}

private fun PlatformCommand.matches(status: HandlerStatus): Boolean = when (this) {
    PlatformCommand.ResolveHandler -> status == HandlerStatus.NoSpecificHandler
    is PlatformCommand.OpenAppDetails -> status is HandlerStatus.OtherApp && status.packageName == packageName
}

internal val lockscreenActionIds = setOf(PrefsHelper.SPECIAL_TORCH, PrefsHelper.SPECIAL_CAMERA)
