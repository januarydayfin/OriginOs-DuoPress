package com.vivotomirpay.stub.actions

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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivotomirpay.stub.PrefsHelper
import com.vivotomirpay.stub.R

/**
 * Управляет отдельным действием при блокировке, не сбрасывая выбор при отключении.
 *
 * @param state снимок того же сценария, что и на экране «Действия».
 * @param onIntent передаёт выбор действия и изменения переключателя.
 * @param modifier модификатор контейнера экрана.
 */
@Composable
fun LockscreenScreen(
    state: ActionsState,
    onIntent: (ActionsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val settings = state.settings
        if (settings == null) {
            Text(
                text = stringResource(R.string.actions_settings_loading),
                modifier = Modifier.padding(16.dp),
            )
        } else {
            val enabled = settings.lockscreenEnabled
            ListItem(
                headlineContent = { Text(stringResource(R.string.switch_lock)) },
                supportingContent = {
                    Text(stringResource(if (enabled) R.string.lock_hint_on else R.string.lock_hint_off))
                },
                trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = enabled, role = Role.Switch) {
                        onIntent(ActionsIntent.ToggleLockscreen)
                    },
            )
            if (enabled) {
                Text(
                    text = stringResource(R.string.lock_action_label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
                        .semantics { heading() },
                )
                Column(Modifier.selectableGroup()) {
                    lockscreenActionIds.forEach { id ->
                        val selected = settings.lockscreenActionId == id
                        val label = if (id == PrefsHelper.SPECIAL_TORCH) R.string.name_torch else R.string.name_camera
                        ListItem(
                            headlineContent = { Text(stringResource(label)) },
                            leadingContent = { RadioButton(selected = selected, onClick = null) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = selected, role = Role.RadioButton) {
                                    onIntent(ActionsIntent.SelectLockscreenAction(id))
                                },
                        )
                    }
                }
            }
        }
    }
}
