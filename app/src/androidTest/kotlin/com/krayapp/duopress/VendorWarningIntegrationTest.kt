package com.krayapp.duopress

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VendorWarningIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = PrefsHelper.preferences(context)
    private val optOutKey = "skip_unsupported_vendor_warning"
    private var originalOptOut: Boolean? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun clearOptOut() {
        originalOptOut = prefs.getBoolean(optOutKey, false).takeIf { prefs.contains(optOutKey) }
        assertTrue(prefs.edit().remove(optOutKey).commit())
    }

    @After
    fun restoreOptOut() {
        scenario?.close()
        MainActivity.dependenciesOverride = null
        val edit = prefs.edit()
        originalOptOut?.let { edit.putBoolean(optOutKey, it) } ?: edit.remove(optOutKey)
        assertTrue(edit.commit())
    }

    @Test
    fun unsupportedDeviceCannotDismissWarningExceptWithTimedCloseButton() {
        val launchedAt = SystemClock.elapsedRealtime()
        launch()
        node(R.string.vendor_warning_title).assertIsDisplayed()
        node(R.string.btn_close).assertIsNotEnabled()
        input("keyevent KEYCODE_BACK")
        node(R.string.vendor_warning_title).assertIsDisplayed()
        input("tap 5 ${context.resources.displayMetrics.heightPixels / 2}")
        node(R.string.vendor_warning_title).assertIsDisplayed()
        awaitCloseEnabled()
        assertTrue(SystemClock.elapsedRealtime() - launchedAt >= 3_000)
        input("keyevent KEYCODE_BACK")
        node(R.string.vendor_warning_title).assertIsDisplayed()
        node(R.string.btn_close).performClick()
        node(R.string.vendor_warning_title).assertDoesNotExist()
    }

    @Test
    fun supportedDeviceDoesNotShowWarning() {
        launch(supported = true)
        node(R.string.actions_double_press).assertIsDisplayed()
        node(R.string.vendor_warning_title).assertDoesNotExist()
    }

    @Test
    fun acknowledgedOptOutSurvivesNewLaunch() {
        launch()
        node(R.string.vendor_warning_opt_out).performClick()
        awaitCloseEnabled()
        node(R.string.btn_close).performClick()

        launch()
        node(R.string.actions_double_press).assertIsDisplayed()
        node(R.string.vendor_warning_title).assertDoesNotExist()
    }

    @Test
    fun vendorWarningSurvivesRecreateUntilClosed() {
        launch()
        node(R.string.vendor_warning_opt_out).performClick()
        requireNotNull(scenario).recreate()
        node(R.string.vendor_warning_opt_out).assertIsOn().performClick()
        awaitCloseEnabled()
        node(R.string.btn_close).performClick()

        requireNotNull(scenario).recreate()
        node(R.string.vendor_warning_title).assertDoesNotExist()

        launch()
        node(R.string.vendor_warning_title).assertIsDisplayed()
        node(R.string.btn_close).assertIsNotEnabled()
    }

    private fun launch(supported: Boolean = false) {
        scenario?.close()
        MainActivity.dependenciesOverride = { AppDependencies(it, isSupportedDevice = { supported }) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun awaitCloseEnabled() {
        compose.waitUntil(6_000) {
            compose.onAllNodes(hasText(context.getString(R.string.btn_close)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        node(R.string.btn_close).assertIsEnabled()
    }

    private fun node(@StringRes label: Int) = compose.onNodeWithText(context.getString(label))

    private fun input(args: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input $args"),
        ).use { it.readBytes() }
    }
}
