package com.vivotomirpay.stub.actions

import androidx.lifecycle.viewModelScope
import com.vivotomirpay.stub.data.SettingsChange
import com.vivotomirpay.stub.data.SettingsRepository
import com.vivotomirpay.stub.data.SettingsSnapshot
import com.vivotomirpay.stub.platform.HandlerCandidate
import com.vivotomirpay.stub.platform.HandlerProbe
import com.vivotomirpay.stub.platform.HandlerSnapshot
import com.vivotomirpay.stub.platform.HandlerStatus
import com.vivotomirpay.stub.platform.PlatformCommand
import com.vivotomirpay.stub.platform.classifyHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeSettings()
    private val models = mutableListOf<ActionsViewModel>()

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateDoesNotWarn() = runTest {
        val model = model(HandlerStatus.Unavailable)
        assertEquals(HandlerStatus.Loading, model.state.value.handler.status)
        assertNull(model.state.value.settings)
        model.onIntent(ActionsIntent.ResolveHandler)
        assertNull(model.state.value.platformRequest)
        runCurrent()
        assertEquals(HandlerStatus.Unavailable, model.state.value.handler.status)
        model.onIntent(ActionsIntent.ResolveHandler)
        assertNull(model.state.value.platformRequest)
    }

    @Test
    fun singleResolvedSelfRequiresNoSetup() = runTest {
        val self = HandlerCandidate("com.vivotomirpay.stub.debug", "Double Power Key")
        val probe = HandlerProbe { classifyHandler(self.packageName, self, listOf(self)) }
        val model = model(probe)
        runCurrent()

        assertEquals(HandlerStatus.ThisApp, model.state.value.handler.status)
        model.onIntent(ActionsIntent.ResolveHandler)
        model.onIntent(ActionsIntent.OpenHandlerDetails(self.packageName))
        assertNull(model.state.value.platformRequest)
    }

    @Test
    fun otherHandlerRequestsAppDetailsNotImplicitLaunch() = runTest {
        val release = HandlerCandidate("com.vivotomirpay.stub", "Other installation")
        val probe = HandlerProbe { classifyHandler("com.vivotomirpay.stub.debug", release, listOf(release)) }
        val model = model(probe)
        runCurrent()
        assertEquals(
            HandlerStatus.OtherApp(release.packageName, release.label),
            model.state.value.handler.status,
        )

        model.onIntent(ActionsIntent.ResolveHandler)
        model.onIntent(ActionsIntent.OpenHandlerDetails("not.the.current.handler"))
        assertNull(model.state.value.platformRequest)
        model.onIntent(ActionsIntent.OpenHandlerDetails(release.packageName))
        assertEquals(
            PlatformCommand.OpenAppDetails(release.packageName),
            model.state.value.platformRequest?.command,
        )
    }

    @Test
    fun noSpecificHandlerRequestsSystemResolution() = runTest {
        val self = HandlerCandidate("com.vivotomirpay.stub.debug", "Double Power Key")
        val other = HandlerCandidate("org.example.wallet", "Wallet")
        val system = HandlerCandidate("android", "System resolver")
        val probe = HandlerProbe {
            classifyHandler(self.packageName, system, listOf(self, other, system))
        }
        val model = model(probe)
        runCurrent()
        assertEquals(HandlerStatus.NoSpecificHandler, model.state.value.handler.status)
        assertEquals(listOf(self, other), model.state.value.handler.candidates)

        model.onIntent(ActionsIntent.ResolveHandler)
        assertEquals(PlatformCommand.ResolveHandler, model.state.first().platformRequest?.command)
    }

    @Test
    fun emptyOrSystemOnlyHandlersAreUnavailable() {
        val resolver = HandlerCandidate("com.vendor.resolver", "System resolver")
        assertEquals(
            HandlerStatus.Unavailable,
            classifyHandler("com.vivotomirpay.stub", resolver, listOf(resolver)).status,
        )
        assertEquals(
            HandlerStatus.Unavailable,
            classifyHandler("com.vivotomirpay.stub", null, emptyList()).status,
        )
    }

    @Test
    fun lockscreenOffRetainsSelectedCamera() = runTest {
        val model = model(HandlerStatus.ThisApp)
        runCurrent()
        model.onIntent(ActionsIntent.SelectLockscreenAction("special:camera"))
        runCurrent()
        model.onIntent(ActionsIntent.ToggleLockscreen)
        runCurrent()
        assertFalse(model.state.value.settings!!.lockscreenEnabled)
        assertEquals("special:camera", model.state.value.settings!!.lockscreenActionId)

        model.onIntent(ActionsIntent.ToggleLockscreen)
        runCurrent()
        assertTrue(model.state.value.settings!!.lockscreenEnabled)
        assertEquals("special:camera", model.state.value.settings!!.lockscreenActionId)
    }

    @Test
    fun twoLockscreenTapsBeforePublicationInvertThePersistedValueTwice() = runTest {
        val model = model(HandlerStatus.ThisApp)
        runCurrent()
        model.onIntent(ActionsIntent.SelectLockscreenAction("special:camera"))
        runCurrent()
        repository.holdPublication = true
        repeat(2) { model.onIntent(ActionsIntent.ToggleLockscreen) }
        runCurrent()

        assertEquals(listOf(false, true), repository.lockscreenWrites)
        assertTrue(repository.snapshot().lockscreenEnabled)
        assertTrue(model.state.value.settings!!.lockscreenEnabled)
        assertEquals("special:camera", repository.snapshot().lockscreenActionId)
    }

    @Test
    fun lockscreenRejectsActionOutsideTorchAndCamera() = runTest {
        val model = model(HandlerStatus.ThisApp)
        runCurrent()
        model.onIntent(ActionsIntent.SelectLockscreenAction("special:vpn"))
        runCurrent()
        assertEquals("special:torch", model.state.value.settings!!.lockscreenActionId)
    }

    @Test
    fun resolvesLabelsAgainWhenSavedTargetChanges() = runTest {
        val model = model(HandlerProbe { HandlerSnapshot(HandlerStatus.ThisApp, emptyList()) })
        runCurrent()
        assertEquals("Mir Pay label", model.state.value.targetLabel)
        repository.saved.value = repository.saved.value.copy(targetId = "com.missing.app")
        runCurrent()
        assertEquals("com.missing.app", model.state.value.targetLabel)
    }

    @Test
    fun requestAcknowledgementIsOneShotAndIdsAreUniqueAcrossModels() = runTest {
        val first = model(HandlerStatus.NoSpecificHandler)
        val second = model(HandlerStatus.NoSpecificHandler)
        runCurrent()
        first.onIntent(ActionsIntent.ResolveHandler)
        val request = first.state.first().platformRequest!!
        first.onIntent(ActionsIntent.ResolveHandler)
        assertEquals(request, first.state.first().platformRequest)
        second.onIntent(ActionsIntent.ResolveHandler)
        val otherId = second.state.value.platformRequest!!.id

        assertNotEquals(request.id, otherId)
        assertFalse(second.acknowledgePlatformRequest(request.id))
        assertTrue(first.acknowledgePlatformRequest(request.id))
        assertFalse(first.acknowledgePlatformRequest(request.id))
        first.onIntent(ActionsIntent.ResolveHandler)
        assertNull(first.state.first().platformRequest)
        assertEquals(otherId, second.state.value.platformRequest?.id)
    }

    @Test
    fun platformResultMustMatchClaimAndSuccessfulResultRefreshesHandler() = runTest {
        var status: HandlerStatus = HandlerStatus.NoSpecificHandler
        val model = model(HandlerProbe { HandlerSnapshot(status, emptyList()) })
        runCurrent()
        model.onIntent(ActionsIntent.ResolveHandler)
        val original = model.state.value.platformRequest!!
        model.onPlatformResult(original.id, launched = false)
        assertFalse(model.state.value.platformLaunchFailed)
        assertTrue(model.acknowledgePlatformRequest(original.id))
        model.onPlatformResult(original.id, launched = false)
        assertTrue(model.state.value.platformLaunchFailed)

        model.onIntent(ActionsIntent.ResolveHandler)
        val retry = model.state.value.platformRequest!!
        assertTrue(model.acknowledgePlatformRequest(retry.id))
        model.onPlatformResult(original.id, launched = false)
        assertFalse(model.state.value.platformLaunchFailed)
        status = HandlerStatus.ThisApp
        model.onPlatformResult(retry.id, launched = true)
        runCurrent()
        assertEquals(HandlerStatus.ThisApp, model.state.value.handler.status)
    }

    @Test
    fun resumeInvalidatesQueuedResolutionBeforeProbeCompletes() = runTest {
        val other = HandlerStatus.OtherApp("org.example.wallet", "Wallet")
        var status: HandlerStatus = HandlerStatus.NoSpecificHandler
        val model = model(HandlerProbe { HandlerSnapshot(status, emptyList()) })
        runCurrent()
        model.onIntent(ActionsIntent.ResolveHandler)
        val oldRequest = model.state.value.platformRequest!!

        status = other
        model.onIntent(ActionsIntent.RefreshHandler)
        // The worker has not been allowed to publish a fresh result.
        assertFalse("Resume must revoke the queued request immediately", model.acknowledgePlatformRequest(oldRequest.id))
        model.onIntent(ActionsIntent.ResolveHandler)
        assertNull(model.state.value.platformRequest)

        runCurrent()
        assertEquals(other, model.state.value.handler.status)
        assertFalse(model.acknowledgePlatformRequest(oldRequest.id))
        model.onIntent(ActionsIntent.ResolveHandler)
        assertNull(model.state.value.platformRequest)
        model.onIntent(ActionsIntent.OpenHandlerDetails(other.packageName))
        val freshRequest = model.state.value.platformRequest!!
        assertEquals(PlatformCommand.OpenAppDetails(other.packageName), freshRequest.command)
        assertTrue(model.acknowledgePlatformRequest(freshRequest.id))
    }

    @Test
    fun successfulSystemReturnBlocksRepeatTapUntilProbeCompletes() = runTest {
        val other = HandlerStatus.OtherApp("org.example.wallet", "Wallet")
        var status: HandlerStatus = HandlerStatus.NoSpecificHandler
        val model = model(HandlerProbe { HandlerSnapshot(status, emptyList()) })
        runCurrent()
        model.onIntent(ActionsIntent.ResolveHandler)
        val launched = model.state.value.platformRequest!!
        assertTrue(model.acknowledgePlatformRequest(launched.id))

        status = other
        model.onPlatformResult(launched.id, launched = true)
        model.onIntent(ActionsIntent.ResolveHandler)
        assertNull("Returning from the chooser must not re-enable resolution before the probe", model.state.value.platformRequest)

        // onResume may arrive in the same turn as the result.
        model.onIntent(ActionsIntent.RefreshHandler)
        model.onIntent(ActionsIntent.ResolveHandler)
        assertNull(model.state.value.platformRequest)

        runCurrent()
        assertEquals(other, model.state.value.handler.status)
        assertFalse(model.acknowledgePlatformRequest(launched.id))
        model.onIntent(ActionsIntent.ResolveHandler)
        assertNull(model.state.value.platformRequest)
    }

    @Test
    fun resumeAlsoInvalidatesQueuedAppDetailsEvenIfTheHandlerStaysTheSame() = runTest {
        val other = HandlerStatus.OtherApp("org.example.wallet", "Wallet")
        val model = model(other)
        runCurrent()
        val openDetails = ActionsIntent.OpenHandlerDetails(other.packageName)
        model.onIntent(openDetails)
        val oldRequest = model.state.value.platformRequest!!

        model.onIntent(ActionsIntent.RefreshHandler)
        assertFalse("Resume must also revoke queued app details", model.acknowledgePlatformRequest(oldRequest.id))
        model.onIntent(openDetails)
        assertNull(model.state.value.platformRequest)

        runCurrent()
        assertFalse(model.acknowledgePlatformRequest(oldRequest.id))
        model.onIntent(openDetails)
        val newRequest = model.state.value.platformRequest!!
        assertNotEquals(oldRequest.id, newRequest.id)
        assertEquals(PlatformCommand.OpenAppDetails(other.packageName), newRequest.command)
        assertTrue(model.acknowledgePlatformRequest(newRequest.id))
    }

    private fun TestScope.model(status: HandlerStatus) =
        model(HandlerProbe { HandlerSnapshot(status, emptyList()) })

    private fun TestScope.model(probe: HandlerProbe) = ActionsViewModel(
        repository,
        probe,
        ActionLabelResolver { id, _ -> if (id == "ru.nspk.mirpay") "Mir Pay label" else id },
        StandardTestDispatcher(testScheduler),
    ).also(models::add)

    private class FakeSettings : SettingsRepository {
        override fun editMenu(edit: com.vivotomirpay.stub.menu.MenuEdit) = error("Unexpected menu edit: $edit")
        val saved = MutableStateFlow(
            SettingsSnapshot(
                targetId = "ru.nspk.mirpay",
                lockscreenEnabled = true,
                lockscreenActionId = "special:torch",
                vibrateEnabled = true,
                vibrateDurationMs = 40,
                vibratePattern = "single",
                vpnClient = "auto",
                menuItems = listOf("special:torch", "special:camera", "special:vpn", "special:circle_to_search"),
                triggerLog = "",
            ),
        )

        override fun observe() = saved

        var holdPublication = false
        private var unpublished: SettingsSnapshot? = null
        val lockscreenWrites = mutableListOf<Boolean>()

        override fun snapshot(): SettingsSnapshot = unpublished ?: saved.value

        override fun update(change: SettingsChange) {
            val current = snapshot()
            val next = when (change) {
                is SettingsChange.LockscreenEnabled -> {
                    lockscreenWrites += change.enabled
                    current.copy(lockscreenEnabled = change.enabled)
                }
                is SettingsChange.LockscreenAction -> current.copy(lockscreenActionId = change.id)
                else -> error("Unexpected settings change: $change")
            }
            if (holdPublication) unpublished = next else saved.value = next
        }
    }
}
