package com.vivotomirpay.stub.menu

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.vivotomirpay.stub.data.SettingsChange
import com.vivotomirpay.stub.data.SettingsRepository
import com.vivotomirpay.stub.data.SettingsSnapshot
import com.vivotomirpay.stub.platform.AppCatalog
import com.vivotomirpay.stub.platform.AppEntry
import com.vivotomirpay.stub.platform.AppScope
import com.vivotomirpay.stub.platform.PlatformCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MenuEditorViewModelTest {
    @Test
    fun editorExpandsSearchableAppPicker() = withEditor { editor, repository ->
        val maps = AppEntry("org.maps", "Карты")
        assertEquals(MenuPicker.None, editor.state.value.picker)
        editor.onIntent(MenuEditorIntent.OpenApps)
        advanceUntilIdle()
        assertEquals(MenuPicker.Apps, editor.state.value.picker)
        assertEquals(listOf(maps, AppEntry("org.wallet", "Wallet")), editor.state.value.visibleApps)

        editor.onIntent(MenuEditorIntent.SearchApps("  ORG.MAPS "))
        advanceUntilIdle()
        assertEquals(listOf(maps), editor.state.value.visibleApps)
        editor.onIntent(MenuEditorIntent.SearchApps("карТ"))
        advanceUntilIdle()
        assertEquals(listOf(maps), editor.state.value.visibleApps)

        editor.onIntent(MenuEditorIntent.PickApp("org.maps"))
        advanceUntilIdle()
        assertEquals(listOf("", "special:torch", "special:camera", "app:org.maps"), repository.snapshot().menuItems)
        assertEquals(MenuPicker.None, editor.state.value.picker)
    }

    @Test
    fun editorEditsRawIndexesAndRejectsUnsupportedSpecials() = withEditor { editor, repository ->
        editor.onIntent(MenuEditorIntent.RemoveAt(1))
        advanceUntilIdle()
        assertEquals(listOf("", "special:camera"), editor.state.value.menuItems)

        editor.onIntent(MenuEditorIntent.OpenSpecials)
        editor.onIntent(MenuEditorIntent.PickSpecial("special:menu"))
        advanceUntilIdle()
        assertEquals(listOf("", "special:camera"), repository.snapshot().menuItems)
        editor.onIntent(MenuEditorIntent.PickSpecial("special:camera"))
        advanceUntilIdle()
        assertEquals(MenuEditResult.Duplicate, editor.state.value.editResult)
        assertEquals(MenuPicker.Specials, editor.state.value.picker)

        editor.onIntent(MenuEditorIntent.PickSpecial("special:vpn"))
        advanceUntilIdle()
        assertEquals(listOf("", "special:camera", "special:vpn"), repository.snapshot().menuItems)
        assertEquals(MenuPicker.None, editor.state.value.picker)
    }

    @Test
    fun searchCannotRepopulateAnAppPickerClosedBySaving() = withEditor { editor, _ ->
        editor.onIntent(MenuEditorIntent.OpenApps)
        advanceUntilIdle()
        editor.onIntent(MenuEditorIntent.PickApp("org.maps"))
        editor.onIntent(MenuEditorIntent.SearchApps("wallet"))
        advanceUntilIdle()

        assertEquals(MenuPicker.None, editor.state.value.picker)
        assertTrue(editor.state.value.visibleApps.isEmpty())
    }

    @Test
    fun previewCommandMustBeAcknowledgedBeforeRunningAndCompletedBeforeRepeating() = withEditor { editor, _ ->
        editor.onIntent(MenuEditorIntent.Preview)
        val request = requireNotNull(editor.state.value.platformRequest)
        assertEquals(PlatformCommand.PreviewMenu, request.command)
        editor.onIntent(MenuEditorIntent.Preview)
        assertEquals(request, editor.state.value.platformRequest)
        assertFalse(editor.acknowledgePlatformRequest(request.id + 1))
        assertTrue(editor.acknowledgePlatformRequest(request.id))
        assertFalse(editor.acknowledgePlatformRequest(request.id))
        editor.onPlatformResult(request.id + 1, launched = false)
        editor.onIntent(MenuEditorIntent.Preview)
        assertNull(editor.state.value.platformRequest)

        editor.onPlatformResult(request.id, launched = false)
        assertTrue(editor.state.value.platformLaunchFailed)
        editor.onIntent(MenuEditorIntent.Preview)
        assertNotEquals(request.id, editor.state.value.platformRequest?.id)
        assertFalse(editor.state.value.platformLaunchFailed)
    }

    private fun withEditor(
        test: TestScope.(MenuEditorViewModel, MemorySettings) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = MemorySettings()
        val store = ViewModelStore()
        try {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MenuEditorViewModel(repository, Catalog, dispatcher) as T
            }
            val editor = ViewModelProvider(store, factory)[MenuEditorViewModel::class.java]
            advanceUntilIdle()
            test(editor, repository)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private object Catalog : AppCatalog {
        override suspend fun apps(scope: AppScope) =
            listOf(AppEntry("org.maps", "Карты"), AppEntry("org.wallet", "Wallet"))

        override suspend fun icon(packageName: String): ImageBitmap? = null
    }

    private class MemorySettings : SettingsRepository {
        private val values = MutableStateFlow(
            SettingsSnapshot("", false, "", false, 40, "single", "auto",
                listOf("", "special:torch", "special:camera"), ""),
        )
        override fun observe() = values
        override fun snapshot() = values.value
        override fun update(change: SettingsChange) = error("Expected a menu edit")
        override fun editMenu(edit: MenuEdit): MenuEditResult {
            val (result, items) = applyMenuEdit(values.value.menuItems, edit, { it }, { it })
            if (result == MenuEditResult.Applied) values.value = values.value.copy(menuItems = items)
            return result
        }
    }
}
