package com.krayapp.duopress.menu

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.krayapp.duopress.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MenuEditorReorderTest {
    @get:Rule val compose = createComposeRule()
    private val original = listOf("app:a", "special:camera", "app:a", "app:b")
    private val saved = mutableStateOf(original)
    private val rollback = mutableStateOf(0L)
    private val moves = mutableListOf<MenuEditorIntent.Move>()
    private val pulses = mutableListOf<HapticFeedbackType>()
    private val recorder = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            pulses += hapticFeedbackType
        }
    }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setup() {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides recorder) {
                MenuEditorScreen(MenuEditorState(menuItems = saved.value, loadingMenu = false, moveRollback = rollback.value), {
                    if (it is MenuEditorIntent.Move) moves.add(it)
                })
            }
        }
    }

    private fun dragSecondDuplicate() {
        val tag = context.getString(R.string.editor_drag, "a")
        val handle = compose.onAllNodesWithContentDescription(tag, useUnmergedTree = true)[1].fetchSemanticsNode().boundsInRoot
        val previous = compose.onAllNodesWithContentDescription(tag, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val start = handle.center
        val delta = Offset(0f, (previous.center.y - start.y) / 2)
        val root = compose.onRoot()
        root.performTouchInput { down(start); moveBy(delta * .35f) }
        compose.waitForIdle()
        root.performTouchInput { moveBy(delta * .75f) }
        compose.waitForIdle()
    }

    @Test fun dropMovesOnlyTheSecondDuplicate() {
        setup()
        dragSecondDuplicate()
        assertTrue(moves.isEmpty())
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(MenuEditorIntent.Move(2, 1, original), moves.single())
            assertDragPulses()
        }
    }

    @Test fun dropKeepsVisibleOrderUntilSavedSnapshotArrives() {
        setup()
        val appNodes = compose.onAllNodesWithText("a").fetchSemanticsNodes().map { it.id }
        val cameraNode = compose.onNodeWithText(context.getString(R.string.name_camera)).fetchSemanticsNode().id
        dragSecondDuplicate()
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        val cameraLabel = context.getString(R.string.name_camera)
        fun assertCameraBelowBothApps() {
            val cameraY = compose.onNodeWithText(cameraLabel).fetchSemanticsNode().boundsInRoot.center.y
            val appYs = compose.onAllNodesWithText("a").fetchSemanticsNodes().map { it.boundsInRoot.center.y }
            assertTrue("cameraY=$cameraY, appYs=$appYs", appYs.size == 2 && appYs.all { it < cameraY })
        }
        compose.runOnIdle { assertEquals(MenuEditorIntent.Move(2, 1, original), moves.single()) }
        assertCameraBelowBothApps()
        compose.runOnIdle { saved.value = listOf("app:a", "app:a", "special:camera", "app:b") }
        assertCameraBelowBothApps()
        assertEquals(appNodes, compose.onAllNodesWithText("a").fetchSemanticsNodes().map { it.id })
        assertEquals(cameraNode, compose.onNodeWithText(cameraLabel).fetchSemanticsNode().id)
    }

    @Test fun cancelledDragDoesNotSave() {
        setup()
        dragSecondDuplicate()
        compose.onRoot().performTouchInput { cancel() }
        compose.runOnIdle { assertTrue(moves.isEmpty()); assertDragPulses() }
    }

    @Test fun rejectedDropRestoresOriginalPositionsAndAllowsRetry() {
        setup()
        dragSecondDuplicate()
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle { rollback.value++ }
        val cameraY = compose.onNodeWithText(context.getString(R.string.name_camera)).fetchSemanticsNode().boundsInRoot.center.y
        val above = compose.onAllNodesWithText("a").fetchSemanticsNodes().count { it.boundsInRoot.center.y < cameraY }
        assertEquals(1, above)
        dragSecondDuplicate()
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle { assertEquals(List(2) { MenuEditorIntent.Move(2, 1, original) }, moves) }
    }

    private fun assertDragPulses() {
        val step = if (android.os.Build.VERSION.SDK_INT >= 34) HapticFeedbackType.SegmentTick else HapticFeedbackType.VirtualKey
        assertEquals(listOf(HapticFeedbackType.LongPress, step), pulses)
    }

    @Test fun tapWithoutDragDoesNotPulse() {
        setup()
        val tag = context.getString(R.string.editor_drag, "a")
        compose.onAllNodesWithContentDescription(tag, useUnmergedTree = true)[0].performTouchInput { click() }
        compose.runOnIdle { assertTrue(pulses.isEmpty()); assertTrue(moves.isEmpty()) }
    }

    @Test fun changedSnapshotInvalidatesDrag() {
        setup()
        dragSecondDuplicate()
        compose.runOnIdle { saved.value = original.drop(1) }
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle { assertTrue(moves.isEmpty()) }
    }

    @Test fun accessibilityMovesByRawPosition() {
        setup()
        val rows = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val up = context.getString(R.string.editor_move_up, "a")
        assertFalse(rows[0].fetchSemanticsNode().config[SemanticsActions.CustomActions].any { it.label == up })
        val action = rows[2].fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == up }
        compose.runOnIdle { assertTrue(action.action()); assertEquals(MenuEditorIntent.Move(2, 1, original), moves.single()) }
    }
}
