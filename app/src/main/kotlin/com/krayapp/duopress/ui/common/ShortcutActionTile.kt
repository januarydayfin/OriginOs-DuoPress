package com.krayapp.duopress.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.krayapp.duopress.R

/**
 * Открывает выбор ярлыков или объясняет, как установить Shizuku.
 *
 * @param modifier положение и ширина плитки.
 * @param enabled разрешено ли взаимодействие с плиткой на текущем экране.
 * @param installed установлен ли Shizuku; `null`, пока идёт проверка.
 * @param selected относится ли сохранённое действие к категории ярлыков.
 * @param onOpenShortcuts открывает выбор ярлыков.
 * @param onOpenStore открывает страницу установки и сообщает, удалось ли это.
 */
@Composable
internal fun ShortcutActionTile(
    modifier: Modifier,
    enabled: Boolean,
    installed: Boolean?,
    selected: Boolean = false,
    onOpenShortcuts: () -> Unit,
    onOpenStore: () -> Boolean,
) {
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    val missing = installed == false
    val tileModifier = if (missing) {
        val requirement = stringResource(R.string.shortcut_install_required)
        val explain = stringResource(R.string.shortcut_install_explain)
        // Недоступен выбор ярлыка, но действие «Узнать почему» должно быть доступно.
        modifier.semantics {
            role = Role.Button
            stateDescription = requirement
            onClick(label = explain, action = null)
        }
    } else {
        modifier
    }
    ActionTile(
        label = R.string.name_shortcuts,
        icon = R.drawable.ic_action_shortcut,
        modifier = tileModifier,
        enabled = enabled && installed != null,
        opensPicker = installed == true,
        selected = selected,
        muted = missing,
        onClick = {
            when (installed) {
                true -> onOpenShortcuts()
                false -> showExplanation = true
                null -> Unit
            }
        },
    )

    LaunchedEffect(installed) {
        if (installed == true) showExplanation = false
    }
    if (showExplanation && installed != true) {
        ShizukuInstallDialog(onClose = { showExplanation = false }, onOpenStore = onOpenStore)
    }
}

@Composable
private fun ShizukuInstallDialog(onClose: () -> Unit, onOpenStore: () -> Boolean) {
    var openFailed by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.shortcut_install_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.shortcut_install_message))
                if (openFailed) {
                    Text(
                        stringResource(R.string.shortcut_install_market_failed),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onOpenStore()) onClose() else openFailed = true }) {
                Text(stringResource(R.string.shortcut_install_market))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.btn_close)) }
        },
    )
}
