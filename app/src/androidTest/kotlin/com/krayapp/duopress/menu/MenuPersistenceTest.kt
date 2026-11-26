package com.krayapp.duopress.menu

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.krayapp.duopress.PrefsHelper
import com.krayapp.duopress.data.SharedPrefsSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MenuPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun readsAndNoOpsDoNotRewriteLegacyJson() = withMenu(
        """[ " special:torch ", "", "special:menu", "special:camera", "special:camera" ]""",
    ) { repository, json ->
        val raw = listOf(" special:torch ", "", "special:menu", "special:camera", "special:camera")
        assertEquals(
            raw,
            repository.snapshot().menuItems,
        )
        assertEquals(MenuEditResult.Duplicate, repository.editMenu(MenuEdit.Add("special:torch")))
        assertEquals(MenuEditResult.NoChange, repository.editMenu(MenuEdit.RemoveAt(7, raw)))
        assertEquals(MenuEditResult.NoChange, repository.editMenu(MenuEdit.Move(3, 4, raw)))
        assertEquals(json, PrefsHelper.getMenuItemsJson(context))
    }

    @Test
    fun editsUseRawIndexesAndPersistAnEmptyArray() = withMenu(
        """[""," special:torch ","special:camera"]""",
    ) { repository, _ ->
        assertEquals(
            MenuEditResult.Applied,
            repository.editMenu(MenuEdit.Move(2, 0, listOf("", " special:torch ", "special:camera"))),
        )
        assertEquals("""["special:camera",""," special:torch "]""", PrefsHelper.getMenuItemsJson(context))
        assertEquals(
            MenuEditResult.Applied,
            repository.editMenu(MenuEdit.RemoveAt(1, listOf("special:camera", "", " special:torch "))),
        )
        assertEquals("""["special:camera"," special:torch "]""", PrefsHelper.getMenuItemsJson(context))
        assertEquals(
            MenuEditResult.Applied,
            repository.editMenu(MenuEdit.RemoveAt(1, listOf("special:camera", " special:torch "))),
        )
        assertEquals(MenuEditResult.Applied, repository.editMenu(MenuEdit.RemoveAt(0, listOf("special:camera"))))
        assertEquals("[]", PrefsHelper.getMenuItemsJson(context))
        assertEquals(emptyList<String>(), SharedPrefsSettingsRepository(context).snapshot().menuItems)
    }

    @Test
    fun rejectsStaleRemoveWithoutRewritingTheReplacementJson() =
        withMenu("""["special:torch","special:camera"]""") { repository, _ ->
            val staleEdit = MenuEdit.RemoveAt(0, repository.snapshot().menuItems)
            val currentJson = """[ "special:camera" ]"""
            PrefsHelper.setMenuItemsJson(context, currentJson)

            assertEquals(MenuEditResult.NoChange, repository.editMenu(staleEdit))
            assertEquals(currentJson, PrefsHelper.getMenuItemsJson(context))
        }

    @Test
    fun rejectsStaleMoveEvenWhenSourceAndDestinationRowsAreUnchanged() =
        withMenu("""["special:torch","special:camera"," app:org.example "]""") { repository, _ ->
            val staleEdit = MenuEdit.Move(0, 1, repository.snapshot().menuItems)
            val currentJson = """[ "special:torch", "special:camera", "app:org.example" ]"""
            PrefsHelper.setMenuItemsJson(context, currentJson)

            assertEquals(MenuEditResult.NoChange, repository.editMenu(staleEdit))
            assertEquals(currentJson, PrefsHelper.getMenuItemsJson(context))
        }

    @Test
    fun concurrentEditorsSerializeTheReadCheckAndWriteAcrossRepositoryInstances() = withMenu("[]") { _, _ ->
        val repositories = List(16) { SharedPrefsSettingsRepository(context) }
        val ids = repositories.indices.map { "app:org.example.app$it" }
        val ready = CountDownLatch(ids.size)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(ids.size)
        try {
            val writes = repositories.zip(ids).map { (repository, id) ->
                pool.submit<MenuEditResult> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    repository.editMenu(MenuEdit.Add(id))
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            writes.forEach { assertEquals(MenuEditResult.Applied, it.get(5, TimeUnit.SECONDS)) }
            val repository = SharedPrefsSettingsRepository(context)
            val saved = repository.snapshot().menuItems
            assertEquals(ids.size, saved.size)
            assertEquals(ids.toSet(), saved.toSet())
            assertEquals(MenuEditResult.Full, repository.editMenu(MenuEdit.Add("app:org.example.extra")))
            assertEquals(saved, repository.snapshot().menuItems)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun withMenu(json: String, block: (SharedPrefsSettingsRepository, String) -> Unit) {
        val prefs = context.getSharedPreferences("vivo_swipe_stub_prefs", Context.MODE_PRIVATE)
        val key = "menu_items_json"
        val previous = prefs.getString(key, null)
        try {
            assertTrue(prefs.edit().putString(key, json).commit())
            block(SharedPrefsSettingsRepository(context), json)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }
}
