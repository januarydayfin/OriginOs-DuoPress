package com.vivotomirpay.stub.menu

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivotomirpay.stub.PrefsHelper
import com.vivotomirpay.stub.data.LegacySettingsRepository
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
        assertEquals(
            listOf(" special:torch ", "", "special:menu", "special:camera", "special:camera"),
            repository.snapshot().menuItems,
        )
        assertEquals(MenuEditResult.Duplicate, repository.editMenu(MenuEdit.Add("special:torch")))
        assertEquals(MenuEditResult.NoChange, repository.editMenu(MenuEdit.RemoveAt(7)))
        assertEquals(MenuEditResult.NoChange, repository.editMenu(MenuEdit.Move(3, 4)))
        assertEquals(json, PrefsHelper.getMenuItemsJson(context))
    }

    @Test
    fun editsUseRawIndexesAndPersistAnEmptyArray() = withMenu(
        """[""," special:torch ","special:camera"]""",
    ) { repository, _ ->
        assertEquals(MenuEditResult.Applied, repository.editMenu(MenuEdit.Move(2, 0)))
        assertEquals("""["special:camera",""," special:torch "]""", PrefsHelper.getMenuItemsJson(context))
        assertEquals(MenuEditResult.Applied, repository.editMenu(MenuEdit.RemoveAt(1)))
        assertEquals("""["special:camera"," special:torch "]""", PrefsHelper.getMenuItemsJson(context))
        assertEquals(MenuEditResult.Applied, repository.editMenu(MenuEdit.RemoveAt(1)))
        assertEquals(MenuEditResult.Applied, repository.editMenu(MenuEdit.RemoveAt(0)))
        assertEquals("[]", PrefsHelper.getMenuItemsJson(context))
        assertEquals(emptyList<String>(), LegacySettingsRepository(context).snapshot().menuItems)
    }

    @Test
    fun concurrentEditorsSerializeTheReadCheckAndWriteAcrossRepositoryInstances() = withMenu("[]") { _, _ ->
        val repositories = List(16) { LegacySettingsRepository(context) }
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
            val repository = LegacySettingsRepository(context)
            val saved = repository.snapshot().menuItems
            assertEquals(ids.size, saved.size)
            assertEquals(ids.toSet(), saved.toSet())
            assertEquals(MenuEditResult.Full, repository.editMenu(MenuEdit.Add("app:org.example.extra")))
            assertEquals(saved, repository.snapshot().menuItems)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun withMenu(json: String, block: (LegacySettingsRepository, String) -> Unit) {
        val prefs = context.getSharedPreferences("vivo_swipe_stub_prefs", Context.MODE_PRIVATE)
        val key = "menu_items_json"
        val previous = prefs.getString(key, null)
        try {
            assertTrue(prefs.edit().putString(key, json).commit())
            block(LegacySettingsRepository(context), json)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }
}
