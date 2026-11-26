package com.krayapp.duopress.picker

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krayapp.duopress.data.SettingsChange
import com.krayapp.duopress.data.SettingsRepository
import com.krayapp.duopress.data.SettingsSnapshot
import com.krayapp.duopress.platform.AppCatalog
import com.krayapp.duopress.platform.AppEntry
import com.krayapp.duopress.platform.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class ActionPickerViewModelTest : PickerViewModelTestFixture() {
    @Test
    fun presetsContainOnlyBuiltInSpecialActions() {
        assertEquals(
            setOf("special:torch", "special:camera", "special:vpn", "special:circle_to_search", "special:menu"),
            ActionPreset.entries.map { it.id }.toSet(),
        )
    }

    @Test
    fun openingPickerDoesNotChangePreviouslySavedWallet() = runTest {
        repository.saved.value = repository.saved.value.copy(targetId = "com.vivo.wallet")
        val model = model()
        runCurrent()

        assertEquals("com.vivo.wallet", model.state.value.targetId)
        assertTrue(repository.writes.isEmpty())
    }

    @Test
    fun mirPayCanBePickedFromAppSearch() = runTest {
        val mirPay = AppEntry("ru.nspk.mirpay", "Mir Pay")
        catalog.entries = listOf(mirPay, AppEntry("ru.nspk.sbpay", "SBPay"))
        val model = model()
        runCurrent()
        model.onIntent(ActionPickerIntent.Search("Mir Pay"))
        runCurrent()

        assertEquals(listOf(mirPay), model.state.value.visibleApps)
        model.onIntent(ActionPickerIntent.PickApp(mirPay.packageName, "Mir Pay"))
        runCurrent()
        assertEquals(listOf(SettingsChange.Target("ru.nspk.mirpay")), repository.writes)
        assertTrue(model.state.value.selectionComplete)
    }

    @Test
    fun sbPayCanBePickedByPackageName() = runTest {
        val model = model()
        runCurrent()
        model.onIntent(ActionPickerIntent.ChangeManualPackage("ru.nspk.sbpay"))
        model.onIntent(ActionPickerIntent.PickManualPackage)
        runCurrent()

        assertEquals(listOf(SettingsChange.Target("ru.nspk.sbpay")), repository.writes)
        assertTrue(model.state.value.selectionComplete)
    }

    @Test
    fun primaryPickPersistsExistingId() = runTest {
        val model = model()
        runCurrent()
        model.onIntent(ActionPickerIntent.PickPreset(ActionPreset.CircleToSearch))
        model.onIntent(ActionPickerIntent.PickPreset(ActionPreset.Camera))
        runCurrent()

        assertEquals("special:circle_to_search", repository.saved.value.targetId)
        assertEquals(listOf(SettingsChange.Target("special:circle_to_search")), repository.writes)
        assertTrue(model.state.value.selectionComplete)
    }

    @Test
    fun manualPackageSurvivesCatalogRemoval() = runTest {
        catalog.entries = listOf(AppEntry("com.example.app", "Example"))
        val model = model()
        runCurrent()
        model.onIntent(ActionPickerIntent.ChangeManualPackage("  com.example.app \t"))
        catalog.entries = emptyList()
        model.onIntent(ActionPickerIntent.ReloadApps)
        runCurrent()

        assertTrue(model.state.value.apps.isEmpty())
        model.onIntent(ActionPickerIntent.PickManualPackage)
        runCurrent()
        assertEquals("com.example.app", repository.saved.value.targetId)
        assertTrue(model.state.value.selectionComplete)
    }

    @Test
    fun missingIconKeepsSearchResult() = runTest {
        val entry = AppEntry("com.example.app", "Example")
        catalog.entries = listOf(entry, AppEntry("com.other", "Other"))
        val model = model()
        runCurrent()
        model.onIntent(ActionPickerIntent.Search("  EXAMPLE  "))
        runCurrent()
        assertEquals(listOf(entry), model.state.value.visibleApps)
        assertNull(model.icon(entry.packageName))
        model.onIntent(ActionPickerIntent.Search("com.example"))
        runCurrent()
        assertEquals(listOf(entry), model.state.value.visibleApps)
        model.onIntent(ActionPickerIntent.PickApp(entry.packageName, "com.example"))
        runCurrent()

        assertEquals("com.example.app", repository.saved.value.targetId)
        assertEquals(listOf(AppScope.Installed), catalog.scopes)
        assertEquals(listOf("com.example.app"), catalog.iconRequests)
    }

    @Test
    fun changingQueryImmediatelyInvalidatesDisplayedApps() = runTest {
        catalog.entries = listOf(AppEntry("com.alpha", "Alpha"), AppEntry("com.beta", "Beta"))
        val model = model()
        runCurrent()
        model.onIntent(ActionPickerIntent.Search("alpha"))
        runCurrent()
        assertEquals(listOf("com.alpha"), model.state.value.visibleApps.map { it.packageName })

        model.onIntent(ActionPickerIntent.Search("discarded"))
        model.onIntent(ActionPickerIntent.Search("beta"))
        // Hold the filter by not running its dispatcher; a callback from the old composition can still arrive.
        model.onIntent(ActionPickerIntent.PickApp("com.alpha", "alpha"))
        assertEquals(PickerSelection.Idle, model.state.value.selection)
        assertTrue(model.state.value.filteringApps)
        assertTrue(model.state.value.visibleApps.isEmpty())
        assertTrue(repository.writes.isEmpty())

        runCurrent()
        assertFalse(model.state.value.filteringApps)
        assertEquals(listOf("com.beta"), model.state.value.visibleApps.map { it.packageName })
        model.onIntent(ActionPickerIntent.PickApp("com.beta", "beta"))
        runCurrent()
        assertEquals(listOf(SettingsChange.Target("com.beta")), repository.writes)
    }

    @Test
    fun replacedResultsRejectAnAppThatIsStillInstalled() = runTest {
        catalog.entries = listOf(AppEntry("com.alpha", "Alpha"), AppEntry("com.beta", "Beta"))
        val model = model()
        runCurrent()
        model.onIntent(ActionPickerIntent.Search("beta"))
        runCurrent()

        model.onIntent(ActionPickerIntent.PickApp("com.alpha", "beta"))
        model.onIntent(ActionPickerIntent.PickApp("com.beta", "older query"))
        runCurrent()
        assertEquals(PickerSelection.Idle, model.state.value.selection)
        assertTrue(repository.writes.isEmpty())
    }

    @Test
    fun selectionCompletionCanOnlyBeClaimedOnce() = runTest {
        val model = completedSelection()
        assertTrue(model.claimSelectionCompletion(isActiveEntry = true))
        assertFalse(model.claimSelectionCompletion(isActiveEntry = true))
    }

    @Test
    fun inactiveEntryKeepsSelectionCompletionPending() = runTest {
        val model = completedSelection()
        assertFalse(model.claimSelectionCompletion(isActiveEntry = false))
        assertTrue(model.state.value.selectionComplete)
        assertTrue(model.claimSelectionCompletion(isActiveEntry = true))
    }

    @Test
    fun exitedEntryCannotClaimSelectionCompletion() = runTest {
        val model = completedSelection()
        model.onIntent(ActionPickerIntent.Exit)
        assertFalse(model.claimSelectionCompletion(isActiveEntry = true))
    }

    private fun TestScope.completedSelection(): ActionPickerViewModel {
        val model = model()
        assertFalse(model.claimSelectionCompletion(isActiveEntry = true))
        model.onIntent(ActionPickerIntent.PickPreset(ActionPreset.Camera))
        runCurrent()
        assertTrue(model.state.value.selectionComplete)
        return model
    }

    private fun model() = track(ActionPickerViewModel(repository, catalog, dispatcher))
}

@OptIn(ExperimentalCoroutinesApi::class)
abstract class PickerViewModelTestFixture {
    protected val dispatcher = StandardTestDispatcher()
    internal val repository = PickerSettings()
    internal val catalog = PickerCatalog()
    private val models = mutableListOf<ViewModel>()

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    protected fun <T : ViewModel> track(model: T): T = model.also(models::add)
}

internal class PickerSettings : SettingsRepository {
    override fun editMenu(edit: com.krayapp.duopress.menu.MenuEdit) = error("Unexpected menu edit: $edit")
    val saved = MutableStateFlow(
        SettingsSnapshot(
            "ru.nspk.mirpay", true, "special:torch", true, 40, "single", "auto", emptyList(), "",
        ),
    )
    val writes = mutableListOf<SettingsChange>()

    override fun observe() = saved
    override fun snapshot() = error("Use observable settings")
    override fun update(change: SettingsChange) {
        require(change is SettingsChange.Target)
        writes.add(change)
        saved.value = saved.value.copy(targetId = change.id)
    }
}

internal class PickerCatalog : AppCatalog {
    var entries = emptyList<AppEntry>()
    val scopes = mutableListOf<AppScope>()
    val iconRequests = mutableListOf<String>()

    override suspend fun apps(scope: AppScope): List<AppEntry> {
        scopes.add(scope)
        return entries
    }

    override suspend fun icon(packageName: String): ImageBitmap? {
        iconRequests.add(packageName)
        return null
    }
}
