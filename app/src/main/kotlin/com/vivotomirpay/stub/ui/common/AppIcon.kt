package com.vivotomirpay.stub.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vivotomirpay.stub.R
import com.vivotomirpay.stub.ui.theme.AppTheme

/**
 * Декоративный значок строки. Вызывает main-safe загрузчик только пока строка в композиции.
 * При отсутствии значка показывает запасной, не влияет на текст и выбор строки.
 */
@Composable
fun AppIcon(
    entryKey: Any,
    loadIcon: suspend () -> ImageBitmap?,
    modifier: Modifier = Modifier,
    @DrawableRes fallback: Int = R.drawable.ic_action_app,
) = key(entryKey) {
    val loader by rememberUpdatedState(loadIcon)
    val bitmap by produceState<ImageBitmap?>(null) { value = loader() }
    val icon = bitmap
    if (icon == null) {
        Icon(painterResource(fallback), contentDescription = null, modifier = modifier.size(28.dp))
    } else {
        Image(icon, contentDescription = null, modifier = modifier.size(28.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun AppIconPreview() {
    AppTheme { AppIcon("example", loadIcon = { null }) }
}
