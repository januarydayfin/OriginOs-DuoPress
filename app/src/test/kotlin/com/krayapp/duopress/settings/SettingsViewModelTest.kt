package com.krayapp.duopress.settings

import androidx.lifecycle.viewModelScope
import com.krayapp.duopress.data.SettingsChange
import com.krayapp.duopress.data.SettingsRepository
import com.krayapp.duopress.data.SettingsSnapshot
import com.krayapp.duopress.platform.DeviceStatus
import com.krayapp.duopress.platform.DeviceStatusProbe
import com.krayapp.duopress.platform.PlatformCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = Task3SettingsRepository()
    private var device = DeviceStatus(happAvailable = true, incyAvailable = true, vpnActive = false)
    private lateinit var model: SettingsViewModel

    @Before
    fun setMain() {
        Dispatchers.setMain(dispatcher)
        model = SettingsViewModel(repository, DeviceStatusProbe { device }, dispatcher)
    }

    @After
    fun resetMain() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun sliderPersistsAndPreviewsOnceOnGestureEnd() = runTest {
        runCurrent()
        model.onIntent(SettingsIntent.ChangeDuration(300))
        runCurrent()
        assertEquals(300, model.state.value.durationDraftMs)
        assertEquals(40, repository.snapshot().vibrateDurationMs)
        assertNull(model.state.value.platformRequest)

        for (ms in listOf(20, 500)) {
            model.onIntent(SettingsIntent.ChangeDuration(ms))
            model.onIntent(SettingsIntent.CommitDuration)
            runCurrent()
            val request = checkNotNull(model.state.value.platformRequest)
            assertEquals(PlatformCommand.PreviewVibration(ms, "single"), request.command)
            assertEquals(ms, repository.snapshot().vibrateDurationMs)
            assertTrue(model.acknowledgePlatformRequest(request.id))
            assertFalse(model.acknowledgePlatformRequest(request.id))
            model.onPlatformResult(request.id, launched = true)
            runCurrent()
            assertNull(model.state.value.platformRequest)
        }
        assertEquals(listOf(SettingsChange.VibrationDuration(20), SettingsChange.VibrationDuration(500)), repository.writes)
    }

    @Test
    fun vpnClientAbsentRetainsChoice() = runTest {
        device = DeviceStatus(happAvailable = false, incyAvailable = true, vpnActive = false)
        model.onIntent(SettingsIntent.Resume)
        runCurrent()
        model.onIntent(SettingsIntent.SelectVpnClient("happ"))
        runCurrent()
        model.onIntent(SettingsIntent.Resume)
        runCurrent()

        assertEquals("happ", repository.snapshot().vpnClient)
        assertEquals("happ", model.state.value.settings?.vpnClient)
        assertEquals(false, model.state.value.device?.happAvailable)
        assertNull(model.state.value.platformRequest)
    }

    @Test
    fun resumeRefreshesVpnStatus() = runTest {
        model.onIntent(SettingsIntent.Resume)
        runCurrent()
        assertEquals(device, model.state.value.device)
        device = DeviceStatus(happAvailable = false, incyAvailable = true, vpnActive = true)

        model.onIntent(SettingsIntent.Resume)
        runCurrent()
        assertEquals(device, model.state.value.device)
        assertNull(model.state.value.platformRequest)
    }

    @Test
    fun toggleAndPatternStillPreviewEvenWhenVibrationIsDisabled() = runTest {
        runCurrent()
        model.onIntent(SettingsIntent.ToggleVibration)
        model.onIntent(SettingsIntent.SelectPattern("double"))
        runCurrent()

        val toggle = checkNotNull(model.state.value.platformRequest)
        assertEquals(PlatformCommand.PreviewVibration(40, "single"), toggle.command)
        assertFalse(repository.snapshot().vibrateEnabled)
        assertEquals("double", repository.snapshot().vibratePattern)
        assertTrue(model.acknowledgePlatformRequest(toggle.id))
        model.onPlatformResult(toggle.id, launched = true)
        val pattern = checkNotNull(model.state.value.platformRequest)
        assertNotEquals(toggle.id, pattern.id)
        assertEquals(PlatformCommand.PreviewVibration(40, "double"), pattern.command)
    }

    @Test
    fun twoVibrationTapsBothToggleBeforeRepositoryPublishes() = runTest {
        val published = MutableStateFlow(repository.snapshot())
        val delayedRepository = object : SettingsRepository by repository {
            override fun observe() = published
        }
        val delayedModel = SettingsViewModel(delayedRepository, DeviceStatusProbe { device }, dispatcher)
        try {
            runCurrent()
            repeat(2) {
                val displayed = checkNotNull(delayedModel.state.value.settings)
                assertTrue("Both taps must see the same published value", displayed.vibrateEnabled)
                delayedModel.onIntent(SettingsIntent.ToggleVibration)
                runCurrent()
            }

            assertEquals(
                listOf(SettingsChange.VibrationEnabled(false), SettingsChange.VibrationEnabled(true)),
                repository.writes,
            )
            assertTrue(repository.snapshot().vibrateEnabled)
            published.value = repository.snapshot()
            runCurrent()
            assertEquals(true, delayedModel.state.value.settings?.vibrateEnabled)

            val first = checkNotNull(delayedModel.state.value.platformRequest)
            assertEquals(PlatformCommand.PreviewVibration(40, "single"), first.command)
            assertTrue(delayedModel.acknowledgePlatformRequest(first.id))
            delayedModel.onPlatformResult(first.id, launched = true)
            val second = checkNotNull(delayedModel.state.value.platformRequest)
            assertEquals(first.command, second.command)
            assertNotEquals(first.id, second.id)
            assertTrue(delayedModel.acknowledgePlatformRequest(second.id))
            delayedModel.onPlatformResult(second.id, launched = true)
            assertNull(delayedModel.state.value.platformRequest)
        } finally {
            delayedModel.viewModelScope.cancel()
        }
    }

}

internal class Task3SettingsRepository(triggerLog: String = "") : SettingsRepository {
    override fun editMenu(edit: com.krayapp.duopress.menu.MenuEdit) = error("Unexpected menu edit: $edit")
    private val saved = MutableStateFlow(
        SettingsSnapshot("ru.nspk.mirpay", true, "special:torch", true, 40, "single", "auto", emptyList(), triggerLog),
    )
    val writes = mutableListOf<SettingsChange>()

    override fun observe() = saved
    override fun snapshot() = saved.value

    override fun update(change: SettingsChange) {
        saved.value = when (change) {
            is SettingsChange.VibrationEnabled -> saved.value.copy(vibrateEnabled = change.enabled)
            is SettingsChange.VibrationDuration -> saved.value.copy(vibrateDurationMs = change.ms)
            is SettingsChange.VibrationPattern -> saved.value.copy(vibratePattern = change.pattern)
            is SettingsChange.VpnClient -> saved.value.copy(vpnClient = change.client)
            SettingsChange.ClearLog -> saved.value.copy(triggerLog = "")
            else -> error("Unexpected settings change: $change")
        }
        writes += change
    }
}
