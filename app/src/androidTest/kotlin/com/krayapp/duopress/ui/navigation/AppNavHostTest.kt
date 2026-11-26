package com.krayapp.duopress.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krayapp.duopress.ui.theme.AppTheme
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavHostTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actionsTab = hasText("Actions") or hasText("Действия")
    private val settingsTab = hasText("Settings") or hasText("Настройки")
    private val diagnosticsTab = hasText("Diagnostics") or hasText("Диагностика")
    private val backButton = hasContentDescription("Back") or hasContentDescription("Назад")
    private var actionsEntry: NavBackStackEntry? = null

    @Before
    fun showHost() {
        compose.setContent {
            AppTheme {
                AppNavHost(render = { route, entry, navigator ->
                    if (route == AppRoute.Actions) SideEffect { actionsEntry = entry }
                    TestScreen(route, entry, navigator)
                })
            }
        }
    }

    @Test
    fun routeChangesRemoveTheOutgoingScreenWithoutWaitingForAnimation() {
        assertScreen("Actions screen")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNode(settingsTab).performClick()
            assertScreenReplaced("Actions screen", "Settings screen")

            systemBack()
            assertScreenReplaced("Settings screen", "Actions screen")

            compose.onNodeWithText("Edit menu").performClick()
            assertScreenReplaced("Actions screen", "Menu editor screen")

            compose.onNode(backButton).performClick()
            assertScreenReplaced("Menu editor screen", "Actions screen")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun rootNavigationShowsCorrectSectionAndNeverDuplicates() {
        assertScreen("Actions screen")
        assertSelectedTab(actionsTab)
        compose.onNode(settingsTab).performClick()
        assertScreen("Settings screen")
        assertSelectedTab(settingsTab)
        compose.onNodeWithText("Setting edits: 0").performClick()

        compose.onNode(diagnosticsTab).performClick()
        assertScreen("Diagnostics screen")
        assertSelectedTab(diagnosticsTab)
        compose.onNode(settingsTab).performClick()
        compose.onNodeWithText("Setting edits: 1").assertIsDisplayed()
        compose.onNode(diagnosticsTab).performClick()
        compose.onNode(diagnosticsTab).performClick()
        systemBack()
        assertScreen("Actions screen")
        assertSelectedTab(actionsTab)

        compose.onNode(settingsTab).performClick()
        systemBack()
        assertScreen("Actions screen")
        assertSelectedTab(actionsTab)
    }

    @Test
    fun nestedRouteHidesBarAndBackRestoresActions() {
        compose.onNodeWithText("Edit menu").performClick()
        assertScreen("Menu editor screen")
        compose.onAllNodes(isSelectable()).assertCountEquals(0)
        compose.onNode(backButton).assertIsDisplayed().performClick()
        assertScreen("Actions screen")
        assertSelectedTab(actionsTab)

        compose.onNodeWithText("Edit lockscreen").performClick()
        assertScreen("Lockscreen screen")
        compose.onAllNodes(isSelectable()).assertCountEquals(0)
        systemBack()
        assertScreen("Actions screen")
        assertSelectedTab(actionsTab)
    }

    @Test
    fun selectionReturnsToActions() {
        val original = compose.runOnIdle { requireNotNull(actionsEntry) }
        compose.onNodeWithText("Pick action").performClick()
        assertScreen("Action picker screen")
        compose.onNodeWithText("Use action").performClick()
        assertScreen("Actions screen")
        compose.runOnIdle { assertSame(original, actionsEntry) }

        compose.onNodeWithText("Pick action").performClick()
        assertScreen("Action picker screen")
        compose.onNodeWithText("Pick shortcut").performClick()
        assertScreen("Shortcut picker screen")
        compose.onNodeWithText("Use shortcut").performClick()
        assertScreen("Actions screen")
        compose.runOnIdle { assertSame(original, actionsEntry) }
        assertSelectedTab(actionsTab)
        compose.onAllNodes(backButton).assertCountEquals(0)
        systemBack()
        val scenario = compose.activityRule.scenario
        compose.waitUntil { scenario.state == Lifecycle.State.DESTROYED }
    }

    private fun systemBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun assertScreen(label: String) {
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    private fun assertScreenReplaced(outgoing: String, incoming: String) {
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText(outgoing).assertDoesNotExist()
        assertScreen(incoming)
    }

    private fun assertSelectedTab(tab: SemanticsMatcher) {
        compose.onAllNodes(isSelectable()).assertCountEquals(3)
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        compose.onNode(tab).assertIsSelected()
    }
}

@Composable
private fun TestScreen(route: AppRoute, entry: NavBackStackEntry, navigator: AppNavigator) {
    Column {
        when (route) {
            AppRoute.Actions -> {
                Text("Actions screen")
                Button(onClick = { navigator.open(AppRoute.MenuEditor) }) { Text("Edit menu") }
                Button(onClick = { navigator.open(AppRoute.Lockscreen) }) { Text("Edit lockscreen") }
                Button(onClick = { navigator.open(AppRoute.ActionPicker) }) { Text("Pick action") }
            }
            AppRoute.Settings -> {
                Text("Settings screen")
                var edits by rememberSaveable { mutableIntStateOf(0) }
                Button(onClick = { edits++ }) { Text("Setting edits: $edits") }
            }
            AppRoute.Diagnostics -> Text("Diagnostics screen")
            AppRoute.MenuEditor -> Text("Menu editor screen")
            AppRoute.Lockscreen -> Text("Lockscreen screen")
            AppRoute.ActionPicker -> {
                Text("Action picker screen")
                Button(onClick = { navigator.completeActionSelection(entry, { true }, {}) }) { Text("Use action") }
                Button(onClick = { navigator.open(AppRoute.ShortcutPicker) }) { Text("Pick shortcut") }
            }
            AppRoute.ShortcutPicker -> {
                Text("Shortcut picker screen")
                Button(onClick = { navigator.completeActionSelection(entry, { true }, {}) }) { Text("Use shortcut") }
            }
        }
    }
}
