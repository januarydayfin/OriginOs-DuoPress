package com.krayapp.duopress.menu

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.krayapp.duopress.data.SettingsChange
import com.krayapp.duopress.data.SettingsRepository
import com.krayapp.duopress.data.SettingsSnapshot
import com.krayapp.duopress.platform.AppCatalog
import com.krayapp.duopress.platform.AppEntry
import com.krayapp.duopress.platform.AppScope
import com.krayapp.duopress.platform.PlatformCommand
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
    fun noSeventeenthItemAndRemovingOneReopensAdding() = withEditor { editor, repository ->
        repository.replaceMenu(List(15) { "app:$it" })
        advanceUntilIdle()
        editor.onIntent(MenuEditorIntent.OpenGrid)
        editor.onIntent(MenuEditorIntent.PickSpecial("special:camera"))
        advanceUntilIdle()
        assertFalse(editor.state.value.canAdd)
        editor.onIntent(MenuEditorIntent.OpenGrid)
        assertEquals(MenuPicker.None, editor.state.value.picker)
        editor.onIntent(MenuEditorIntent.RemoveAt(15, editor.state.value.menuItems))
        advanceUntilIdle()
        assertTrue(editor.state.value.canAdd)
    }
    @Test
    fun appBackReturnsToGridBeforeLeavingEditor() = withEditor { editor, _ ->
        editor.onIntent(MenuEditorIntent.OpenGrid)
        editor.onIntent(MenuEditorIntent.OpenApps)
        assertEquals(MenuPicker.Apps, editor.state.value.picker)
        editor.onIntent(MenuEditorIntent.BackFromPicker)
        assertEquals(MenuPicker.Grid, editor.state.value.picker)
        editor.onIntent(MenuEditorIntent.BackFromPicker)
        assertEquals(MenuPicker.None, editor.state.value.picker)
    }
    @Test
    fun editorExpandsSearchableAppPicker() = withEditor { editor, repository ->
        val maps = AppEntry("org.maps", "Карты")
        assertEquals(MenuPicker.None, editor.state.value.picker)
        editor.onIntent(MenuEditorIntent.OpenGrid)
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
        editor.onIntent(MenuEditorIntent.RemoveAt(1, editor.state.value.menuItems))
        advanceUntilIdle()
        assertEquals(listOf("", "special:camera"), editor.state.value.menuItems)

        editor.onIntent(MenuEditorIntent.OpenGrid)
        editor.onIntent(MenuEditorIntent.PickSpecial("special:menu"))
        advanceUntilIdle()
        assertEquals(listOf("", "special:camera"), repository.snapshot().menuItems)
        editor.onIntent(MenuEditorIntent.PickSpecial("special:camera"))
        advanceUntilIdle()
        assertEquals(MenuEditResult.Duplicate, editor.state.value.editResult)
        assertEquals(MenuPicker.Grid, editor.state.value.picker)

        editor.onIntent(MenuEditorIntent.PickSpecial("special:vpn"))
        advanceUntilIdle()
        assertEquals(listOf("", "special:camera", "special:vpn"), repository.snapshot().menuItems)
        assertEquals(MenuPicker.None, editor.state.value.picker)
    }

    @Test
    fun staleRemoveDoesNotDeleteTheRowNowAtThatIndex() = withEditor { editor, repository ->
        val staleTap = MenuEditorIntent.RemoveAt(1, editor.state.value.menuItems)
        val current = listOf("", "special:camera")
        repository.replaceMenu(current)
        advanceUntilIdle()
        assertEquals(current, editor.state.value.menuItems)

        editor.onIntent(staleTap)
        advanceUntilIdle()

        assertEquals(current, repository.snapshot().menuItems)
        assertEquals(MenuEditResult.NoChange, editor.state.value.editResult)
    }

    @Test
    fun staleMoveDoesNotReorderAnEditNotYetObserved() = withEditor { editor, repository ->
        val shown = editor.state.value.menuItems
        val staleTap = MenuEditorIntent.Move(1, 2, shown)
        val persisted = listOf("", "special:torch", "special:camera", "special:vpn")
        repository.replaceMenu(persisted, publish = false)

        editor.onIntent(staleTap)
        advanceUntilIdle()

        assertEquals(persisted, repository.snapshot().menuItems)
        assertEquals(shown, editor.state.value.menuItems)
        assertEquals(MenuEditResult.NoChange, editor.state.value.editResult)
    }

    @Test
    fun committedEditBecomesVisibleOnlyThroughTheObservedMenu() = withEditor { editor, repository ->
        val shown = editor.state.value.menuItems
        val edited = listOf("", "special:camera")
        repository.publishWrites = false

        editor.onIntent(MenuEditorIntent.RemoveAt(1, shown))
        advanceUntilIdle()
        assertEquals(edited, repository.snapshot().menuItems)
        assertEquals(shown, editor.state.value.menuItems)

        repository.replaceMenu(edited)
        advanceUntilIdle()
        assertEquals(edited, editor.state.value.menuItems)
    }

    @Test
    fun obsoleteAppPickCannotSaveWhileNewFilterIsPending() = withEditor { editor, repository ->
        editor.onIntent(MenuEditorIntent.OpenGrid)
        editor.onIntent(MenuEditorIntent.OpenApps)
        advanceUntilIdle()
        assertTrue(editor.state.value.visibleApps.any { it.packageName == "org.maps" })
        val before = repository.snapshot().menuItems

        // Do not advance the dispatcher: a callback from the last rendered rows arrives before filtering.
        editor.onIntent(MenuEditorIntent.SearchApps("wallet"))
        val whileFiltering = editor.state.value.visibleApps
        editor.onIntent(MenuEditorIntent.PickApp("org.maps"))
        advanceUntilIdle()

        assertEquals(before, repository.snapshot().menuItems)
        assertEquals(emptyList<AppEntry>(), whileFiltering)
        assertEquals(listOf(AppEntry("org.wallet", "Wallet")), editor.state.value.visibleApps)
        editor.onIntent(MenuEditorIntent.PickApp("org.wallet"))
        advanceUntilIdle()
        assertEquals(before + "app:org.wallet", repository.snapshot().menuItems)
    }

    @Test
    fun searchCannotRepopulateAnAppPickerClosedBySaving() = withEditor { editor, _ ->
        editor.onIntent(MenuEditorIntent.OpenGrid)
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
        private var saved = SettingsSnapshot(
            "", false, "", false, 40, "single", "auto",
            listOf("", "special:torch", "special:camera"), "",
        )
        private val values = MutableStateFlow(saved)
        var publishWrites = true

        fun replaceMenu(items: List<String>, publish: Boolean = true) {
            saved = saved.copy(menuItems = items)
            if (publish) values.value = saved
        }

        override fun observe() = values
        override fun snapshot() = saved
        override fun update(change: SettingsChange) = error("Expected a menu edit")
        override fun editMenu(edit: MenuEdit): MenuEditResult {
            val (result, items) = applyMenuEdit(saved.menuItems, edit, { it }, { it })
            if (result == MenuEditResult.Applied) replaceMenu(items, publishWrites)
            return result
        }
    }
}
