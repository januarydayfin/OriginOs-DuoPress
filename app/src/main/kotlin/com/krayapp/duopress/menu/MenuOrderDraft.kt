package com.krayapp.duopress.menu

/** Индексы относятся к исходному JSON: даже старые дубликаты остаются разными строками. */
internal data class MenuOrderDraft(
    val base: List<String>,
    val order: List<Int> = base.indices.toList(),
) {
    val items get() = order.map(base::get)
    val dirty get() = order != base.indices.toList()

    fun moved(from: Int, to: Int) = copy(order = order.toMutableList().apply { add(to, removeAt(from)) })

    /** Добавления (включая ярлык с дочернего экрана) и удаления не фиксируют черновой порядок. */
    fun observed(next: List<String>, removedIndex: Int?): MenuOrderDraft {
        if (next == base) return this
        if (next.take(base.size) == base)
            return MenuOrderDraft(next, order + (base.size until next.size))
        if (next.size == base.size - 1) {
            val index = (listOfNotNull(removedIndex) + base.indices).firstOrNull { i ->
                i in base.indices && base.filterIndexed { j, _ -> j != i } == next
            }
            if (index != null) return MenuOrderDraft(next, order.filter { it != index }.map { if (it > index) it - 1 else it })
        }
        return MenuOrderDraft(next)
    }
}

internal fun menuRowKeys(raw: List<String>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return raw.map { id ->
        val occurrence = occurrences.getOrDefault(id, 0)
        occurrences[id] = occurrence + 1
        "$occurrence:$id"
    }
}
