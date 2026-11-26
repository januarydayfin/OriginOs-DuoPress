package com.krayapp.duopress.actions

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.krayapp.duopress.PrefsHelper
import com.krayapp.duopress.R
import com.krayapp.duopress.data.SettingsSnapshot
import com.krayapp.duopress.platform.HandlerSnapshot
import com.krayapp.duopress.platform.HandlerStatus
import com.krayapp.duopress.menu.visibleMenuItems
import com.krayapp.duopress.ui.navigation.AppRoute
import com.krayapp.duopress.ui.theme.AppTheme

/**
 * Показывает состояние обработчика и три перехода к редактированию действий.
 *
 * @param state снимок общего сценария «Действия».
 * @param onIntent передаёт нажатие кнопки настройки обработчика.
 * @param onOpen открывает выбранный вложенный маршрут.
 * @param modifier модификатор контейнера экрана.
 */
@Composable
fun ActionsScreen(
    state: ActionsState,
    onIntent: (ActionsIntent) -> Unit,
    onOpen: (AppRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_actions),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
        )
        HandlerSummary(state, onIntent)
        val settings = state.settings
        ActionCard(
            title = stringResource(R.string.actions_double_press),
            summary = settings?.let { state.targetLabel ?: it.targetId },
            onClick = { onOpen(AppRoute.ActionPicker) },
        )
        ActionCard(
            title = stringResource(R.string.card_lock_title),
            icon = R.drawable.ic_action_lock,
            summary = settings?.let {
                if (it.lockscreenEnabled) state.lockscreenLabel ?: it.lockscreenActionId
                else stringResource(R.string.actions_same_as_unlocked)
            },
            onClick = { onOpen(AppRoute.Lockscreen) },
        )
        ActionCard(
            title = stringResource(R.string.card_menu_title),
            icon = R.drawable.ic_action_menu,
            summary = settings?.let {
                val count = visibleMenuItems(it.menuItems).size
                pluralStringResource(R.plurals.actions_menu_count, count, count)
            },
            onClick = { onOpen(AppRoute.MenuEditor) },
        )
    }
}

@Composable
private fun HandlerSummary(state: ActionsState, onIntent: (ActionsIntent) -> Unit) {
    val status = state.handler.status
    val summary = when (status) {
        HandlerStatus.Loading -> stringResource(R.string.actions_handler_loading)
        HandlerStatus.ThisApp -> stringResource(R.string.actions_handler_current, stringResource(R.string.app_name))
        is HandlerStatus.OtherApp -> stringResource(R.string.actions_handler_current, status.label)
        HandlerStatus.NoSpecificHandler -> stringResource(R.string.actions_handler_no_specific)
        HandlerStatus.Unavailable -> stringResource(R.string.actions_handler_unavailable)
    }
    val intent = when (status) {
        HandlerStatus.NoSpecificHandler -> ActionsIntent.ResolveHandler
        is HandlerStatus.OtherApp -> ActionsIntent.OpenHandlerDetails(status.packageName)
        else -> null
    }
    val panel = if (intent != null) {
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, CardShape).padding(16.dp)
    } else Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
    Column(panel, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status == HandlerStatus.ThisApp) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (intent != null) {
            Button(
                onClick = { onIntent(intent) },
                enabled = !state.platformBusy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                val label = if (status == HandlerStatus.NoSpecificHandler) R.string.actions_configure_handler
                else R.string.actions_open_handler_details
                Text(stringResource(label))
            }
        }
        if (state.platformLaunchFailed) {
            Text(
                text = stringResource(R.string.actions_platform_launch_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private val CardShape = RoundedCornerShape(20.dp)

@Composable
private fun ActionCard(title: String, summary: String?, onClick: () -> Unit, @DrawableRes icon: Int? = null) {
    val primary = icon == null
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (primary) colors.primaryContainer else colors.surfaceContainer,
            contentColor = if (primary) colors.onPrimaryContainer else colors.onSurface,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (primary) 148.dp else 84.dp)
                .padding(if (primary) 22.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(
                    Modifier.size(44.dp).background(colors.secondaryContainer, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(icon), null, Modifier.size(24.dp), tint = colors.onSecondaryContainer)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = if (primary) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium)
                if (summary != null) {
                    Text(
                        text = summary,
                        style = if (primary) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.bodyMedium,
                        color = if (primary) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ActionsScreenPreview() {
    AppTheme { ActionsScreen(previewActionsState(), onIntent = {}, onOpen = {}) }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LockscreenScreenPreview() {
    AppTheme { LockscreenScreen(previewActionsState(), onIntent = {}) }
}

@Composable
private fun previewActionsState() = ActionsState(
    settings = SettingsSnapshot(
        targetId = PrefsHelper.DEFAULT_TARGET_PACKAGE,
        lockscreenEnabled = true,
        lockscreenActionId = PrefsHelper.SPECIAL_TORCH,
        vibrateEnabled = true,
        vibrateDurationMs = PrefsHelper.DEFAULT_VIBRATE_DURATION_MS,
        vibratePattern = "single",
        vpnClient = "auto",
        menuItems = listOf(
            PrefsHelper.SPECIAL_TORCH,
            PrefsHelper.SPECIAL_CAMERA,
            PrefsHelper.SPECIAL_VPN,
            PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH,
        ),
        triggerLog = "",
    ),
    targetLabel = stringResource(R.string.preset_mirpay),
    lockscreenLabel = stringResource(R.string.name_torch),
    handler = HandlerSnapshot(HandlerStatus.ThisApp, emptyList()),
)
