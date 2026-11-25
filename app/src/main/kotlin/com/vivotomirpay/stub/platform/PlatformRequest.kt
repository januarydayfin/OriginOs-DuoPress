package com.vivotomirpay.stub.platform

import java.util.concurrent.atomic.AtomicLong

/** Команда, которую может выполнить только Android-хост. */
sealed interface PlatformCommand {
    data object ResolveHandler : PlatformCommand
    data class OpenAppDetails(val packageName: String) : PlatformCommand
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
