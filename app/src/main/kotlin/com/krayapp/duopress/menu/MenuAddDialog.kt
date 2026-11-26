package com.krayapp.duopress.menu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.krayapp.duopress.R
import com.krayapp.duopress.picker.AppSearchList
import com.krayapp.duopress.picker.PickerMessage
import com.krayapp.duopress.picker.PickerSearchField

@Composable
internal fun MenuAddDialog(
    state: MenuEditorState,
    onIntent: (MenuEditorIntent) -> Unit,
    loadIcon: suspend (String) -> ImageBitmap?,
    onOpenShortcuts: () -> Unit,
) {
    val grid = state.picker == MenuPicker.Grid
    val back = { onIntent(MenuEditorIntent.BackFromPicker) }
    AlertDialog(
        onDismissRequest = back,
        title = { Text(stringResource(if (grid) R.string.editor_add_action else R.string.picker_section_apps)) },
        confirmButton = {
            TextButton(onClick = back, enabled = !state.saving) {
                Text(stringResource(if (grid) android.R.string.cancel else R.string.btn_back))
            }
        },
        text = {
            if (grid) Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorFeedback(state)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddTile(R.string.picker_section_apps, R.drawable.ic_action_app, Modifier.weight(1f), state.canAdd,
                        opensPicker = true) { onIntent(MenuEditorIntent.OpenApps) }
                    AddTile(R.string.name_shortcuts, R.drawable.ic_action_shortcut, Modifier.weight(1f), state.canAdd,
                        opensPicker = true, onClick = onOpenShortcuts)
                }
                menuSpecialIds.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { id ->
                            val added = state.menuItems.any { sameMenuItem(it, id) }
                            AddTile(requireNotNull(menuSpecialLabel(id)), menuItemIcon(id), Modifier.weight(1f),
                                state.canAdd && !added, alreadyAdded = added) { onIntent(MenuEditorIntent.PickSpecial(id)) }
                        }
                    }
                }
            } else Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                PickerSearchField(state.query, stringResource(R.string.picker_search_hint)) {
                    onIntent(MenuEditorIntent.SearchApps(it))
                }
                EditorFeedback(state)
                when {
                    state.loadingApps -> PickerMessage(stringResource(R.string.picker_loading_apps))
                    state.appsFailed -> PickerMessage(stringResource(R.string.picker_apps_failed),
                        onRetry = { onIntent(MenuEditorIntent.ReloadApps) })
                    else -> AppSearchList(state.visibleApps, { onIntent(MenuEditorIntent.PickApp(it)) },
                        Modifier.weight(1f, fill = false), loadIcon,
                        alreadyAdded = { pkg -> state.menuItems.any { sameMenuItem(it, "app:$pkg") } },
                        enabled = state.canAdd)
                }
            }
        },
    )
}

@Composable
private fun AddTile(label: Int, icon: Int, modifier: Modifier, enabled: Boolean,
                    alreadyAdded: Boolean = false, opensPicker: Boolean = false, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(painterResource(icon), null)
                if (opensPicker) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
            if (alreadyAdded) Text(stringResource(R.string.menu_item_added), style = MaterialTheme.typography.labelSmall)
        }
    }
}
