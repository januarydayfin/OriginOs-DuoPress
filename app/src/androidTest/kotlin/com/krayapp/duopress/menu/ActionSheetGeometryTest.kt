package com.krayapp.duopress.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowInsets
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.krayapp.duopress.R
import com.krayapp.duopress.ui.theme.AppTheme
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
        setSheetContent(viewportHeight)

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

    @Test
    fun canScrollToAndTapAnActionInTheShortSafeArea() {
        val picks = mutableListOf<String>()
        setSheetContent(viewportHeight = 182.dp, onSelected = { picks += it })
        val last = compose.onNodeWithText("Long menu item 15")
        last.assertIsNotDisplayed()

        val scroll = compose.onNode(hasScrollAction())
        val minimumTouchHeight = with(compose.density) { 48.dp.toPx() }
        val scrollHeight = scroll.fetchSemanticsNode().boundsInRoot.height
        assertTrue("Only $scrollHeight px are available for scrolling", scrollHeight >= minimumTouchHeight - 1f)
        scroll.performScrollToNode(hasText("Long menu item 15"))

        val visibleHeight = last.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.height
        assertTrue("Only $visibleHeight px of the action is visible", visibleHeight >= minimumTouchHeight - 1f)
        last.performTouchInput { click() }

        compose.runOnIdle { assertEquals(listOf("item15"), picks) }
    }

    private val cutout = 100.dp

    private fun setSheetContent(viewportHeight: Dp, onSelected: (String) -> Unit = {}) {
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
                                onSelected = onSelected,
                                onDismiss = {},
                            )
                        }
                    }
                }
            }
        }
    }
}
