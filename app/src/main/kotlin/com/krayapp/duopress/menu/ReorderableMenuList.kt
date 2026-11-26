package com.krayapp.duopress.menu

import android.os.Build
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Ключ строки — позиция в исходном снимке, даже если два сохранённых ID одинаковы. */
@Composable
internal fun ReorderableMenuList(
    items: List<String>,
    enabled: Boolean,
    moveRollback: Long,
    onMove: (Int, Int, List<String>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (rawIndex: Int, handle: Modifier) -> Unit,
) {
    val list = rememberLazyListState()
    val move by rememberUpdatedState(onMove)
    val feedback = LocalHapticFeedback.current
    key(items, moveRollback) {
        val original = remember { items.indices.toList() }
        var order by remember { mutableStateOf(original) }
        var dragging by remember { mutableStateOf<Int?>(null) }
        var awaitingSnapshot by remember { mutableStateOf(false) }
        val interactions = remember { MutableInteractionSource() }
        LaunchedEffect(interactions) {
            interactions.interactions.collect { event ->
                if (event is DragInteraction.Stop || event is DragInteraction.Cancel) {
                    val from = dragging ?: return@collect
                    val to = order.indexOf(from)
                    dragging = null
                    if (event is DragInteraction.Stop && to >= 0 && from != to) {
                        // Сохраняем видимый порядок вплоть до нового снимка или явного отказа.
                        awaitingSnapshot = true
                        move(from, to, items)
                    } else {
                        order = original
                    }
                }
            }
        }
        val reorder = rememberReorderableLazyListState(list) { from, to ->
            if (dragging != null && from.index != to.index) {
                order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
                val tick = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackType.SegmentTick else HapticFeedbackType.VirtualKey
                feedback.performHapticFeedback(tick)
            }
        }
        LazyColumn(modifier, state = list, contentPadding = PaddingValues(bottom = 92.dp)) {
            items(order, key = { it }) { rawIndex ->
                ReorderableItem(reorder, key = rawIndex) {
                    row(rawIndex, Modifier.draggableHandle(enabled = enabled && !awaitingSnapshot && items.size > 1,
                        interactionSource = interactions, onDragStarted = {
                            dragging = rawIndex
                            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }))
                }
            }
        }
    }
}
