package com.vivotomirpay.stub

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivotomirpay.stub.platform.AndroidShortcutGateway
import com.vivotomirpay.stub.platform.HandlerProbe
import com.vivotomirpay.stub.platform.HandlerSnapshot
import com.vivotomirpay.stub.platform.HandlerStatus
import com.vivotomirpay.stub.platform.PermissionSendGate
import com.vivotomirpay.stub.platform.ShortcutControl
import com.vivotomirpay.stub.platform.ShortcutEntry
import com.vivotomirpay.stub.platform.ShortcutGateway
import com.vivotomirpay.stub.platform.ShortcutPermissionStart
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeAppIntegrationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.getSharedPreferences("vivo_swipe_stub_prefs", Context.MODE_PRIVATE)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var originalTarget: String? = null

    @Before
    fun saveDebugTarget() {
        assertEquals("com.vivotomirpay.stub.debug", context.packageName)
        originalTarget = prefs.getString("target_package", null)
    }

    @After
    fun restoreDebugTarget() {
        if (::scenario.isInitialized) scenario.close()
        MainActivity.dependenciesOverride = null
        val edit = prefs.edit()
        if (originalTarget == null) edit.remove("target_package")
        else edit.putString("target_package", originalTarget)
        assertTrue(edit.commit())
    }

    @Test
    fun launchShowsActionsWithoutLegacyHints() {
        launch()
        assertActions()
        compose.onNode(heading(R.string.app_name)).assertIsDisplayed()
        compose.onNode(text(R.string.header_subtitle)).assertDoesNotExist()
        compose.onNode(text(R.string.card_action_hint)).assertDoesNotExist()
        compose.onNode(text(R.string.switch_vibrate)).assertDoesNotExist()
    }

    @Test
    fun navigatePickReturnAndSeeCommittedAction() {
        assertTrue(prefs.edit().putString("target_package", "special:camera").commit())
        launch()
        openActionPicker()
        compose.onNode(text(R.string.preset_torch)).performClick()
        assertActions()
        compose.waitUntil(5_000) { prefs.getString("target_package", null) == "special:torch" }
        compose.onNode(text(R.string.actions_double_press) and text(R.string.name_torch)).assertIsDisplayed()
        compose.onNode(hasContentDescription(context.getString(R.string.btn_back))).assertDoesNotExist()
        systemBack()
        // Back from the launcher root backgrounds the task on Android 12+, and finishes on older versions.
        compose.waitUntil(5_000) { !scenario.state.isAtLeast(Lifecycle.State.STARTED) }
    }

    @Test
    fun staleShortcutsClickCannotNavigateInTheSameTurnAsActionSave() {
        assertTrue(prefs.edit().putString("target_package", "special:camera").commit())
        val shizuku = fakeShizuku()
        launch()
        openActionPicker()
        val selectTorch = checkNotNull(
            compose.onNode(text(R.string.preset_torch)).fetchSemanticsNode().config[SemanticsActions.OnClick].action,
        )
        compose.onNode(hasScrollAction()).performScrollToNode(text(R.string.name_shortcuts))
        val openShortcuts = checkNotNull(
            compose.onNode(text(R.string.name_shortcuts)).fetchSemanticsNode().config[SemanticsActions.OnClick].action,
        )

        // Actual launcher callbacks in one main turn, before Compose sees Saving or the host can pop.
        compose.runOnIdle {
            selectTorch()
            openShortcuts()
        }
        compose.waitUntil(5_000) { prefs.getString("target_package", null) == "special:torch" }
        compose.onNode(heading(R.string.shortcut_picker_title)).assertDoesNotExist()
        assertActions()
        assertTrue(shizuku.requests.isEmpty())
    }

    @Test
    fun settingsDiagnosticsAreReachableWithoutBackStackDuplicates() {
        launch()
        tab(R.string.nav_settings).performClick()
        compose.onNode(heading(R.string.nav_settings)).assertIsDisplayed()
        tab(R.string.nav_diagnostics).performClick()
        compose.onNode(heading(R.string.nav_diagnostics)).assertIsDisplayed()
        tab(R.string.nav_settings).performClick()
        tab(R.string.nav_settings).performClick()
        systemBack()
        assertActions()

        openShortcutPicker()
        systemBack()
        compose.onNode(heading(R.string.picker_title_main)).assertIsDisplayed()
        clickBack()
        assertActions()
    }

    @Test
    fun systemPermissionDeliveryNotRepeatedOnActivityRecreation() {
        val shizuku = fakeShizuku()
        launch()
        requestShortcutPermission()
        val code = shizuku.requests.single()
        val application = context.applicationContext as Application
        var deliveriesDuringDestroy = 0
        val callbacks = object : ActivityCallbacks() {
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is MainActivity) {
                    deliveriesDuringDestroy++
                    shizuku.deliver(code, granted = false)
                }
            }
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            scenario.recreate()
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
        assertEquals(1, deliveriesDuringDestroy)
        awaitText(R.string.shortcut_permission_denied)
        assertEquals(listOf(code), shizuku.requests)
        assertEquals(0, shizuku.listenerCount)
    }

    @Test
    fun exitedPermissionCodeCannotCompleteNextShortcutRoute() {
        val shizuku = fakeShizuku()
        launch()
        requestShortcutPermission()
        val oldCode = shizuku.requests.single()
        systemBack()
        compose.onNode(heading(R.string.picker_title_main)).assertIsDisplayed()
        assertEquals(0, shizuku.listenerCount)
        clickBack()
        assertActions()

        requestShortcutPermission()
        val newCode = shizuku.requests.last()
        assertEquals(2, shizuku.requests.size)
        assertTrue(oldCode != newCode)
        compose.runOnIdle { shizuku.deliver(oldCode, granted = false) }
        compose.onNode(text(R.string.shortcut_permission_waiting)).assertIsDisplayed()
        compose.onNode(text(R.string.shortcut_permission_denied)).assertDoesNotExist()
        compose.runOnIdle {
            shizuku.deliver(newCode, granted = false)
            shizuku.deliver(newCode, granted = true)
        }
        awaitText(R.string.shortcut_permission_denied)
        assertEquals(0, shizuku.listenerCount)
    }

    @Test
    fun rejectedPermissionDispatchReprobesInsteadOfShowingDenial() {
        val shizuku = fakeShizuku().apply { rejectDispatch = true }
        launch()
        openShortcutPicker()
        awaitText(R.string.shortcut_permission_request)
        compose.onNode(text(R.string.shortcut_permission_request)).performClick()
        awaitText(R.string.shortcut_shizuku_unavailable)
        compose.onNode(text(R.string.shortcut_permission_denied)).assertDoesNotExist()
        assertEquals(1, shizuku.requests.size)
        assertEquals(0, shizuku.listenerCount)
    }

    @Test
    fun terminalBackDuringPermissionPrecheckPreventsRealDispatchPathFromSending() {
        val enteredPrecheck = CountDownLatch(1)
        val releasePrecheck = CountDownLatch(1)
        val rationaleChecks = AtomicInteger()
        val sentCodes = CopyOnWriteArrayList<Int>()
        val worker = Executors.newSingleThreadExecutor()
        worker.asCoroutineDispatcher().use { dispatcher ->
            val gateway = AndroidShortcutGateway(
                context, dispatcher,
                checkStatus = { ShizukuShortcuts.Status.PERMISSION_DENIED },
                showRationale = {
                    if (rationaleChecks.incrementAndGet() == 2) {
                        enteredPrecheck.countDown()
                        check(releasePrecheck.await(10, SECONDS))
                    }
                    false
                },
                sendPermissionRequest = { sentCodes += it },
            )
            val shizuku = FakeShizuku(permissionGateway = gateway)
            MainActivity.dependenciesOverride = { AppDependencies(it, shortcuts = gateway, shortcutControl = shizuku) }
            try {
                launch()
                requestShortcutPermission()
                assertTrue("Production permission precheck was not entered", enteredPrecheck.await(5, SECONDS))
                systemBack()
                compose.onNode(heading(R.string.picker_title_main)).assertIsDisplayed()
                assertEquals(0, shizuku.listenerCount)
                releasePrecheck.countDown()
                worker.submit {}.get(5, SECONDS)
                assertEquals("The revoked precheck must not invoke the SDK sender", emptyList<Int>(), sentCodes.toList())

                clickBack()
                requestShortcutPermission()
                compose.waitUntil(5_000) { sentCodes.size == 1 }
                assertTrue(sentCodes.single() > 0)
            } finally {
                releasePrecheck.countDown()
            }
        }
    }

    @Test
    fun successfulDispatchWithoutPauseDoesNotBlockTheNextTab() {
        useHandlerProbe(TestHandlerProbe(HandlerStatus.OtherApp("example.other.handler", "Other handler")))
        launch()
        awaitText(R.string.actions_open_handler_details)
        val pauses = AtomicInteger()
        scenario.onActivity { activity ->
            activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) pauses.incrementAndGet()
            })
        }

        val intents = captureHandlerDetailsWithoutActivityResult {
            compose.onNode(text(R.string.actions_open_handler_details)).performClick()
            tab(R.string.nav_diagnostics).performClick()
            awaitOtherHandler()
            compose.onNode(text(R.string.btn_appinfo)).performScrollTo().performClick()
            compose.waitForIdle()
        }
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        assertEquals(0, pauses.get())
        assertEquals(
            listOf("package:example.other.handler", "package:example.other.handler"),
            intents.map(Intent::getDataString),
        )
        compose.onNode(text(R.string.diagnostics_platform_failed)).assertDoesNotExist()
    }

    @Test
    fun completedDispatchReprobesOnceWhenTheActiveRouteActuallyResumes() {
        val probe = TestHandlerProbe(HandlerStatus.OtherApp("example.other.handler", "Other handler"))
        useHandlerProbe(probe)
        launch()
        tab(R.string.nav_diagnostics).performClick()
        awaitOtherHandler()
        val beforeDispatch = probe.calls.get()
        val intents = captureHandlerDetailsWithoutActivityResult {
            compose.onNode(text(R.string.btn_appinfo)).performScrollTo().performClick()
            compose.waitUntil(5_000) { probe.calls.get() > beforeDispatch }
        }
        assertEquals(1, intents.size)
        assertEquals(beforeDispatch + 1, probe.calls.get())
        assertEquals(Lifecycle.State.RESUMED, scenario.state)

        scenario.moveToState(Lifecycle.State.STARTED)
        probe.status = HandlerStatus.NoSpecificHandler
        assertEquals(beforeDispatch + 1, probe.calls.get())
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitText(R.string.actions_handler_no_specific)
        assertEquals(beforeDispatch + 2, probe.calls.get())
        compose.onNode(text(R.string.diagnostics_platform_failed)).assertDoesNotExist()
    }

    @Test
    fun diagnosticsTestUsesOwnSwipeEntryAndDetailsUseOtherHandlerPackage() {
        val probe = TestHandlerProbe(HandlerStatus.OtherApp("example.other.handler", "Other handler"))
        useHandlerProbe(probe)
        launch()
        tab(R.string.nav_diagnostics).performClick()
        val expectedLabel = awaitOtherHandler()
        val beforeTestAction = probe.calls.get()
        val intents = captureIntents { intent ->
            when (intent.action) {
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> throw ActivityNotFoundException("Test surface")
                else -> null
            }
        } doWhile {
            blockWithoutActivityResult(IntentFilter("vivo.intent.action.NFC_BUS_SWINGSPLASH")) {
                compose.onNode(text(R.string.btn_test)).performScrollTo().performClick()
                compose.onNode(expectedLabel).assertIsDisplayed()
                assertEquals(beforeTestAction, probe.calls.get())
            }
            compose.onNode(text(R.string.btn_appinfo)).performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(text(R.string.diagnostics_platform_failed)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        assertEquals(2, intents.size)
        assertEquals(ComponentName("com.vivotomirpay.stub.debug", "com.vivotomirpay.stub.SwipeEntryActivity"), intents[0].component)
        assertEquals("vivo.intent.action.NFC_BUS_SWINGSPLASH", intents[0].action)
        assertEquals("2", intents[0].getStringExtra("lb_from"))
        assertEquals(setOf("lb_from"), intents[0].extras?.keySet())
        assertEquals(null, intents[0].categories)
        assertEquals(null, intents[0].data)
        assertEquals(null, intents[0].`package`)
        assertEquals(0, intents[0].flags)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents[1].action)
        assertEquals("package:example.other.handler", intents[1].dataString)
    }

    @Test
    fun diagnosticSelfResolutionUsesNeutralStatusAndOpensOwnVariantDetails() {
        useHandlerProbe(TestHandlerProbe(HandlerStatus.ThisApp))
        launch()
        tab(R.string.nav_diagnostics).performClick()
        val status = hasText(context.getString(R.string.actions_handler_current, context.getString(R.string.app_name)))
        compose.waitUntil(5_000) { compose.onAllNodes(status).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(status).assertIsDisplayed()
        compose.onNode(text(R.string.btn_resolve)).assertDoesNotExist()

        val intents = captureHandlerDetailsWithoutActivityResult {
            compose.onNode(text(R.string.btn_appinfo)).performScrollTo().performClick()
        }
        assertEquals("package:com.vivotomirpay.stub.debug", intents.single().dataString)
    }

    @Test
    fun diagnosticNoSpecificHandlerKeepsResolverAndSeparateOwnVariantDetails() {
        val probe = TestHandlerProbe(HandlerStatus.NoSpecificHandler)
        useHandlerProbe(probe)
        launch()
        tab(R.string.nav_diagnostics).performClick()
        awaitText(R.string.actions_handler_no_specific)
        compose.onNode(text(R.string.btn_resolve)).performScrollTo().assertIsDisplayed()
        val resolverIntents = captureIntents { null } doWhile {
            val filter = IntentFilter("vivo.intent.action.NFC_BUS_SWINGSPLASH").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            blockWithoutActivityResult(filter) {
                compose.onNode(text(R.string.btn_resolve)).performClick()
                compose.waitForIdle()
            }
        }
        val resolver = resolverIntents.single()
        assertEquals("vivo.intent.action.NFC_BUS_SWINGSPLASH", resolver.action)
        assertEquals(setOf(Intent.CATEGORY_DEFAULT), resolver.categories)
        assertEquals(null, resolver.component)
        assertEquals(null, resolver.`package`)
        assertEquals("2", resolver.getStringExtra("lb_from"))
        awaitText(R.string.actions_handler_no_specific)
        val intents = captureHandlerDetailsWithoutActivityResult {
            compose.onNode(text(R.string.btn_appinfo)).performScrollTo().performClick()
            compose.waitForIdle()
        }
        assertEquals("package:com.vivotomirpay.stub.debug", intents.single().dataString)
    }

    @Test
    fun assistantSettingsTryLegacyFallbacksInOrder() {
        launch()
        tab(R.string.nav_settings).performClick()
        val intents = captureIntents { throw ActivityNotFoundException("Test surface") } doWhile {
            compose.onNode(text(R.string.btn_assistant)).performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(text(R.string.settings_platform_failed)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        assertEquals(
            listOf(
                "android.settings.VOICE_INPUT_SETTINGS",
                "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
                "android.settings.MANAGE_ASSISTANT",
                "android.settings.APPLICATION_DETAILS_SETTINGS",
            ),
            intents.map(Intent::getAction),
        )
        assertTrue(intents.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
        assertEquals("package:com.google.android.googlequicksearchbox", intents.last().dataString)
    }

    @Test
    fun editorBackFirstClosesEmbeddedPickerAndPreviewOpensRealMenu() {
        launch()
        compose.onNode(text(R.string.card_menu_title)).performScrollTo().performClick()
        compose.onNode(heading(R.string.editor_menu_title)).assertIsDisplayed()
        scrollEditorTo(R.string.editor_add_app)
        compose.onNode(text(R.string.editor_add_app)).performClick()
        compose.onNode(heading(R.string.editor_add_app)).assertIsDisplayed()
        clickBack()
        compose.onNode(heading(R.string.editor_add_app)).assertDoesNotExist()
        compose.onNode(heading(R.string.editor_menu_title)).assertIsDisplayed()
        scrollEditorTo(R.string.editor_add_action)
        compose.onNode(text(R.string.editor_add_action)).performClick()
        compose.onNode(heading(R.string.editor_add_action)).assertIsDisplayed()
        systemBack()
        compose.onNode(heading(R.string.editor_add_action)).assertDoesNotExist()
        compose.onNode(heading(R.string.editor_menu_title)).assertIsDisplayed()

        val intents = captureIntents { null } doWhile {
            scrollEditorTo(R.string.btn_menu_preview)
            compose.onNode(text(R.string.btn_menu_preview)).performClick()
            awaitText(R.string.menu_subtitle)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { scenario.state == Lifecycle.State.RESUMED }
        }
        val launch = intents.single()
        assertEquals(ComponentName("com.vivotomirpay.stub.debug", "com.vivotomirpay.stub.MenuActivity"), launch.component)
        assertEquals(Intent.FLAG_ACTIVITY_NO_ANIMATION, launch.flags)
        compose.onNode(heading(R.string.editor_menu_title)).assertIsDisplayed()
        clickBack()
        assertActions()
    }

    private fun useHandlerProbe(probe: HandlerProbe) {
        MainActivity.dependenciesOverride = { AppDependencies(it, handlerProbe = probe) }
    }

    private fun awaitOtherHandler(): SemanticsMatcher {
        val label = hasText(context.getString(R.string.actions_handler_current, "Other handler"))
        compose.waitUntil(5_000) { compose.onAllNodes(label).fetchSemanticsNodes().isNotEmpty() }
        return label
    }

    private fun captureHandlerDetailsWithoutActivityResult(block: () -> Unit): List<Intent> =
        captureIntents { null } doWhile {
            val filter = IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { addDataScheme("package") }
            blockWithoutActivityResult(filter, block)
        }

    private fun blockWithoutActivityResult(filter: IntentFilter, block: () -> Unit) {
        // Returning a canned ActivityResult can cause a synthetic pause/resume even without a launch.
        val blocker = instrumentation.addMonitor(filter, null, true)
        try {
            block()
        } finally {
            instrumentation.removeMonitor(blocker)
        }
    }

    private fun scrollEditorTo(@StringRes label: Int) {
        compose.onNode(hasScrollAction()).performScrollToNode(text(label))
    }

    private fun captureIntents(intercept: (Intent) -> Instrumentation.ActivityResult?): CapturedIntents =
        CapturedIntents(intercept)

    private inner class CapturedIntents(
        private val intercept: (Intent) -> Instrumentation.ActivityResult?,
    ) {
        infix fun doWhile(block: () -> Unit): List<Intent> {
            val intents = CopyOnWriteArrayList<Intent>()
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    intents += Intent(intent)
                    return intercept(intent)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                block()
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            return intents.toList()
        }
    }

    private fun fakeShizuku() = FakeShizuku().also { fake ->
        MainActivity.dependenciesOverride = { AppDependencies(it, shortcuts = fake, shortcutControl = fake) }
    }

    private fun requestShortcutPermission() {
        openShortcutPicker()
        awaitText(R.string.shortcut_permission_request)
        compose.onNode(text(R.string.shortcut_permission_request)).performClick()
        awaitText(R.string.shortcut_permission_waiting)
    }

    private fun awaitText(@StringRes label: Int) {
        compose.waitUntil(5_000) { compose.onAllNodes(text(label)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(text(label)).assertIsDisplayed()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun openActionPicker() {
        compose.onNode(text(R.string.actions_double_press)).performClick()
        compose.onNode(heading(R.string.picker_title_main)).assertIsDisplayed()
    }

    private fun openShortcutPicker() {
        openActionPicker()
        compose.onNode(hasScrollAction()).performScrollToNode(text(R.string.name_shortcuts))
        compose.onNode(text(R.string.name_shortcuts)).performClick()
        compose.onNode(heading(R.string.shortcut_picker_title)).assertIsDisplayed()
    }

    private fun clickBack() {
        compose.onNode(hasContentDescription(context.getString(R.string.btn_back))).performClick()
    }

    @Suppress("DEPRECATION")
    private fun systemBack() {
        compose.waitForIdle()
        scenario.onActivity { it.onBackPressed() }
    }

    private fun assertActions() {
        tab(R.string.nav_actions).assertIsSelected()
        compose.onNode(text(R.string.actions_double_press)).assertIsDisplayed()
    }

    private fun tab(@StringRes label: Int) = compose.onNode(isSelectable() and text(label))

    private fun text(@StringRes label: Int) = hasText(context.getString(label))

    private fun heading(@StringRes label: Int) =
        text(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
}

private class TestHandlerProbe(@Volatile var status: HandlerStatus) : HandlerProbe {
    val calls = AtomicInteger()

    override fun inspect(): HandlerSnapshot {
        calls.incrementAndGet()
        return HandlerSnapshot(status, emptyList())
    }
}

private class FakeShizuku(private val permissionGateway: AndroidShortcutGateway? = null) : ShortcutGateway, ShortcutControl {
    private val listeners = mutableSetOf<(Int, Boolean) -> Unit>()
    private val requestCodes = mutableListOf<Int>()
    val requests: List<Int> get() = requestCodes.toList()
    val listenerCount: Int get() = listeners.size
    var rejectDispatch = false
    @Volatile private var probeStatus = ShizukuShortcuts.Status.PERMISSION_DENIED

    override fun status() = probeStatus
    override fun canRequestPermission() = true
    override suspend fun shortcuts(packageName: String) = emptyList<ShortcutEntry>()
    override suspend fun managerIntent(): Intent? = null

    override suspend fun requestPermission(requestCode: Int, sendGate: PermissionSendGate): ShortcutPermissionStart {
        requestCodes += requestCode
        if (permissionGateway != null) return permissionGateway.requestPermission(requestCode, sendGate)
        if (rejectDispatch) {
            probeStatus = ShizukuShortcuts.Status.UNAVAILABLE
            return ShortcutPermissionStart.Rejected
        }
        return ShortcutPermissionStart.WaitingForResult
    }

    override fun listen(onResult: (Int, Boolean) -> Unit): AutoCloseable {
        listeners += onResult
        return AutoCloseable { listeners -= onResult }
    }

    fun deliver(code: Int, granted: Boolean) {
        listeners.toList().forEach { it(code, granted) }
    }
}

private open class ActivityCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
