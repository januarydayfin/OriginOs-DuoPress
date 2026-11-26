package com.krayapp.duopress.ui.common

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.krayapp.duopress.R
import kotlinx.coroutines.delay

/** Закрывается только после задержки; передаёт итоговое значение чекбокса. */
@Composable
internal fun VendorWarningDialog(onClose: (doNotShowAgain: Boolean) -> Unit) {
    var doNotShowAgain by rememberSaveable { mutableStateOf(false) }
    val closeAfter = rememberSaveable { SystemClock.elapsedRealtime() + CLOSE_DELAY_MS }
    var canClose by remember { mutableStateOf(SystemClock.elapsedRealtime() >= closeAfter) }
    LaunchedEffect(closeAfter) {
        delay((closeAfter - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        canClose = true
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.vendor_warning_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.vendor_warning_message))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(doNotShowAgain, role = Role.Checkbox) { doNotShowAgain = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = doNotShowAgain, onCheckedChange = null)
                    Text(stringResource(R.string.vendor_warning_opt_out))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onClose(doNotShowAgain) }, enabled = canClose) {
                Text(stringResource(R.string.btn_close))
            }
        },
    )
}

private const val CLOSE_DELAY_MS = 3_000L
