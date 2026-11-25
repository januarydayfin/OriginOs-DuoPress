package com.vivotomirpay.stub.menu

import com.vivotomirpay.stub.PrefsHelper

/**
 * Координирует однократный выбор в окне. Все вызовы хоста происходят на Main.
 *
 * @param nowMs монотонное время, в той же шкале, которую использует [scheduleAt].
 * @param scheduleAt планирует задачу независимо от жизненного цикла окна.
 * @param close начинает анимацию скрытия.
 * @param finish завершает Activity после скрытия.
 * @param dispatchImmediately выполняет действия, для которых не нужен снимок экрана.
 * @param dispatchCircleToSearch выполняет действие с application context после снятия окна.
 */
internal class MenuSelection(
    private val nowMs: () -> Long,
    private val scheduleAt: (Long, () -> Unit) -> Unit,
    private val close: () -> Unit,
    private val finish: () -> Unit,
    private val dispatchImmediately: (String) -> Unit,
    private val dispatchCircleToSearch: () -> Unit,
) {
    private var closing = false
    private var finishing = false
    private var circleDeadline: Long? = null

    /**
     * Блокирует повторные тапы до запуска любого действия.
     *
     * @param id сохранённый ID выбранного действия.
     */
    fun select(id: String) {
        if (closing) return
        closing = true
        try {
            if (id == PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH) {
                circleDeadline = nowMs() + CIRCLE_DELAY_MS
            } else {
                dispatchImmediately(id)
            }
        } finally {
            close()
        }
    }

    fun dismiss() {
        if (closing) return
        closing = true
        close()
    }

    fun onHidden() {
        if (!closing || finishing) return
        finishing = true
        finish()
    }

    /** Разрешает отложенный запуск только после начала finish и снятия окна. */
    fun onWindowDetached() {
        if (!finishing) return
        val deadline = circleDeadline ?: return
        circleDeadline = null
        scheduleAt(maxOf(deadline, nowMs()), dispatchCircleToSearch)
    }

    private companion object {
        const val CIRCLE_DELAY_MS = 550L
    }
}
