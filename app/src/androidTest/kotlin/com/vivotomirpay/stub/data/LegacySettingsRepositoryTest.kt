package com.vivotomirpay.stub.data

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivotomirpay.stub.PrefsHelper
import com.vivotomirpay.stub.actions.LegacyActionLabelResolver
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LegacySettingsRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun repositoryObservesLegacyWrite() = runBlocking {
        withStringPreferenceRestored("lockscreen_action") {
            PrefsHelper.setLockscreenAction(context, "special:torch")
            val repository = LegacySettingsRepository(context)

            val observed = withTimeout(5_000) {
                repository.observe()
                    .onEach {
                        if (it.lockscreenActionId == "special:torch") {
                            PrefsHelper.setLockscreenAction(context, "special:camera")
                        }
                    }
                    .take(2)
                    .toList()
            }
            assertEquals(
                listOf("special:torch", "special:camera"),
                observed.map(SettingsSnapshot::lockscreenActionId),
            )
        }
    }

    @Test
    fun repositoryWritesThroughLegacyHelper() = runBlocking {
        withStringPreferenceRestored("lockscreen_action") {
            val repository = LegacySettingsRepository(context)
            repository.update(SettingsChange.LockscreenAction("special:camera"))
            assertEquals("special:camera", PrefsHelper.getLockscreenAction(context))
            assertEquals("special:camera", repository.snapshot().lockscreenActionId)
        }
    }

    @Test
    fun snapshotPreservesLegacyMenuOrderDuplicatesAndUnknownIds() = runBlocking {
        withStringPreferenceRestored("menu_items_json") {
            val rawJson = """["special:camera","unknown:id","special:camera"," app:org.example "]"""
            PrefsHelper.setMenuItemsJson(context, rawJson)
            assertEquals(
                listOf("special:camera", "unknown:id", "special:camera", " app:org.example "),
                LegacySettingsRepository(context).snapshot().menuItems,
            )
            assertEquals(rawJson, PrefsHelper.getMenuItemsJson(context))
        }
    }

    @Test
    fun resolvesInstalledPackageAndFallsBackToUnmodifiedMissingId() {
        val resolver = LegacyActionLabelResolver(context)
        assertEquals("Double Power Key", resolver.resolve(context.packageName, "auto"))
        val missing = "app:org.example.nonexistent"
        assertEquals(missing, resolver.resolve(missing, "auto"))
    }

    @Test
    fun resolvedSpecialActionLabelSupportsEnglishAndRussian() {
        fun resolver(language: String): LegacyActionLabelResolver {
            val config = Configuration(context.resources.configuration)
            config.setLocale(Locale.forLanguageTag(language))
            return LegacyActionLabelResolver(context.createConfigurationContext(config))
        }

        assertEquals("Flashlight", resolver("en").resolve("special:torch", "auto"))
        assertEquals("Фонарик", resolver("ru").resolve("special:torch", "auto"))
    }

    private suspend fun withStringPreferenceRestored(key: String, block: suspend () -> Unit) {
        val preferences = context.getSharedPreferences("vivo_swipe_stub_prefs", Context.MODE_PRIVATE)
        val previous = preferences.getString(key, null)
        try {
            block()
        } finally {
            val editor = preferences.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }
}
