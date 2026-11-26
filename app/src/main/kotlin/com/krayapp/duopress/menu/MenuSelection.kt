package com.krayapp.duopress.menu

/**
 * Координирует однократный выбор в окне. Все вызовы хоста происходят на Main.
 *
 * @param close начинает анимацию скрытия.
 * @param finish завершает Activity после скрытия.
 * @param dispatch выполняет действие и вызывает callback, когда попытка завершилась.
 */
internal class MenuSelection(
    private val close: () -> Unit,
    private val finish: () -> Unit,
    private val dispatch: (String, () -> Unit) -> Unit,
) {
    private var selected = false
    private var closing = false
    private var finishing = false

    /**
     * Блокирует повторные тапы до запуска любого действия.
     *
     * @param id сохранённый ID выбранного действия.
     */
    fun select(id: String) {
        if (selected || closing) return
        selected = true
        try {
            dispatch(id, ::beginClose)
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

}
