package com.krayapp.duopress.menu

import com.krayapp.duopress.PrefsHelper

/**
 * Координирует однократный выбор в окне. Все вызовы хоста происходят на Main.
 *
 * @param nowMs монотонное время, в той же шкале, которую использует [scheduleAt].
 * @param scheduleAt планирует задачу независимо от жизненного цикла окна.
 * @param close начинает анимацию скрытия.
 * @param finish завершает Activity после скрытия.
 * @param dispatch выполняет действие и вызывает callback, когда попытка завершилась.
 * @param dispatchCircleToSearch выполняет действие с application context после снятия окна.
 */
internal class MenuSelection(
    private val nowMs: () -> Long,
    private val scheduleAt: (Long, () -> Unit) -> Unit,
    private val close: () -> Unit,
    private val finish: () -> Unit,
    private val dispatch: (String, () -> Unit) -> Unit,
    private val dispatchCircleToSearch: () -> Unit,
) {
    private var selected = false
    private var closing = false
    private var finishing = false
    private var circleDeadline: Long? = null

    /**
     * Блокирует повторные тапы до запуска любого действия.
     *
     * @param id сохранённый ID выбранного действия.
     */
    fun select(id: String) {
        if (selected || closing) return
        selected = true
        try {
            if (id == PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH) {
                circleDeadline = nowMs() + CIRCLE_DELAY_MS
                beginClose()
            } else {
                dispatch(id, ::beginClose)
            }
        } catch (error: Exception) {
            beginClose()
            throw error
        }
    }

    fun dismiss() {
        if (selected) return
        beginClose()
    }

    private fun beginClose() {
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
