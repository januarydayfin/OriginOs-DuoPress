package com.vivotomirpay.stub.picker

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import com.vivotomirpay.stub.PrefsHelper
import com.vivotomirpay.stub.R
import com.vivotomirpay.stub.ShortcutAction
import com.vivotomirpay.stub.platform.AppEntry
import com.vivotomirpay.stub.ui.common.AppIcon
import com.vivotomirpay.stub.ui.theme.AppTheme

/** Экран маршрута выбора основного действия; внешний хост выполняет переходы. */
@Composable
fun ActionPickerScreen(
    viewModel: ActionPickerViewModel,
    onOpenShortcuts: () -> Unit,
    onSelectionComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PickerCompletion(state.selectionComplete, onSelectionComplete)
    ActionPickerContent(state, viewModel::onIntent, viewModel::icon, onOpenShortcuts, modifier)
}

/**
 * Ленивый список результатов для пикеров и редактора. Владелец списка передаёт уже отфильтрованные
 * записи, и обрабатывает выбор по имени пакета независимо от доступности значка.
 */
@Composable
fun AppSearchList(
    entries: List<AppEntry>,
    onPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    loadIcon: suspend (String) -> ImageBitmap? = { null },
) {
    LazyColumn(modifier = modifier) {
        if (entries.isEmpty()) item("empty") { PickerMessage(stringResource(R.string.picker_no_apps)) }
        appSearchItems(entries, onPicked, loadIcon)
    }
}

@Composable
private fun ActionPickerContent(
    state: ActionPickerState,
    onIntent: (ActionPickerIntent) -> Unit,
    loadIcon: suspend (String) -> ImageBitmap?,
    onOpenShortcuts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canPick = state.selection != PickerSelection.Saving && !state.selectionComplete
    val currentPackage = PrefsHelper.appPackageOf(state.targetId) ?: state.targetId
    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item("presets") { PickerHeading(stringResource(R.string.picker_section_specials)) }
        items(presetRows, key = { it.preset.id }, contentType = { "preset" }) { row ->
            val selectedId = if (row.preset in walletPresets) currentPackage else state.targetId
            PickerChoice(
                label = stringResource(row.label),
                selected = row.preset.id == selectedId,
                enabled = canPick,
                onClick = { onIntent(ActionPickerIntent.PickPreset(row.preset)) },
                leading = { PickerIcon(row.icon) },
            )
        }
        item("shortcuts", contentType = "preset") {
            PickerChoice(
                label = stringResource(R.string.name_shortcuts),
                selected = ShortcutAction.isAction(state.targetId),
                enabled = canPick,
                onClick = onOpenShortcuts,
                leading = { PickerIcon(R.drawable.ic_action_shortcut) },
            )
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
        item("apps") { PickerHeading(stringResource(R.string.picker_section_apps)) }
        item("search") {
            PickerSearchField(state.query, stringResource(R.string.picker_search_hint)) {
                onIntent(ActionPickerIntent.Search(it))
            }
        }
        when {
            state.loadingApps -> item("loading") { PickerMessage(stringResource(R.string.picker_loading_apps)) }
            state.appsFailed -> item("appsError") {
                PickerMessage(
                    stringResource(R.string.picker_apps_failed),
                    onRetry = { onIntent(ActionPickerIntent.ReloadApps) },
                )
            }
            state.visibleApps.isEmpty() -> item("empty") { PickerMessage(stringResource(R.string.picker_no_apps)) }
            else -> appSearchItems(
                state.visibleApps,
                onPicked = { onIntent(ActionPickerIntent.PickApp(it)) },
                loadIcon = loadIcon,
                selectedPackage = currentPackage,
                enabled = canPick,
            )
        }
    }
}

private fun LazyListScope.appSearchItems(
    entries: List<AppEntry>,
    onPicked: (String) -> Unit,
    loadIcon: suspend (String) -> ImageBitmap?,
    selectedPackage: String? = null,
    enabled: Boolean = true,
) {
    items(entries, key = { "app:${it.packageName}" }, contentType = { "app" }) { entry ->
        PickerChoice(
            label = entry.label,
            detail = entry.packageName.takeUnless { it == entry.label },
            selected = selectedPackage == entry.packageName,
            enabled = enabled,
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
internal fun PickerCompletion(complete: Boolean, onComplete: () -> Unit) {
    val callback by rememberUpdatedState(onComplete)
    LaunchedEffect(complete) {
        if (complete) callback()
    }
}

@Composable
private fun PickerHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun PickerIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
}

private data class PresetRow(val preset: ActionPreset, @StringRes val label: Int, @DrawableRes val icon: Int)

private val presetRows = listOf(
    PresetRow(ActionPreset.MirPay, R.string.preset_mirpay, R.drawable.ic_action_app),
    PresetRow(ActionPreset.SbpPay, R.string.preset_sbpay, R.drawable.ic_action_app),
    PresetRow(ActionPreset.VivoWallet, R.string.picker_vivo_wallet, R.drawable.ic_action_app),
    PresetRow(ActionPreset.Torch, R.string.name_torch, R.drawable.ic_action_torch),
    PresetRow(ActionPreset.Camera, R.string.name_camera, R.drawable.ic_action_camera),
    PresetRow(ActionPreset.Vpn, R.string.name_vpn, R.drawable.ic_action_vpn),
    PresetRow(ActionPreset.Menu, R.string.name_menu, R.drawable.ic_action_menu),
    PresetRow(ActionPreset.CircleToSearch, R.string.name_cts, R.drawable.ic_action_search),
)
private val walletPresets = setOf(ActionPreset.MirPay, ActionPreset.SbpPay, ActionPreset.VivoWallet)

@Preview(showBackground = true)
@Composable
private fun ActionPickerPreview() {
    val example = AppEntry("com.example.app", "Example")
    AppTheme {
        ActionPickerContent(
            ActionPickerState(targetId = "special:camera", visibleApps = listOf(example), loadingApps = false),
            onIntent = {},
            loadIcon = { null },
            onOpenShortcuts = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AppSearchListPreview() {
    AppTheme { AppSearchList(listOf(AppEntry("com.example.app", "Example")), onPicked = {}) }
}
