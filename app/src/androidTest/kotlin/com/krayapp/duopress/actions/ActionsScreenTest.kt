package com.krayapp.duopress.actions

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krayapp.duopress.R
import com.krayapp.duopress.data.SettingsSnapshot
import com.krayapp.duopress.platform.HandlerSnapshot
import com.krayapp.duopress.platform.HandlerStatus
import com.krayapp.duopress.ui.navigation.AppRoute
import com.krayapp.duopress.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun actionsRowsAreClickableAndShowCurrentSummaries() {
        val opened = mutableListOf<AppRoute>()
        val state = fixture().copy(
            settings = fixture().settings!!.copy(lockscreenEnabled = false),
            handler = HandlerSnapshot(HandlerStatus.ThisApp, emptyList()),
        )
        compose.setContent {
            AppTheme { ActionsScreen(state, onIntent = {}, onOpen = { opened.add(it) }) }
        }

        val resources = compose.activity.resources
        compose.onNode(hasText(resources.getString(R.string.actions_double_press)) and hasText("Installed wallet"))
            .assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNode(
            hasText(resources.getString(R.string.card_lock_title)) and
                hasText(resources.getString(R.string.actions_same_as_unlocked)),
        ).assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNode(
            hasText(resources.getString(R.string.card_menu_title)) and
                hasText(resources.getQuantityString(R.plurals.actions_menu_count, 2, 2)),
        ).assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(AppRoute.ActionPicker, AppRoute.Lockscreen, AppRoute.MenuEditor), opened)
        compose.onNodeWithText(resources.getString(R.string.actions_configure_handler)).assertDoesNotExist()
        compose.onNodeWithText(resources.getString(R.string.actions_open_handler_details)).assertDoesNotExist()
    }

    @Test
    fun lockscreenToggleAndRadioRowsExposeStateAndClickAction() {
        val state = mutableStateOf(fixture())
        compose.setContent {
            AppTheme {
                LockscreenScreen(state.value, onIntent = { action ->
                    if (action == ActionsIntent.ToggleLockscreen) {
                        val settings = state.value.settings!!
                        state.value = state.value.copy(
                            settings = settings.copy(lockscreenEnabled = !settings.lockscreenEnabled),
                        )
                    }
                })
            }
        }

        val resources = compose.activity.resources
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertHasClickAction()
        val toggle = compose.onNodeWithText(resources.getString(R.string.switch_lock))
        val camera = compose.onNodeWithText(resources.getString(R.string.name_camera))
        toggle.assertIsOn().assertHeightIsAtLeast(48.dp)
        camera.assertIsSelected().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        toggle.performClick()
        toggle.assertIsOff()
        camera.assertDoesNotExist()
        compose.onNodeWithText(resources.getString(R.string.lock_hint_off)).assertIsDisplayed()
        toggle.performClick()
        camera.assertIsSelected()
    }

    private fun fixture() = ActionsState(
        settings = SettingsSnapshot(
            targetId = "org.example.wallet",
            lockscreenEnabled = true,
            lockscreenActionId = "special:camera",
            vibrateEnabled = true,
            vibrateDurationMs = 40,
            vibratePattern = "single",
            vpnClient = "auto",
            menuItems = listOf("special:torch", "special:camera"),
            triggerLog = "",
        ),
        targetLabel = "Installed wallet",
        lockscreenLabel = "Selected camera",
    )
}
