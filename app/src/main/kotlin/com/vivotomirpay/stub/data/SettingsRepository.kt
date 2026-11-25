package com.vivotomirpay.stub.data

import kotlinx.coroutines.flow.Flow

/** Общий источник сохранённых настроек для экранов и системной точки входа. */
interface SettingsRepository {
    /** Отдаёт начальный снимок, затем сообщает об изменениях, в том числе из старого UI. */
    fun observe(): Flow<SettingsSnapshot>

    /** Синхронно читает актуальные настройки. Первый вызов может ждать чтения с диска. */
    fun snapshot(): SettingsSnapshot

    /** Применяет отдельное изменение. Вызывающий выбирает фоновый поток. */
    fun update(change: SettingsChange)
}
