package com.krayapp.duopress.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.krayapp.duopress.R

/**
 * Показывает действие в редакторе меню или на экране выбора двойного нажатия.
 *
 * @param label ресурс названия для пользователя.
 * @param icon ресурс декоративной иконки действия.
 * @param modifier задаёт положение и ширину плитки в ряду.
 * @param enabled разрешает нажатие.
 * @param alreadyAdded показывает подпись редактора об уже добавленном действии.
 * @param opensPicker показывает стрелку перехода к дополнительному выбору.
 * @param selected отмечает текущее действие цветом и семантикой, не блокируя нажатие.
 * @param muted приглушает фон, не отключая действие (например, объяснение недоступности).
 * @param onClick запускает выбор или открывает вложенный экран.
 */
@Composable
internal fun ActionTile(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    modifier: Modifier,
    enabled: Boolean,
    alreadyAdded: Boolean = false,
    opensPicker: Boolean = false,
    selected: Boolean = false,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = if (selected) modifier.semantics { this.selected = true } else modifier,
        enabled = enabled,
        colors = when {
            muted -> CardDefaults.cardColors(containerColor = colors.surfaceVariant, contentColor = colors.onSurfaceVariant)
            selected -> CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)
            else -> CardDefaults.cardColors(containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer)
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(painterResource(icon), contentDescription = null)
                if (opensPicker) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
            if (alreadyAdded) Text(stringResource(R.string.menu_item_added), style = MaterialTheme.typography.labelSmall)
        }
    }
}
