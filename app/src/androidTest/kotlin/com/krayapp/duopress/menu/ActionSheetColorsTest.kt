package com.krayapp.duopress.menu

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class ActionSheetColorsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun lightSheetAndActionUseDistinctSurfaceRoles() = assertSurfaceRoles(lightColorScheme())

    @Test
    fun darkSheetAndActionUseDistinctSurfaceRoles() = assertSurfaceRoles(darkColorScheme())

    private fun assertSurfaceRoles(scheme: ColorScheme) {
        val label = "Torch"
        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                ActionSheet(
                    items = listOf(MenuItemUi("torch", label)),
                    onSelected = {},
                    onDismiss = {},
                )
            }
        }

        val action = compose.onNode(hasClickAction() and hasText(label)).fetchSemanticsNode().boundsInRoot
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val y = action.center.y.toInt()

        // Sample the sheet gutter and the empty trailing area of the card at the same height.
        val sheetColor = pixels[(action.left / 2).toInt(), y]
        val cardColor = pixels[(action.right - action.width / 4).toInt(), y]
        assertEquals("Sheet fill", scheme.surface.toArgb(), sheetColor.toArgb())
        assertEquals("Action fill", scheme.surfaceContainer.toArgb(), cardColor.toArgb())
    }
}
