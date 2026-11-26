package com.krayapp.duopress.settings

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.krayapp.duopress.R
import com.krayapp.duopress.data.SettingsSnapshot
import com.krayapp.duopress.platform.DeviceStatus
import com.krayapp.duopress.ui.theme.AppTheme
import kotlin.math.roundToInt

/**
 * Показывает настройки вибрации и VPN; при возобновлении запрашивает проверку устройства.
 *
 * @param state текущий снимок настроек и статусов.
 * @param onIntent принимает события экрана; исполнение платформенных запросов остаётся у хоста.
 * @param modifier модификатор корневого контейнера.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onIntent(SettingsIntent.Resume) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        val settings = state.settings
        if (settings == null) {
            Text(stringResource(R.string.actions_settings_loading))
        } else {
            VibrationSection(settings, state.durationDraftMs, onIntent)
            HorizontalDivider()
            VpnSection(settings.vpnClient, state.device, onIntent)
        }
        if (state.platformLaunchFailed) {
            Text(
                text = stringResource(R.string.settings_platform_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun VibrationSection(
    settings: SettingsSnapshot,
    durationDraftMs: Int?,
    onIntent: (SettingsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsHeading(R.string.card_vibration_title)
        ListItem(
            headlineContent = { Text(stringResource(R.string.switch_vibrate)) },
            trailingContent = { Switch(checked = settings.vibrateEnabled, onCheckedChange = null) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = settings.vibrateEnabled,
                role = Role.Switch,
                onValueChange = { onIntent(SettingsIntent.ToggleVibration) },
            ),
        )
        val duration = durationDraftMs ?: settings.vibrateDurationMs
        val durationLabel = stringResource(R.string.vibrate_duration_label, duration)
        Text(durationLabel)
        Slider(
            value = duration.toFloat(),
            onValueChange = { onIntent(SettingsIntent.ChangeDuration(it.roundToInt())) },
            onValueChangeFinished = { onIntent(SettingsIntent.CommitDuration) },
            valueRange = vibrationDurationRange.first.toFloat()..vibrationDurationRange.last.toFloat(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = durationLabel },
        )
        Text(stringResource(R.string.vibrate_pattern_label), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            PatternOption("single", R.string.pattern_single, settings.vibratePattern, onIntent)
            PatternOption("double", R.string.pattern_double, settings.vibratePattern, onIntent)
            PatternOption("long", R.string.pattern_long, settings.vibratePattern, onIntent)
        }
        SettingsHint(R.string.card_vibration_hint)
    }
}

@Composable
private fun PatternOption(id: String, @StringRes title: Int, current: String, onIntent: (SettingsIntent) -> Unit) {
    SettingOption(title, selected = current == id, onClick = { onIntent(SettingsIntent.SelectPattern(id)) })
}

@Composable
private fun VpnSection(client: String, device: DeviceStatus?, onIntent: (SettingsIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsHeading(R.string.card_vpn_title)
        Column(Modifier.selectableGroup()) {
            VpnOption("auto", R.string.vpn_client_auto, client, onIntent)
            VpnOption("happ", R.string.settings_vpn_happ, client, onIntent, device?.happAvailable)
            VpnOption("incy", R.string.settings_vpn_incy, client, onIntent, device?.incyAvailable)
        }
        Text(
            text = when {
                device == null -> stringResource(R.string.settings_device_loading)
                device.vpnActive -> stringResource(R.string.vpn_state_active)
                else -> stringResource(R.string.vpn_state_inactive)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        SettingsHint(R.string.settings_vpn_hint)
    }
}

@Composable
private fun VpnOption(
    id: String,
    @StringRes title: Int,
    current: String,
    onIntent: (SettingsIntent) -> Unit,
    available: Boolean? = null,
) {
    val summary = when (available) {
        true -> stringResource(R.string.vpn_status_installed, stringResource(title))
        false -> stringResource(R.string.vpn_status_not_installed_short)
        null -> null
    }
    SettingOption(
        title,
        selected = current == id,
        onClick = { onIntent(SettingsIntent.SelectVpnClient(id)) },
        summary = summary,
    )
}

@Composable
private fun SettingOption(@StringRes title: Int, selected: Boolean, onClick: () -> Unit, summary: String? = null) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { RadioButton(selected, onClick = null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick,
        ),
    )
}

@Composable
private fun SettingsHeading(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SettingsHint(@StringRes hint: Int) {
    Text(
        stringResource(hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsScreenPreview() {
    AppTheme {
        SettingsScreen(
            state = SettingsUiState(
                settings = SettingsSnapshot("ru.nspk.mirpay", true, "special:torch", true, 40, "double", "happ", emptyList(), ""),
                device = DeviceStatus(happAvailable = false, incyAvailable = true, vpnActive = false),
            ),
            onIntent = {},
        )
    }
}
