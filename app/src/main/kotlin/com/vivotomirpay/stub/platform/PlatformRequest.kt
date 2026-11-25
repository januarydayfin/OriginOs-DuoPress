package com.vivotomirpay.stub.platform

import java.util.concurrent.atomic.AtomicLong

/** Команда, которую может выполнить только Android-хост. */
sealed interface PlatformCommand {
    data object ResolveHandler : PlatformCommand
    data class OpenAppDetails(val packageName: String) : PlatformCommand

    /**
     * Запрашивает явный предпросмотр, даже если виброотклик на двойное нажатие отключён.
     *
     * @property durationMs сохранённая длительность импульса на момент запроса.
     * @property pattern сохранённый паттерн на момент запроса.
     */
    data class PreviewVibration(val durationMs: Int, val pattern: String) : PlatformCommand
    data object OpenAssistantSettings : PlatformCommand
    data object RequestShortcutPermission : PlatformCommand
    data object OpenShizuku : PlatformCommand

    /** Запускает действие через явный вход приложения, не через системный выбор обработчика. */
    data object TestAction : PlatformCommand
}

/**
 * Одноразовый запрос к хосту, не сохраняемый при смерти процесса.
 *
 * @property id идентификатор, уникальный среди запросов всех сценариев в одном процессе.
 */
data class PlatformRequest(val id: Long, val command: PlatformCommand) {
    companion object {
        private val lastId = AtomicLong()

        /** Создаёт новый запрос; все ViewModel используют один счётчик. */
        fun create(command: PlatformCommand): PlatformRequest =
            PlatformRequest(lastId.incrementAndGet(), command)
    }
}
