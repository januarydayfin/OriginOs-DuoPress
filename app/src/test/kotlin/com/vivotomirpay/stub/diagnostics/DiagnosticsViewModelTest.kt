package com.vivotomirpay.stub.diagnostics

import androidx.lifecycle.viewModelScope
import com.vivotomirpay.stub.platform.HandlerCandidate
import com.vivotomirpay.stub.platform.HandlerProbe
import com.vivotomirpay.stub.platform.HandlerSnapshot
import com.vivotomirpay.stub.platform.HandlerStatus
import com.vivotomirpay.stub.platform.PlatformCommand
import com.vivotomirpay.stub.settings.Task3SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = Task3SettingsRepository(triggerLog = "Vivo trigger → Mir Pay")
    private val wallet = HandlerCandidate("org.example.wallet", "Wallet")
    private val other = HandlerStatus.OtherApp(wallet.packageName, wallet.label)
    private var handler = HandlerSnapshot(HandlerStatus.NoSpecificHandler, emptyList())
    private lateinit var model: DiagnosticsViewModel

    @Before
    fun setMain() {
        Dispatchers.setMain(dispatcher)
        model = DiagnosticsViewModel(repository, HandlerProbe { handler }, dispatcher)
    }

    @After
    fun resetMain() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun clearLogReflectsRepository() = runTest {
        runCurrent()
        assertEquals("Vivo trigger → Mir Pay", model.state.value.triggerLog)
        model.onIntent(DiagnosticsIntent.ClearLog)
        runCurrent()
        assertEquals("", repository.snapshot().triggerLog)
        assertEquals("", model.state.value.triggerLog)
    }

    @Test
    fun testDoesNotMarkHandlerConfigured() = runTest {
        handler = HandlerSnapshot(other, listOf(wallet))
        model.onIntent(DiagnosticsIntent.Resume)
        runCurrent()
        model.onIntent(DiagnosticsIntent.TestAction)
        val request = checkNotNull(model.state.value.platformRequest)
        assertEquals(PlatformCommand.TestAction, request.command)
        assertTrue(model.acknowledgePlatformRequest(request.id))
        assertFalse(model.acknowledgePlatformRequest(request.id))

        // No log/result from an explicit test proves reassignment. Only a probe on Resume may change the status.
        handler = HandlerSnapshot(HandlerStatus.ThisApp, emptyList())
        model.onPlatformResult(request.id, launched = true)
        runCurrent()
        assertEquals(HandlerSnapshot(other, listOf(wallet)), model.state.value.handler)
    }

    @Test
    fun resumeRefreshesDeviceAndHandlerStatus() = runTest {
        model.onIntent(DiagnosticsIntent.Resume)
        runCurrent()
        assertEquals(handler, model.state.value.handler)
        handler = HandlerSnapshot(other, listOf(wallet))

        model.onIntent(DiagnosticsIntent.Resume)
        runCurrent()
        assertEquals(handler, model.state.value.handler)
        assertNull(model.state.value.platformRequest)
    }

    @Test
    fun resumeRevokesUnacknowledgedResolverAndThenRoutesOtherAppToDetails() = runTest {
        model.onIntent(DiagnosticsIntent.Resume)
        runCurrent()
        model.onIntent(DiagnosticsIntent.ConfigureHandler)
        val resolver = checkNotNull(model.state.value.platformRequest)
        assertEquals(PlatformCommand.ResolveHandler, resolver.command)

        handler = HandlerSnapshot(other, listOf(wallet))
        model.onIntent(DiagnosticsIntent.Resume)
        assertFalse(model.acknowledgePlatformRequest(resolver.id))
        model.onIntent(DiagnosticsIntent.ConfigureHandler)
        assertNull(model.state.value.platformRequest)
        runCurrent()

        model.onIntent(DiagnosticsIntent.ConfigureHandler)
        val details = checkNotNull(model.state.value.platformRequest)
        assertEquals(PlatformCommand.OpenAppDetails(wallet.packageName), details.command)
        assertFalse(model.acknowledgePlatformRequest(resolver.id))
        assertTrue(model.acknowledgePlatformRequest(details.id))
    }

    @Test
    fun successfulHandlerResultBlocksNewConfigurationUntilFreshStatusPublishes() = runTest {
        model.onIntent(DiagnosticsIntent.Resume)
        runCurrent()
        model.onIntent(DiagnosticsIntent.ConfigureHandler)
        val resolver = checkNotNull(model.state.value.platformRequest)
        model.onPlatformResult(resolver.id, launched = false)
        assertFalse(model.state.value.platformLaunchFailed)
        assertTrue(model.acknowledgePlatformRequest(resolver.id))
        handler = HandlerSnapshot(HandlerStatus.ThisApp, emptyList())

        model.onPlatformResult(resolver.id, launched = true)
        model.onIntent(DiagnosticsIntent.ConfigureHandler)
        assertNull(model.state.value.platformRequest)
        runCurrent()
        assertEquals(HandlerStatus.ThisApp, model.state.value.handler.status)
        model.onIntent(DiagnosticsIntent.ConfigureHandler)
        assertNull(model.state.value.platformRequest)
    }
}
