package com.vivotomirpay.stub.settings

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vivotomirpay.stub.data.SettingsChange
import com.vivotomirpay.stub.data.SettingsRepository
import com.vivotomirpay.stub.data.SettingsSnapshot
import com.vivotomirpay.stub.platform.DeviceStatus
import com.vivotomirpay.stub.platform.DeviceStatusProbe
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
 * Снимок настроек и состояние экрана.
 *
 * @property settings сохранённые настройки, `null` до первого снимка репозитория.
 * @property device результат последней пробы, `null` до первого возобновления.
 * @property durationDraftMs значение слайдера до подтверждения сохранения.
 * @property platformRequest запрос, который ещё не подтверждён хостом.
 */
data class SettingsUiState(
    val settings: SettingsSnapshot? = null,
    val device: DeviceStatus? = null,
    val durationDraftMs: Int? = null,
    val platformRequest: PlatformRequest? = null,
    val platformLaunchFailed: Boolean = false,
    internal val inFlightRequest: PlatformRequest? = null,
) {
    internal val platformBusy: Boolean get() = platformRequest != null || inFlightRequest != null
}

/** Действия экрана. [ChangeDuration] — изменение черновика; [CommitDuration] сохраняет его и запрашивает предпросмотр. */
sealed interface SettingsIntent {
    data object Resume : SettingsIntent
    /** Переключает сохранённое значение, а не отображаемый снимок. */
    data object ToggleVibration : SettingsIntent
    data class ChangeDuration(val ms: Int) : SettingsIntent
    data object CommitDuration : SettingsIntent
    data class SelectPattern(val pattern: String) : SettingsIntent
    data class SelectVpnClient(val client: String) : SettingsIntent
    data object OpenAssistantSettings : SettingsIntent
}

/**
 * Меняет сохранённые настройки через общий репозиторий, системные команды передаёт хосту.
 *
 * @param repository источник сохранённых настроек, в приложении — общий legacy-репозиторий.
 * @param probe проверяет VPN и системного ассистента.
 * @param dispatcher фоновый диспетчер для записи настроек, снимков предпросмотра и проб.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val probe: DeviceStatusProbe,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state = mutableState.asStateFlow()
    private val settingsWrites = Mutex()
    private val queuedPreviews = ArrayDeque<PlatformRequest>()
    private var deviceRefresh: Job? = null
    private var durationRevision = 0L

    init {
        viewModelScope.launch {
            repository.observe().collect { saved ->
                mutableState.update { it.copy(settings = saved) }
            }
        }
    }

    /**
     * Принимает интенты экрана в главном потоке. Записи и пробы выполняются вне него.
     *
     * @param intent событие пользователя или жизненного цикла экрана.
     */
    @MainThread
    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.Resume -> refreshDevice()
            SettingsIntent.ToggleVibration -> save(preview = true) {
                SettingsChange.VibrationEnabled(!repository.snapshot().vibrateEnabled)
            }
            is SettingsIntent.ChangeDuration -> {
                durationRevision++
                mutableState.update { it.copy(durationDraftMs = intent.ms.coerceIn(vibrationDurationRange)) }
            }
            SettingsIntent.CommitDuration -> {
                val ms = state.value.durationDraftMs ?: state.value.settings?.vibrateDurationMs ?: return
                save(preview = true, committedRevision = ++durationRevision) { SettingsChange.VibrationDuration(ms) }
            }
            is SettingsIntent.SelectPattern -> if (intent.pattern in vibrationPatterns) {
                save(preview = true) { SettingsChange.VibrationPattern(intent.pattern) }
            }
            is SettingsIntent.SelectVpnClient -> if (intent.client in vpnClients) {
                save { SettingsChange.VpnClient(intent.client) }
            }
            SettingsIntent.OpenAssistantSettings -> if (!state.value.platformBusy) {
                val request = PlatformRequest.create(PlatformCommand.OpenAssistantSettings)
                mutableState.update { it.copy(platformRequest = request, platformLaunchFailed = false) }
            }
        }
    }

    /**
     * Хост подтверждает запрос непосредственно перед исполнением на главном потоке.
     *
     * @param id идентификатор запроса, полученного хостом.
     * @return `true` только для актуального запроса, который подтверждается впервые.
     */
    @MainThread
    fun acknowledgePlatformRequest(id: Long): Boolean {
        val current = state.value
        val request = current.platformRequest ?: return false
        if (request.id != id) return false
        mutableState.value = current.copy(platformRequest = null, inFlightRequest = request)
        return true
    }

    /**
     * Завершает подтверждённый запрос и публикует следующий предпросмотр, если он есть.
     *
     * @param id идентификатор подтверждённого запроса; несовпадающий результат игнорируется.
     * @param launched удалось ли выполнить команду, независимо от выбора пользователя на системном экране.
     */
    @MainThread
    fun onPlatformResult(id: Long, launched: Boolean) {
        val current = state.value
        val acknowledged = current.inFlightRequest ?: return
        if (acknowledged.id != id) return
        mutableState.value = current.copy(
            platformRequest = queuedPreviews.removeFirstOrNull(),
            inFlightRequest = null,
            platformLaunchFailed = !launched,
        )
        if (launched && acknowledged.command == PlatformCommand.OpenAssistantSettings) refreshDevice()
    }

    private fun refreshDevice() {
        deviceRefresh?.cancel()
        deviceRefresh = viewModelScope.launch {
            val device = withContext(dispatcher) { probe.inspect() }
            mutableState.update { it.copy(device = device) }
        }
    }

    private fun save(preview: Boolean = false, committedRevision: Long? = null, change: () -> SettingsChange) {
        viewModelScope.launch {
            settingsWrites.withLock {
                val command = withContext(dispatcher) {
                    repository.update(change())
                    if (preview) {
                        val saved = repository.snapshot()
                        PlatformCommand.PreviewVibration(saved.vibrateDurationMs, saved.vibratePattern)
                    } else null
                }
                if (committedRevision == durationRevision) {
                    mutableState.update { it.copy(durationDraftMs = null) }
                }
                if (command != null) {
                    val request = PlatformRequest.create(command)
                    if (state.value.platformBusy) queuedPreviews.addLast(request)
                    else mutableState.update { it.copy(platformRequest = request, platformLaunchFailed = false) }
                }
            }
        }
    }
}

internal val vibrationDurationRange = 20..500
private val vibrationPatterns = setOf("single", "double", "long")
private val vpnClients = setOf("auto", "happ", "incy")
