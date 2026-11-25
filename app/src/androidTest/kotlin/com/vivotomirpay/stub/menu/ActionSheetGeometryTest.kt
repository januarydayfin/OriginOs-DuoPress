package com.vivotomirpay.stub.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowInsets
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivotomirpay.stub.R
import com.vivotomirpay.stub.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ActionSheetGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tallSheetStaysBelowTheCutoutButScrimStillFillsTheShortViewport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val viewportHeight = 220.dp
        val cutout = 100.dp
        compose.setContent {
            val top = with(LocalDensity.current) { cutout.roundToPx() }
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.WindowInsets(
                        WindowInsetsCompat.Builder()
                            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, top, 0, 0))
                            .build(),
                    ),
                ) {
                    AppTheme {
                        Box(Modifier.size(width = 300.dp, height = viewportHeight)) {
                            ActionSheet(
                                items = List(16) { MenuItemUi("item$it", "Long menu item $it") },
                                onSelected = {},
                                onDismiss = {},
                            )
                        }
                    }
                }
            }
        }

        val scrim = compose.onNodeWithContentDescription(context.getString(android.R.string.cancel))
            .fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText(context.getString(R.string.menu_title))
            .fetchSemanticsNode().boundsInRoot
        val expectedHeight = with(compose.density) { viewportHeight.toPx() }
        val safeTop = scrim.top + with(compose.density) { cutout.toPx() }

        assertEquals(expectedHeight, scrim.height, 1f)
        assertTrue("Sheet title at y=${title.top}, above safe top y=$safeTop", title.top >= safeTop - 1f)
        assertTrue("Sheet title is inside the viewport", title.bottom <= scrim.bottom)
    }
}
