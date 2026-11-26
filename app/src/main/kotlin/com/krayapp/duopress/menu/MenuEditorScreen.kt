package com.krayapp.duopress.menu

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.krayapp.duopress.PrefsHelper
import com.krayapp.duopress.R
import com.krayapp.duopress.picker.PickerMessage
import com.krayapp.duopress.ui.common.AppIcon
import com.krayapp.duopress.ui.theme.AppTheme

/**
 * Показывает редактор и встроенные списки добавления. Хост отвечает за верхнюю панель маршрута.
 *
 * @param state текущее состояние редактора.
 * @param onIntent обработчик действий, в том числе запроса предпросмотра.
 * @param modifier модификатор корневой области редактора.
 * @param loadIcon загрузчик цветного значка по имени пакета.
 * @param showDialogs разрешает показывать сетку добавления на активном маршруте.
 * @param onOpenShortcuts открывает выбор ярлыков.
 * @param onOpenApps открывает выбор приложений.
 * @param shizukuInstalled наличие пакета Shizuku; `null`, пока идёт проверка.
 * @param onOpenShizukuStore открывает страницу установки и сообщает, удалось ли это.
 */
@Composable
fun MenuEditorScreen(
    state: MenuEditorState,
    onIntent: (MenuEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
    loadIcon: suspend (String) -> ImageBitmap? = { null },
    showDialogs: Boolean = true,
    onOpenShortcuts: () -> Unit = {},
    onOpenApps: () -> Unit = {},
    shizukuInstalled: Boolean? = null,
    onOpenShizukuStore: () -> Boolean = { false },
) {
    Box(modifier.fillMaxSize()) {
        EditorRows(state, onIntent, Modifier, loadIcon)
        if (state.canAdd) FloatingActionButton(
            onClick = { onIntent(MenuEditorIntent.OpenGrid) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Default.Add, stringResource(R.string.editor_add_action))
        }
        if (showDialogs && state.picker == MenuPicker.Grid) {
            MenuAddDialog(state, onIntent, onOpenApps, onOpenShortcuts, shizukuInstalled, onOpenShizukuStore)
        }
    }
}

@Composable
private fun EditorRows(
    state: MenuEditorState,
    onIntent: (MenuEditorIntent) -> Unit,
    modifier: Modifier,
    loadIcon: suspend (String) -> ImageBitmap?,
) {
    val renderedMenu = state.menuItems
    Column(modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.editor_order))
                Text(stringResource(R.string.editor_count, renderedMenu.size, PrefsHelper.MAX_MENU_ITEMS))
            }
            if (renderedMenu.size >= PrefsHelper.MAX_MENU_ITEMS) PickerMessage(stringResource(R.string.editor_menu_full))
            EditorFeedback(state)
        if (!state.loadingMenu && renderedMenu.isEmpty()) {
            PickerMessage(stringResource(R.string.editor_empty))
        }
        ReorderableMenuList(renderedMenu, state.rowKeys, !state.saving, state.moveRollback,
            onMove = { from, to, snapshot -> onIntent(MenuEditorIntent.Move(from, to, snapshot)) }) { index, handle ->
            val id = renderedMenu[index].legacyMenuTrim()
            val special = menuSpecialLabel(id)
            val packageName = PrefsHelper.appPackageOf(id)
            val label = when {
                id.isEmpty() -> stringResource(R.string.editor_blank_item)
                special != null -> stringResource(special)
                packageName != null -> state.appLabels[packageName] ?: packageName
                else -> menuShortcutLabel(id) ?: id
            }
            val up = stringResource(R.string.editor_move_up, label)
            val down = stringResource(R.string.editor_move_down, label)
            ListItem(
                modifier = Modifier.semantics {
                    customActions = buildList {
                        if (!state.saving && index > 0) add(CustomAccessibilityAction(up) {
                            onIntent(MenuEditorIntent.Move(index, index - 1, renderedMenu)); true
                        })
                        if (!state.saving && index < renderedMenu.lastIndex) add(CustomAccessibilityAction(down) {
                            onIntent(MenuEditorIntent.Move(index, index + 1, renderedMenu)); true
                        })
                    }
                },
                headlineContent = { Text(label) },
                leadingContent = {
                    if (packageName != null) {
                        AppIcon(packageName, loadIcon = { loadIcon(packageName) })
                    } else {
                        Icon(painterResource(menuItemIcon(id)), contentDescription = null)
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EditIconButton(
                            icon = R.drawable.ic_action_delete,
                            description = stringResource(R.string.editor_remove_item, label),
                            enabled = !state.saving,
                        ) { onIntent(MenuEditorIntent.RemoveAt(index, renderedMenu)) }
                        Box(handle.size(48.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_action_drag), stringResource(R.string.editor_drag, label))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun EditIconButton(@DrawableRes icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = description)
    }
}

@Composable
internal fun EditorFeedback(state: MenuEditorState) {
    val message = when {
        state.editFailed -> R.string.editor_save_failed
        state.editResult == MenuEditResult.Duplicate -> R.string.editor_already_in_menu
        state.editResult == MenuEditResult.Full -> R.string.editor_menu_full
        state.platformLaunchFailed -> R.string.editor_preview_failed
        else -> return
    }
    PickerMessage(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@StringRes
internal fun menuSpecialLabel(id: String): Int? = when (id) {
    PrefsHelper.SPECIAL_TORCH -> R.string.name_torch
    PrefsHelper.SPECIAL_CAMERA -> R.string.name_camera
    PrefsHelper.SPECIAL_VPN -> R.string.name_vpn
    PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH -> R.string.action_circle_unavailable
    PrefsHelper.SPECIAL_MENU -> R.string.name_menu
    else -> null
}

@Preview(showBackground = true)
@Composable
private fun MenuEditorPreview() {
    AppTheme {
        MenuEditorScreen(
            state = MenuEditorState(
                menuItems = listOf(PrefsHelper.SPECIAL_TORCH, "app:org.example.maps"),
                loadingMenu = false,
            ),
            onIntent = {},
        )
    }
}
