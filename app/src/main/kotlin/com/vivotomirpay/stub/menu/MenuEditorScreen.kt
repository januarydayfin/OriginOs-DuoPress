package com.vivotomirpay.stub.menu

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vivotomirpay.stub.PrefsHelper
import com.vivotomirpay.stub.R
import com.vivotomirpay.stub.picker.AppSearchList
import com.vivotomirpay.stub.picker.PickerMessage
import com.vivotomirpay.stub.picker.PickerSearchField
import com.vivotomirpay.stub.ui.common.AppIcon
import com.vivotomirpay.stub.ui.theme.AppTheme

/**
 * Показывает редактор и встроенные списки добавления. Хост отвечает за верхнюю панель маршрута.
 *
 * @param state текущее состояние редактора.
 * @param onIntent обработчик действий, в том числе запроса предпросмотра.
 * @param modifier модификатор корневой области редактора.
 * @param loadIcon загрузчик цветного значка по имени пакета.
 */
@Composable
fun MenuEditorScreen(
    state: MenuEditorState,
    onIntent: (MenuEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
    loadIcon: suspend (String) -> ImageBitmap? = { null },
) {
    BackHandler(enabled = state.picker != MenuPicker.None) { onIntent(MenuEditorIntent.ClosePicker) }
    when (state.picker) {
        MenuPicker.None -> EditorRows(state, onIntent, modifier, loadIcon)
        MenuPicker.Specials -> LazyColumn(modifier.fillMaxSize()) {
            item { EditorHeading(stringResource(R.string.editor_add_action)) }
            item { EditorFeedback(state) }
            items(menuSpecialIds, key = { it }) { id ->
                ListItem(
                    headlineContent = { Text(stringResource(requireNotNull(menuSpecialLabel(id)))) },
                    leadingContent = { Icon(painterResource(menuItemIcon(id)), contentDescription = null) },
                    modifier = Modifier.clickable(enabled = !state.saving, role = Role.Button) {
                        onIntent(MenuEditorIntent.PickSpecial(id))
                    },
                )
            }
        }
        MenuPicker.Apps -> Column(modifier.fillMaxSize().imePadding()) {
            EditorHeading(stringResource(R.string.editor_add_app))
            PickerSearchField(state.query, stringResource(R.string.picker_search_hint)) {
                onIntent(MenuEditorIntent.SearchApps(it))
            }
            EditorFeedback(state)
            when {
                state.loadingApps -> PickerMessage(stringResource(R.string.picker_loading_apps))
                state.appsFailed -> PickerMessage(stringResource(R.string.picker_apps_failed), onRetry = {
                    onIntent(MenuEditorIntent.ReloadApps)
                })
                else -> AppSearchList(
                    entries = state.visibleApps,
                    onPicked = { onIntent(MenuEditorIntent.PickApp(it)) },
                    modifier = Modifier.weight(1f),
                    loadIcon = loadIcon,
                )
            }
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
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            PickerMessage(stringResource(R.string.editor_hint))
            EditorFeedback(state)
        }
        if (!state.loadingMenu && renderedMenu.isEmpty()) {
            item { PickerMessage(stringResource(R.string.editor_empty)) }
        }
        itemsIndexed(renderedMenu) { index, rawId ->
            val id = rawId.legacyMenuTrim()
            val special = menuSpecialLabel(id)
            val packageName = PrefsHelper.appPackageOf(id)
            val label = when {
                id.isEmpty() -> stringResource(R.string.editor_blank_item)
                special != null -> stringResource(special)
                packageName != null -> state.appLabels[packageName] ?: packageName
                else -> id
            }
            ListItem(
                headlineContent = { Text(label) },
                leadingContent = {
                    if (packageName != null) {
                        AppIcon(packageName, loadIcon = { loadIcon(packageName) })
                    } else {
                        Icon(painterResource(menuItemIcon(id)), contentDescription = null)
                    }
                },
                trailingContent = {
                    Row {
                        EditIconButton(
                            icon = R.drawable.ic_action_up,
                            description = stringResource(R.string.editor_move_up, label),
                            enabled = !state.saving && index > 0,
                        ) { onIntent(MenuEditorIntent.Move(index, index - 1, renderedMenu)) }
                        EditIconButton(
                            icon = R.drawable.ic_action_down,
                            description = stringResource(R.string.editor_move_down, label),
                            enabled = !state.saving && index < renderedMenu.lastIndex,
                        ) { onIntent(MenuEditorIntent.Move(index, index + 1, renderedMenu)) }
                        EditIconButton(
                            icon = R.drawable.ic_action_delete,
                            description = stringResource(R.string.editor_remove_item, label),
                            enabled = !state.saving,
                        ) { onIntent(MenuEditorIntent.RemoveAt(index, renderedMenu)) }
                    }
                },
            )
        }
        item {
            val canEdit = !state.saving && !state.loadingMenu
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(
                    onClick = { onIntent(MenuEditorIntent.OpenApps) },
                    enabled = canEdit,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.editor_add_app)) }
                OutlinedButton(
                    onClick = { onIntent(MenuEditorIntent.OpenSpecials) },
                    enabled = canEdit,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.editor_add_action)) }
                OutlinedButton(
                    onClick = { onIntent(MenuEditorIntent.Preview) },
                    enabled = state.previewEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_menu_preview)) }
            }
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
private fun EditorHeading(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun EditorFeedback(state: MenuEditorState) {
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
private fun menuSpecialLabel(id: String): Int? = when (id) {
    PrefsHelper.SPECIAL_TORCH -> R.string.name_torch
    PrefsHelper.SPECIAL_CAMERA -> R.string.name_camera
    PrefsHelper.SPECIAL_VPN -> R.string.name_vpn
    PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH -> R.string.name_cts
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
