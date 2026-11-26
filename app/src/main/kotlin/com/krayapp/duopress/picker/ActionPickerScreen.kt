package com.krayapp.duopress.picker

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krayapp.duopress.PrefsHelper
import com.krayapp.duopress.R
import com.krayapp.duopress.ShortcutAction
import com.krayapp.duopress.platform.AppEntry
import com.krayapp.duopress.ui.common.ActionTile
import com.krayapp.duopress.ui.common.AppIcon
import com.krayapp.duopress.ui.common.ShortcutActionTile
import com.krayapp.duopress.ui.theme.AppTheme

/**
 * Показывает плитки выбора действия двойного нажатия и форму ручного ввода пакета.
 *
 * @param viewModel источник текущего действия и черновика ручного ввода.
 * @param onOpenApps открывает список приложений в отдельном маршруте.
 * @param onOpenShortcuts открывает выбор ярлыка в отдельном маршруте.
 * @param shizukuInstalled наличие пакета Shizuku; `null`, пока идёт проверка.
 * @param onOpenShizukuStore открывает страницу установки и сообщает, удалось ли это.
 * @param isActive проверяет, может ли экран ещё принимать действия во время перехода.
 * @param modifier модификатор прокручиваемой области экрана.
 */
@Composable
fun ActionPickerScreen(
    viewModel: ActionPickerViewModel,
    onOpenApps: () -> Unit,
    onOpenShortcuts: () -> Unit,
    shizukuInstalled: Boolean?,
    onOpenShizukuStore: () -> Boolean,
    isActive: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ActionPickerContent(
        state = state,
        onIntent = { if (isActive()) viewModel.onIntent(it) },
        onOpenApps = onOpenApps,
        onOpenShortcuts = onOpenShortcuts,
        shizukuInstalled = shizukuInstalled,
        onOpenShizukuStore = onOpenShizukuStore,
        modifier = modifier,
    )
}

/**
 * Ленивый список результатов для пикеров и редактора. Владелец списка передаёт уже отфильтрованные
 * записи, и обрабатывает выбор по имени пакета независимо от доступности значка.
 * [selectedPackage] выделяет текущий пакет, не запрещая его повторный выбор.
 */
@Composable
fun AppSearchList(
    entries: List<AppEntry>,
    onPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    loadIcon: suspend (String) -> ImageBitmap? = { null },
    alreadyAdded: (String) -> Boolean = { false },
    enabled: Boolean = true,
    selectedPackage: String? = null,
) {
    LazyColumn(modifier = modifier) {
        if (entries.isEmpty()) item("empty") { PickerMessage(stringResource(R.string.picker_no_apps)) }
        appSearchItems(entries, onPicked, loadIcon, selectedPackage, enabled, alreadyAdded)
    }
}

@Composable
private fun ActionPickerContent(
    state: ActionPickerState,
    onIntent: (ActionPickerIntent) -> Unit,
    onOpenApps: () -> Unit,
    onOpenShortcuts: () -> Unit,
    shizukuInstalled: Boolean?,
    onOpenShizukuStore: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val canPick = state.selection != PickerSelection.Saving && !state.selectionComplete
    val targetId = state.targetId
    val shortcutSelected = ShortcutAction.isAction(targetId)
    val appSelected = !targetId.isNullOrBlank() && !targetId.startsWith(PrefsHelper.SPECIAL_PREFIX) && !shortcutSelected
    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("categories") {
            PickerTileRow {
                ActionTile(
                    label = R.string.picker_section_apps,
                    icon = R.drawable.ic_action_app,
                    modifier = Modifier.weight(1f),
                    enabled = canPick,
                    opensPicker = true,
                    selected = appSelected,
                    onClick = onOpenApps,
                )
                ShortcutActionTile(
                    modifier = Modifier.weight(1f),
                    enabled = canPick,
                    installed = shizukuInstalled,
                    selected = shortcutSelected,
                    onOpenShortcuts = onOpenShortcuts,
                    onOpenStore = onOpenShizukuStore,
                )
            }
        }
        items(presetTileRows, key = { it.first().preset.id }, contentType = { "preset_row" }) { row ->
            PickerTileRow {
                row.forEach { tile ->
                    ActionTile(
                        label = tile.label,
                        icon = tile.icon,
                        modifier = Modifier.weight(1f),
                        enabled = canPick,
                        selected = tile.preset.id == state.targetId,
                        onClick = { onIntent(ActionPickerIntent.PickPreset(tile.preset)) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (state.selection == PickerSelection.Failed) item("saveError") {
            PickerMessage(stringResource(R.string.picker_save_failed))
        }
        item("manual") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.picker_manual_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = state.manualPackage,
                    onValueChange = { onIntent(ActionPickerIntent.ChangeManualPackage(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.picker_manual_package_hint)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    singleLine = true,
                )
                Button(
                    onClick = { onIntent(ActionPickerIntent.PickManualPackage) },
                    enabled = canPick && state.manualPackage.isNotBlank(),
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.picker_manual_confirm))
                }
            }
        }
    }
}

@Composable
private fun PickerTileRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

private fun LazyListScope.appSearchItems(
    entries: List<AppEntry>,
    onPicked: (String) -> Unit,
    loadIcon: suspend (String) -> ImageBitmap?,
    selectedPackage: String? = null,
    enabled: Boolean = true,
    alreadyAdded: (String) -> Boolean = { false },
) {
    items(entries, key = { "app:${it.packageName}" }, contentType = { "app" }) { entry ->
        val added = alreadyAdded(entry.packageName)
        PickerChoice(
            label = entry.label,
            detail = if (added) stringResource(R.string.menu_item_added) else entry.packageName.takeUnless { it == entry.label },
            selected = selectedPackage == entry.packageName,
            enabled = enabled && !added,
            onClick = { onPicked(entry.packageName) },
            leading = { AppIcon(entry.packageName, loadIcon = { loadIcon(entry.packageName) }) },
        )
    }
}

@Composable
internal fun PickerChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = detail?.let { value -> { Text(value) } },
        leadingContent = leading,
        modifier = modifier.selectable(selected, enabled, Role.RadioButton, onClick).heightIn(min = 48.dp),
        colors = if (selected) {
            ListItemDefaults.colors(
                containerColor = colors.secondaryContainer,
                headlineColor = colors.onSecondaryContainer,
                supportingColor = colors.onSecondaryContainer,
                leadingIconColor = colors.onSecondaryContainer,
            )
        } else {
            ListItemDefaults.colors()
        },
    )
}

@Composable
internal fun PickerSearchField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        label = { Text(label) },
        leadingIcon = { PickerIcon(R.drawable.ic_action_search) },
        singleLine = true,
    )
}

@Composable
internal fun PickerMessage(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.btn_retry))
            }
        }
    }
}

@Composable
private fun PickerIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
}

private data class PresetRow(val preset: ActionPreset, @StringRes val label: Int, @DrawableRes val icon: Int)

private val presetTileRows = listOf(
    PresetRow(ActionPreset.Torch, R.string.name_torch, R.drawable.ic_action_torch),
    PresetRow(ActionPreset.Camera, R.string.name_camera, R.drawable.ic_action_camera),
    PresetRow(ActionPreset.Vpn, R.string.name_vpn, R.drawable.ic_action_vpn),
    PresetRow(ActionPreset.Menu, R.string.name_menu, R.drawable.ic_action_menu),
).chunked(2)

@Preview(showBackground = true)
@Composable
private fun ActionPickerPreview() {
    AppTheme {
        ActionPickerContent(
            ActionPickerState(targetId = "special:camera"),
            onIntent = {},
            onOpenApps = {},
            onOpenShortcuts = {},
            shizukuInstalled = true,
            onOpenShizukuStore = { false },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AppSearchListPreview() {
    AppTheme { AppSearchList(listOf(AppEntry("com.example.app", "Example")), onPicked = {}) }
}
