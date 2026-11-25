package com.vivotomirpay.stub.menu

import com.vivotomirpay.stub.PrefsHelper

/** Изменение существующего JSON-массива. Индексы — позиции в массиве, не в панели запуска. */
sealed interface MenuEdit {
    /** Добавляет новый ID в конец, не меняя существующие значения. */
    data class Add(val id: String) : MenuEdit

    /** Удаляет сырую позицию, в том числе пустой или повторяющийся пункт. */
    data class RemoveAt(val index: Int) : MenuEdit

    /** [to] — итоговый индекс элемента; остальные элементы сдвигаются. */
    data class Move(val from: Int, val to: Int) : MenuEdit
}

enum class MenuEditResult { Applied, Duplicate, Full, NoChange }

/**
 * Строит список для панели и счётчика, не изменяя исходные значения.
 *
 * @param raw позиции из сохранённого массива в исходном порядке.
 * @return первые 16 непустых уникальных ID, исключая рекурсивный пункт меню.
 */
fun visibleMenuItems(raw: List<String>): List<String> = raw.asSequence()
    .map(String::legacyMenuTrim)
    .filter { it.isNotEmpty() && it != PrefsHelper.SPECIAL_MENU }
    .distinct()
    .take(PrefsHelper.MAX_MENU_ITEMS)
    .toList()

// Java String.trim() у прежней панели удалял только символы <= U+0020.
internal fun String.legacyMenuTrim(): String = trim { it <= ' ' }

internal val menuSpecialIds = listOf(
    PrefsHelper.SPECIAL_TORCH,
    PrefsHelper.SPECIAL_CAMERA,
    PrefsHelper.SPECIAL_VPN,
    PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH,
)

/**
 * Проверяет правку и сохраняет типы неизменённых значений, включая старые данные вне формата.
 *
 * @param raw исходные значения массива.
 * @param edit правка с индексами исходного массива.
 * @param idOf преобразование сохранённого значения в строку для проверки дубликатов.
 * @param itemFromId преобразование нового ID в сохраняемое значение.
 * @return результат и новый список только при применённом изменении; иначе исходный.
 */
internal fun <T> applyMenuEdit(
    raw: List<T>,
    edit: MenuEdit,
    idOf: (T) -> String,
    itemFromId: (String) -> T,
): Pair<MenuEditResult, List<T>> {
    val newItems = when (edit) {
        is MenuEdit.Add -> {
            val id = edit.id.legacyMenuTrim()
            if (id.isEmpty() || id == PrefsHelper.SPECIAL_MENU) return MenuEditResult.NoChange to raw
            if (raw.any { idOf(it).legacyMenuTrim() == id }) return MenuEditResult.Duplicate to raw
            if (raw.size >= PrefsHelper.MAX_MENU_ITEMS) return MenuEditResult.Full to raw
            raw + itemFromId(id)
        }
        is MenuEdit.RemoveAt -> {
            if (edit.index !in raw.indices) return MenuEditResult.NoChange to raw
            raw.toMutableList().apply { removeAt(edit.index) }
        }
        is MenuEdit.Move -> {
            if (edit.from !in raw.indices || edit.to !in raw.indices || edit.from == edit.to) {
                return MenuEditResult.NoChange to raw
            }
            raw.toMutableList().apply { add(edit.to, removeAt(edit.from)) }
        }
    }
    return if (newItems == raw) MenuEditResult.NoChange to raw else MenuEditResult.Applied to newItems
}
