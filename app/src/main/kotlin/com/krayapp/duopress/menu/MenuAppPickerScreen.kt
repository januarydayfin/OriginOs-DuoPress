package com.krayapp.duopress.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import com.krayapp.duopress.R
import com.krayapp.duopress.picker.AppSearchList
import com.krayapp.duopress.picker.PickerMessage
import com.krayapp.duopress.picker.PickerSearchField

@Composable
internal fun MenuAppPickerScreen(
    state: MenuEditorState,
    onIntent: (MenuEditorIntent) -> Unit,
    loadIcon: suspend (String) -> ImageBitmap?,
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        PickerSearchField(state.query, stringResource(R.string.picker_search_hint)) {
            onIntent(MenuEditorIntent.SearchApps(it))
        }
        EditorFeedback(state)
        when {
            state.loadingApps -> PickerMessage(stringResource(R.string.picker_loading_apps))
            state.appsFailed -> PickerMessage(stringResource(R.string.picker_apps_failed),
                onRetry = { onIntent(MenuEditorIntent.ReloadApps) })
            else -> AppSearchList(
                entries = state.visibleApps,
                onPicked = { onIntent(MenuEditorIntent.PickApp(it)) },
                modifier = Modifier.weight(1f),
                loadIcon = loadIcon,
                alreadyAdded = { pkg -> state.menuItems.any { sameMenuItem(it, "app:$pkg") } },
                enabled = state.canAdd,
            )
        }
    }
}
