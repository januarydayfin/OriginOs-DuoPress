package com.vivotomirpay.stub.menu

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivotomirpay.stub.MenuActivity
import com.vivotomirpay.stub.PrefsHelper
import com.vivotomirpay.stub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionSheetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun emptyMenuAndBackDismiss() = withMenu("[]") { scenario ->
        compose.onNodeWithText(context.getString(R.string.menu_empty)).assertIsDisplayed()

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitFinish(scenario)
        assertEquals("test log before opening menu", PrefsHelper.getLastTriggerLog(context))
    }

    @Test
    fun doubleTapDispatchesOnce() = withMenu(
        """["app:org.example.menu_test_first","app:org.example.menu_test_second"]""",
    ) { scenario ->
        val first = "org.example.menu_test_first"
        val second = "org.example.menu_test_second"
        val selectFirst = compose.onNodeWithText(first).fetchSemanticsNode().config[SemanticsActions.OnClick]
        val selectSecond = compose.onNodeWithText(second).fetchSemanticsNode().config[SemanticsActions.OnClick]
        // Deliver both callbacks in the same main-loop turn, before recomposition can disable rows.
        compose.runOnIdle {
            requireNotNull(selectFirst.action).invoke()
            requireNotNull(selectSecond.action).invoke()
        }
        awaitFinish(scenario)
        val log = PrefsHelper.getLastTriggerLog(context).orEmpty()
        assertTrue("Only the first selection may reach ActionRunner: $log", log.contains("Target: $first\n"))
    }

    @Test
    fun outsideTapDismissesWithoutDispatch() = withMenu("[]") { scenario ->
        compose.onNodeWithContentDescription(context.getString(android.R.string.cancel))
            .performTouchInput { click(Offset(center.x, height * 0.15f)) }
        awaitFinish(scenario)
        assertEquals("test log before opening menu", PrefsHelper.getLastTriggerLog(context))
    }

    private fun awaitFinish(scenario: ActivityScenario<MenuActivity>) {
        compose.waitUntil(timeoutMillis = 5_000) { scenario.state == Lifecycle.State.DESTROYED }
    }

    private fun withMenu(json: String, block: (ActivityScenario<MenuActivity>) -> Unit) {
        val prefs = context.getSharedPreferences("vivo_swipe_stub_prefs", Context.MODE_PRIVATE)
        val keys = listOf("menu_items_json", "last_trigger_log")
        val previous = keys.associateWith { prefs.getString(it, null) }
        try {
            assertTrue(prefs.edit().putString(keys[0], json).putString(keys[1], "test log before opening menu").commit())
            ActivityScenario.launch(MenuActivity::class.java).use(block)
        } finally {
            val restore = prefs.edit()
            previous.forEach { (key, value) ->
                if (value == null) restore.remove(key) else restore.putString(key, value)
            }
            assertTrue(restore.commit())
        }
    }
}
