package com.vivotomirpay.stub.diagnostics

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vivotomirpay.stub.R
import com.vivotomirpay.stub.platform.HandlerCandidate
import com.vivotomirpay.stub.platform.HandlerSnapshot
import com.vivotomirpay.stub.platform.HandlerStatus
import com.vivotomirpay.stub.ui.theme.AppTheme

/**
 * Показывает тест, настройку обработчика и журнал; запрашивает проверку при возобновлении экрана.
 *
 * @param state текущий обработчик, зарегистрированные кандидаты и последний журнал.
 * @param onIntent принимает события экрана; исполнение платформенных запросов остаётся у хоста.
 * @param modifier модификатор корневого контейнера.
 */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onIntent: (DiagnosticsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onIntent(DiagnosticsIntent.Resume) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_diagnostics),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        HandlerSection(state, onIntent)
        HorizontalDivider()
        LogSection(state.triggerLog, onIntent)
    }
}

@Composable
private fun HandlerSection(state: DiagnosticsUiState, onIntent: (DiagnosticsIntent) -> Unit) {
    val handler = state.handler
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DiagnosticsHeading(R.string.card_handler_title)
        val summary = when (val status = handler.status) {
            HandlerStatus.Loading -> stringResource(R.string.actions_handler_loading)
            HandlerStatus.ThisApp -> stringResource(R.string.status_default_ok)
            is HandlerStatus.OtherApp -> stringResource(R.string.status_default_other, status.label)
            HandlerStatus.NoSpecificHandler -> stringResource(R.string.actions_handler_no_specific)
            HandlerStatus.Unavailable -> stringResource(R.string.actions_handler_unavailable)
        }
        Text(summary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (handler.status != HandlerStatus.Loading) {
            RegisteredHandlers(handler.candidates)
        }
        Button(
            onClick = { onIntent(DiagnosticsIntent.TestAction) },
            enabled = !state.platformBusy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.btn_test))
        }
        DiagnosticsHint(R.string.btn_test_hint)
        if (handler.status == HandlerStatus.NoSpecificHandler || handler.status is HandlerStatus.OtherApp) {
            val needsResolver = handler.status == HandlerStatus.NoSpecificHandler
            FilledTonalButton(
                onClick = { onIntent(DiagnosticsIntent.ConfigureHandler) },
                enabled = !state.handlerRefreshing && !state.platformBusy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (needsResolver) R.string.btn_resolve else R.string.btn_appinfo))
            }
            DiagnosticsHint(if (needsResolver) R.string.btn_resolve_hint else R.string.btn_appinfo_hint)
        }
        if (state.platformLaunchFailed) {
            Text(
                text = stringResource(R.string.diagnostics_platform_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun RegisteredHandlers(candidates: List<HandlerCandidate>) {
    Text(
        stringResource(R.string.handler_status_registered, candidates.size),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
    candidates.forEach { candidate ->
        Text(
            text = stringResource(R.string.app_label_with_package, candidate.label, candidate.packageName),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LogSection(log: String?, onIntent: (DiagnosticsIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DiagnosticsHeading(R.string.card_log_title)
        when {
            log == null -> Text(stringResource(R.string.diagnostics_log_loading))
            log.isBlank() -> Text(stringResource(R.string.log_empty))
            else -> SelectionContainer { Text(log) }
        }
        OutlinedButton(
            onClick = { onIntent(DiagnosticsIntent.ClearLog) },
            enabled = !log.isNullOrBlank(),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.btn_log_clear))
        }
        DiagnosticsHint(R.string.card_log_hint)
    }
}

@Composable
private fun DiagnosticsHeading(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun DiagnosticsHint(@StringRes hint: Int) {
    Text(
        stringResource(hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DiagnosticsScreenPreview() {
    val wallet = HandlerCandidate("org.example.wallet", "Wallet")
    AppTheme {
        DiagnosticsScreen(
            state = DiagnosticsUiState(
                triggerLog = "NFC_BUS_SWINGSPLASH",
                handler = HandlerSnapshot(HandlerStatus.OtherApp(wallet.packageName, wallet.label), listOf(wallet)),
            ),
            onIntent = {},
        )
    }
}
