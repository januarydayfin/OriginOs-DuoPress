package com.krayapp.duopress.menu

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.krayapp.duopress.PrefsHelper
import com.krayapp.duopress.R
import com.krayapp.duopress.ui.theme.AppTheme
import kotlinx.coroutines.flow.first

/**
 * Строка рабочей панели.
 *
 * @property id сохранённое действие для передачи исполнителю.
 * @property fallbackIcon ресурс для спец-действия или для отсутствующего значка.
 * @property appIcon цветной значок приложения; не тонируется.
 */
data class MenuItemUi(
    val id: String,
    val label: String,
    @DrawableRes val fallbackIcon: Int = R.drawable.ic_action_app,
    val appIcon: ImageBitmap? = null,
)

/**
 * Рисует панель в текущем окне, не создавая Dialog.
 *
 * @param items пункты панели после применения правил видимости.
 * @param onSelected передаёт ID выбранного действия хосту.
 * @param onDismiss сообщает хосту о Back или тапе за пределами панели.
 * @param modifier модификатор корневой области панели и затемнения.
 */
@Composable
fun ActionSheet(
    items: List<MenuItemUi>,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) = ActionSheet(items, onSelected, onDismiss, closing = false, onHidden = {}, modifier = modifier)

@Composable
internal fun ActionSheet(
    items: List<MenuItemUi>?,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    closing: Boolean,
    onHidden: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = !closing } }
    val scrimAlpha = remember { Animatable(0f) }
    val notifyHidden by rememberUpdatedState(onHidden)

    BackHandler(onBack = onDismiss)
    LaunchedEffect(closing) {
        visibility.targetState = !closing
        if (closing) {
            snapshotFlow { visibility.isIdle && !visibility.currentState }.first { it }
            notifyHidden()
        }
    }
    LaunchedEffect(closing) {
        scrimAlpha.animateTo(
            targetValue = if (closing) 0f else 0.32f,
            animationSpec = tween(if (closing) 160 else 200),
        )
    }

    val dismissLabel = stringResource(android.R.string.cancel)
    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(
            Modifier.matchParentSize()
                .drawBehind { drawRect(Color.Black, alpha = scrimAlpha.value) }
                .semantics { contentDescription = dismissLabel }
                .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onDismiss),
        )
        val screenHeight = maxHeight
        AnimatedVisibility(
            visibleState = visibility,
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            enter = slideInVertically(animationSpec = tween(260, easing = LinearOutSlowInEasing)) { it },
            exit = slideOutVertically(animationSpec = tween(220, easing = FastOutLinearInEasing)) { it },
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 6.dp,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        // Примерно прежняя высота списка плюс место для заголовка на обычном экране.
                        .heightIn(max = screenHeight * 0.55f + 88.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier.align(Alignment.CenterHorizontally)
                                    .size(width = 32.dp, height = 4.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                            )
                            Text(
                                stringResource(R.string.menu_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                stringResource(R.string.menu_subtitle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    when {
                        items == null -> item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        items.isEmpty() -> item {
                            Text(
                                stringResource(R.string.menu_empty),
                                modifier = Modifier.padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> items(items, key = { it.id }) { item ->
                            MenuActionRow(item, enabled = !closing) { onSelected(item.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuActionRow(item: MenuItemUi, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.label) },
        leadingContent = {
            val icon = item.appIcon
            if (icon == null) {
                Icon(painterResource(item.fallbackIcon), contentDescription = null, modifier = Modifier.size(24.dp))
            } else {
                Image(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        },
        modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@DrawableRes
internal fun menuItemIcon(id: String): Int = when (id) {
    PrefsHelper.SPECIAL_TORCH -> R.drawable.ic_action_torch
    PrefsHelper.SPECIAL_CAMERA -> R.drawable.ic_action_camera
    PrefsHelper.SPECIAL_VPN -> R.drawable.ic_action_vpn
    PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH -> R.drawable.ic_action_search
    PrefsHelper.SPECIAL_MENU -> R.drawable.ic_action_menu
    else -> R.drawable.ic_action_app
}

@Preview(showBackground = true)
@Composable
private fun ActionSheetPreview() {
    AppTheme {
        ActionSheet(
            listOf(MenuItemUi(PrefsHelper.SPECIAL_TORCH, "Flashlight", R.drawable.ic_action_torch)),
            onSelected = {},
            onDismiss = {},
        )
    }
}
