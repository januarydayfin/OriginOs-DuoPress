package com.krayapp.duopress.menu

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Ключ строки — позиция в исходном снимке, даже если два сохранённых ID одинаковы. */
@Composable
internal fun ReorderableMenuList(
    items: List<String>,
    enabled: Boolean,
    onMove: (Int, Int, List<String>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (rawIndex: Int, handle: Modifier) -> Unit,
) {
    val list = rememberLazyListState()
    val move by rememberUpdatedState(onMove)
    key(items) {
        val original = remember { items.indices.toList() }
        var order by remember { mutableStateOf(original) }
        var dragging by remember { mutableStateOf<Int?>(null) }
        val interactions = remember { MutableInteractionSource() }
        LaunchedEffect(interactions) {
            interactions.interactions.collect { event ->
                if (event is DragInteraction.Stop || event is DragInteraction.Cancel) {
                    val from = dragging
                    val to = order.indexOf(from)
                    dragging = null
                    order = original
                    if (event is DragInteraction.Stop && from != null && to >= 0 && from != to)
                        move(from, to, items)
                }
            }
        }
        val reorder = rememberReorderableLazyListState(list) { from, to ->
            if (dragging != null) order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
        }
        LazyColumn(modifier, state = list, contentPadding = PaddingValues(bottom = 92.dp)) {
            items(order, key = { it }) { rawIndex ->
                ReorderableItem(reorder, key = rawIndex) {
                    row(rawIndex, Modifier.draggableHandle(enabled = enabled && items.size > 1,
                        interactionSource = interactions, onDragStarted = { dragging = rawIndex }))
                }
            }
        }
    }
}
