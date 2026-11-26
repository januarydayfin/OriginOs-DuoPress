package com.krayapp.duopress.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krayapp.duopress.R
import com.krayapp.duopress.ShortcutAction
import com.krayapp.duopress.platform.AppEntry
import com.krayapp.duopress.platform.ShortcutEntry
import com.krayapp.duopress.platform.ShortcutKind
import com.krayapp.duopress.ui.common.AppIcon
import com.krayapp.duopress.ui.theme.AppTheme

/** Экран подбора ярлыка. Только внешний Android-хост исполняет системные команды. */
@Composable
fun ShortcutPickerScreen(
    viewModel: ShortcutPickerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ShortcutPickerContent(state, viewModel::onIntent, viewModel::appIcon, viewModel::shortcutIcon, modifier)
}

@Composable
private fun ShortcutPickerContent(
    state: ShortcutPickerState,
    onIntent: (ShortcutPickerIntent) -> Unit,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    loadShortcutIcon: suspend (ShortcutEntry) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (state.access != ShortcutAccess.Ready) {
            item("access") { ShortcutAccessMessage(state, onIntent) }
        } else {
            item("search") {
                PickerSearchField(state.query, stringResource(R.string.shortcut_picker_search_hint)) {
                    onIntent(ShortcutPickerIntent.Search(it))
                }
            }
            if (state.selection == PickerSelection.Failed) item("saveError") {
                PickerMessage(stringResource(R.string.picker_save_failed))
            }
            when {
                state.loadingApps -> item("loading") { PickerMessage(stringResource(R.string.picker_loading_apps)) }
                state.appsError != null -> item("appsError") {
                    PickerMessage(
                        stringResource(R.string.picker_apps_failed_detail, state.appsError),
                        onRetry = { onIntent(ShortcutPickerIntent.RetryApps) },
                    )
                }
                state.filteringApps -> item("filtering") { PickerMessage(stringResource(R.string.picker_filtering_apps)) }
                state.visibleApps.isEmpty() -> item("empty") {
                    PickerMessage(stringResource(R.string.picker_no_apps))
                }
                else -> state.visibleApps.forEach { row ->
                    val packageName = row.app.packageName
                    item("app:$packageName", contentType = "app") {
                        ExpandableApp(row, loadAppIcon, onToggle = { onIntent(ShortcutPickerIntent.ToggleApp(packageName)) })
                    }
                    shortcutChildren(
                        row, state.query, state.selectedShortcut, loadShortcutIcon, onIntent,
                        enabled = state.selection != PickerSelection.Saving && !state.selectionComplete,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutAccessMessage(state: ShortcutPickerState, onIntent: (ShortcutPickerIntent) -> Unit) {
    val message = when (state.access) {
        ShortcutAccess.Ready -> return
        ShortcutAccess.Checking -> R.string.shortcut_picker_checking_access
        ShortcutAccess.Unavailable -> R.string.shortcut_shizuku_unavailable
        ShortcutAccess.PreV11 -> R.string.shortcut_shizuku_pre_v11
        ShortcutAccess.UnsupportedUid -> R.string.shortcut_unsupported_uid
        ShortcutAccess.UnsupportedApi -> R.string.shortcut_unsupported_api
        ShortcutAccess.PermissionDenied -> R.string.shortcut_permission_denied
        ShortcutAccess.PermissionInterrupted -> R.string.shortcut_permission_interrupted
        ShortcutAccess.PermissionAvailable -> if (state.permissionRequestId == null) {
            R.string.shortcut_permission_required
        } else {
            R.string.shortcut_permission_waiting
        }
    }
    val canRetry = (state.access == ShortcutAccess.Unavailable && !state.openingShizuku) ||
        state.access == ShortcutAccess.PermissionInterrupted
    PickerMessage(
        stringResource(message),
        onRetry = if (canRetry) ({ onIntent(ShortcutPickerIntent.RetryAccess) }) else null,
    )
    when {
        state.access == ShortcutAccess.PermissionAvailable && state.permissionRequestId == null ->
            Button(
                onClick = { onIntent(ShortcutPickerIntent.RequestPermission) },
                modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.shortcut_permission_request)) }

        state.canOpenShizuku -> TextButton(
            onClick = { onIntent(ShortcutPickerIntent.OpenShizuku) },
            enabled = !state.openingShizuku,
            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.btn_open_shizuku)) }
    }
    if (state.openShizukuFailed) PickerMessage(stringResource(R.string.shortcut_open_shizuku_failed))
}

@Composable
private fun ExpandableApp(
    row: ShortcutAppRow,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    onToggle: () -> Unit,
) {
    val expanded = row.children != null
    val stateLabel = stringResource(if (expanded) R.string.picker_app_expanded else R.string.picker_app_collapsed)
    val packageName = row.app.packageName
    ListItem(
        headlineContent = { Text(row.app.label) },
        supportingContent = if (packageName != row.app.label) ({ Text(packageName) }) else null,
        leadingContent = { AppIcon(packageName, loadIcon = { loadAppIcon(packageName) }) },
        trailingContent = {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
            )
        },
        modifier = Modifier.clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = stateLabel }
            .heightIn(min = 48.dp),
    )
}

private fun LazyListScope.shortcutChildren(
    row: ShortcutAppRow,
    query: String,
    current: ShortcutAction?,
    loadIcon: suspend (ShortcutEntry) -> ImageBitmap?,
    onIntent: (ShortcutPickerIntent) -> Unit,
    enabled: Boolean,
) {
    val packageName = row.app.packageName
    val indent = Modifier.padding(start = 24.dp)
    when (val load = row.children) {
        null -> Unit
        ShortcutLoad.Loading -> item("loading:$packageName", contentType = "message") {
            PickerMessage(stringResource(R.string.shortcut_picker_loading), indent)
        }
        is ShortcutLoad.Failed -> item("error:$packageName", contentType = "message") {
            PickerMessage(
                stringResource(R.string.shortcut_picker_loading_error, load.detail),
                indent,
                onRetry = { onIntent(ShortcutPickerIntent.RetryShortcuts(packageName)) },
            )
        }
        is ShortcutLoad.Loaded -> {
            if (load.entries.isEmpty()) item("empty:$packageName", contentType = "message") {
                PickerMessage(stringResource(R.string.shortcut_picker_no_shortcuts), indent)
            }
            items(load.entries, key = { "shortcut:$packageName:${it.id}" }, contentType = { "shortcut" }) { shortcut ->
                PickerChoice(
                    label = shortcut.label,
                    detail = stringResource(
                        when (shortcut.kind) {
                            ShortcutKind.Manifest -> R.string.shortcut_type_manifest
                            ShortcutKind.Dynamic -> R.string.shortcut_type_dynamic
                            ShortcutKind.Pinned -> R.string.shortcut_type_pinned
                        },
                    ),
                    selected = current?.packageName == packageName && current.shortcutId == shortcut.id,
                    enabled = enabled,
                    onClick = { onIntent(ShortcutPickerIntent.PickShortcut(packageName, shortcut.id, query)) },
                    leading = { AppIcon(shortcut, loadIcon = { loadIcon(shortcut) }) },
                    modifier = indent,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ShortcutPickerPreview() {
    val example = AppEntry("com.example.app", "Example")
    val shortcut = ShortcutEntry(example.packageName, "share", "Share", ShortcutKind.Dynamic, 0)
    AppTheme {
        ShortcutPickerContent(
            state = ShortcutPickerState(
                access = ShortcutAccess.Ready,
                visibleApps = listOf(ShortcutAppRow(example, ShortcutLoad.Loaded(listOf(shortcut)))),
            ),
            onIntent = {},
            loadAppIcon = { null },
            loadShortcutIcon = { null },
        )
    }
}
