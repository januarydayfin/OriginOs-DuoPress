package com.vivotomirpay.stub.data

import com.vivotomirpay.stub.menu.MenuEdit
import com.vivotomirpay.stub.menu.MenuEditResult
import kotlinx.coroutines.flow.Flow

/** Общий источник сохранённых настроек для экранов и системной точки входа. */
interface SettingsRepository {
    /** Отдаёт начальный снимок, затем сообщает об изменениях, в том числе из старого UI. */
    fun observe(): Flow<SettingsSnapshot>

    /** Синхронно читает актуальные настройки. Первый вызов может ждать чтения с диска. */
    fun snapshot(): SettingsSnapshot

    /** Применяет отдельное изменение. Вызывающий выбирает фоновый поток. */
    fun update(change: SettingsChange)

    /**
     * Атомарно читает, проверяет и меняет меню; индексы относятся к сырому массиву.
     * Если данные не меняются, запись не выполняется. Вызывающий выбирает фоновый поток.
     *
     * @param edit правка массива существующих пунктов.
     * @return применена ли правка, а если нет — причина отказа.
     */
    fun editMenu(edit: MenuEdit): MenuEditResult
}
