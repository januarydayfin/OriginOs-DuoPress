package com.krayapp.duopress.picker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krayapp.duopress.PrefsHelper
import com.krayapp.duopress.R

/**
 * Даёт выбрать установленное приложение, используя состояние родительского экрана.
 *
 * @param viewModel хранит поисковый запрос и сохраняет выбранное действие.
 * @param isActive проверяет, не закрыт ли маршрут к моменту выбора строки.
 * @param modifier модификатор корневой области экрана приложений.
 */
@Composable
internal fun DoublePressAppPickerScreen(
    viewModel: ActionPickerViewModel,
    isActive: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canPick = state.selection != PickerSelection.Saving && !state.selectionComplete
    val currentPackage = PrefsHelper.appPackageOf(state.targetId) ?: state.targetId

    Column(modifier.fillMaxSize().imePadding()) {
        PickerSearchField(state.query, stringResource(R.string.picker_search_hint)) {
            viewModel.onIntent(ActionPickerIntent.Search(it))
        }
        if (state.selection == PickerSelection.Failed) {
            PickerMessage(stringResource(R.string.picker_save_failed))
        }
        when {
            state.loadingApps -> PickerMessage(stringResource(R.string.picker_loading_apps))
            state.appsFailed -> PickerMessage(
                stringResource(R.string.picker_apps_failed),
                onRetry = { viewModel.onIntent(ActionPickerIntent.ReloadApps) },
            )
            state.filteringApps -> PickerMessage(stringResource(R.string.picker_filtering_apps))
            else -> AppSearchList(
                entries = state.visibleApps,
                onPicked = { packageName ->
                    if (isActive()) viewModel.onIntent(ActionPickerIntent.PickApp(packageName, state.query))
                },
                modifier = Modifier.weight(1f),
                loadIcon = viewModel::icon,
                enabled = canPick,
                selectedPackage = currentPackage,
            )
        }
    }
}
