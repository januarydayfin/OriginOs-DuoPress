package com.vivotomirpay.stub.picker

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vivotomirpay.stub.data.SettingsChange
import com.vivotomirpay.stub.data.SettingsRepository
import com.vivotomirpay.stub.data.SettingsSnapshot
import com.vivotomirpay.stub.platform.AppCatalog
import com.vivotomirpay.stub.platform.AppEntry
import com.vivotomirpay.stub.platform.AppScope
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
        model.onIntent(ActionPickerIntent.PickApp(entry.packageName))
        runCurrent()

        assertEquals("com.example.app", repository.saved.value.targetId)
        assertEquals(listOf(AppScope.Installed), catalog.scopes)
        assertEquals(listOf("com.example.app"), catalog.iconRequests)
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
    override fun editMenu(edit: com.vivotomirpay.stub.menu.MenuEdit) = error("Unexpected menu edit: $edit")
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
