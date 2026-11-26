package com.krayapp.duopress.menu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.krayapp.duopress.R
import com.krayapp.duopress.ui.common.ActionTile

@Composable
internal fun MenuAddDialog(
    state: MenuEditorState,
    onIntent: (MenuEditorIntent) -> Unit,
    onOpenApps: () -> Unit,
    onOpenShortcuts: () -> Unit,
) {
    val back = { onIntent(MenuEditorIntent.BackFromPicker) }
    AlertDialog(
        onDismissRequest = back,
        title = { Text(stringResource(R.string.editor_add_action)) },
        confirmButton = {
            TextButton(onClick = back, enabled = !state.saving) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorFeedback(state)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(R.string.picker_section_apps, R.drawable.ic_action_app, Modifier.weight(1f), state.canAdd,
                        opensPicker = true, onClick = onOpenApps)
                    ActionTile(R.string.name_shortcuts, R.drawable.ic_action_shortcut, Modifier.weight(1f), state.canAdd,
                        opensPicker = true, onClick = onOpenShortcuts)
                }
                menuSpecialIds.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { id ->
                            val added = state.menuItems.any { sameMenuItem(it, id) }
                            ActionTile(requireNotNull(menuSpecialLabel(id)), menuItemIcon(id), Modifier.weight(1f),
                                state.canAdd && !added, alreadyAdded = added) { onIntent(MenuEditorIntent.PickSpecial(id)) }
                        }
                    }
                }
            }
        },
    )
}
