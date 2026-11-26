package com.krayapp.duopress.data

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.krayapp.duopress.ActionRunner
import com.krayapp.duopress.PrefsHelper
import com.krayapp.duopress.actions.LegacyActionLabelResolver
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SettingsCompatibilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun repositoryObservesLegacyWrite() = runBlocking {
        withLegacyPreferences("lockscreen_action") {
            assertTrue(edit().putString("lockscreen_action", "special:torch").commit())
            val repository = SharedPrefsSettingsRepository(context)

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
        withLegacyPreferences("lockscreen_action") {
            val repository = SharedPrefsSettingsRepository(context)
            repository.update(SettingsChange.LockscreenAction("special:camera"))
            assertEquals("special:camera", getString("lockscreen_action", null))
            assertEquals("special:camera", PrefsHelper.getLockscreenAction(context))
            assertEquals("special:camera", repository.snapshot().lockscreenActionId)
        }
    }

    @Test
    fun snapshotPreservesLegacyMenuOrderDuplicatesAndUnknownIds() = runBlocking {
        withLegacyPreferences("menu_items_json") {
            val rawJson = """[ "special:camera", "unknown:id", "special:camera", " app:org.example " ]"""
            assertTrue(edit().putString("menu_items_json", rawJson).commit())
            assertEquals(
                listOf("special:camera", "unknown:id", "special:camera", " app:org.example "),
                SharedPrefsSettingsRepository(context).snapshot().menuItems,
            )
            assertEquals(rawJson, getString("menu_items_json", null))
        }
    }

    @Test
    fun legacyPayloadReadsWithoutReset() = runBlocking {
        val fixture = mapOf(
            "target_package" to "shortcut:com.example.app:a%2Bb:",
            "menu_items_json" to "[]",
            "vibrate_duration_ms" to 40,
            "vpn_client" to "incy",
            "torch_on" to true,
            "double_press_package" to " legacy.gemini ",
            "last_trigger_log" to "Time: legacy\nStatus: kept",
            "lockscreen_enabled" to false,
            "lockscreen_action" to "special:camera",
            "vibrate_on_trigger" to false,
            "vibrate_pattern" to "double",
        )
        withLegacyPreferences(*fixture.keys.toTypedArray()) {
            assertTrue(edit().apply { fixture.forEach { (key, value) -> putPreference(key, value) } }.commit())
            val snapshot: SettingsSnapshot = SharedPrefsSettingsRepository(context).snapshot()
            assertEquals("shortcut:com.example.app:a%2Bb:", snapshot.targetId)
            assertEquals(emptyList<String>(), snapshot.menuItems)
            assertEquals(40, snapshot.vibrateDurationMs)
            assertEquals("incy", snapshot.vpnClient)
            assertTrue(PrefsHelper.isTorchOn(context))
            assertEquals(" legacy.gemini ", PrefsHelper.getDoublePressPackage(context))
            assertEquals("Time: legacy\nStatus: kept", snapshot.triggerLog)
            assertFalse(snapshot.lockscreenEnabled)
            assertEquals("special:camera", snapshot.lockscreenActionId)
            assertFalse(snapshot.vibrateEnabled)
            assertEquals("double", snapshot.vibratePattern)
            assertEquals(fixture, all.filterKeys { it in fixture })
        }
    }

    @Test
    fun missingOrBadMenuGetsSupportedDefaultsWithoutWriting() = runBlocking {
        val expected = listOf("special:torch", "special:camera", "special:vpn")
        withLegacyPreferences("menu_items_json") {
            val repository = SharedPrefsSettingsRepository(context)
            for (raw in listOf(null, " \t", """{"not":"an array"}""")) {
                assertTrue(edit().putString("menu_items_json", raw).commit())
                assertEquals(expected, repository.snapshot().menuItems)
                assertEquals(raw, getString("menu_items_json", null))
                assertEquals(raw != null, contains("menu_items_json"))
            }
        }
    }

    @Test
    fun legacyCircleShowsUnavailableWithoutMutatingSettings() = runBlocking {
        val id = "special:circle_to_search"
        val rawMenu = """[ "special:torch", "$id", "shortcut:pkg:test:Test" ]"""
        val noLaunch = context.createConfigurationContext(Configuration().apply {
            setLocale(Locale.ENGLISH)
        }).let { english ->
            object : ContextWrapper(english) {
                override fun startActivity(intent: Intent) = error("A retired action must not launch $intent")
            }
        }
        withLegacyPreferences("target_package", "menu_items_json", "last_trigger_log") {
            assertTrue(edit().putString("target_package", id).putString("menu_items_json", rawMenu).commit())
            val snapshot = SharedPrefsSettingsRepository(context).snapshot()
            assertEquals(id, snapshot.targetId)
            assertEquals(listOf("special:torch", id, "shortcut:pkg:test:Test"), snapshot.menuItems)
            val status = "Circle to Search — unavailable"
            assertEquals(status, LegacyActionLabelResolver(noLaunch).resolve(id, "auto"))
            assertEquals(status, ActionRunner.runAction(noLaunch, id, null))
            assertTrue(PrefsHelper.getLastTriggerLog(context).orEmpty().contains("Status: $status"))
            assertEquals(id, getString("target_package", null))
            assertEquals(rawMenu, getString("menu_items_json", null))
        }
    }

    @Test
    fun targetFallbackAndVibrationBoundsDoNotRewriteRawValues() = runBlocking {
        withLegacyPreferences("target_package", "lockscreen_action", "vibrate_duration_ms", "vibrate_pattern", "vpn_client") {
            assertTrue(
                edit()
                    .putString("target_package", " \u001f ")
                    .putString("lockscreen_action", "\t ")
                    .putInt("vibrate_duration_ms", -1)
                    .putString("vibrate_pattern", " double ")
                    .putString("vpn_client", "other")
                    .commit(),
            )
            val repository = SharedPrefsSettingsRepository(context)
            val raw = all.toMap()
            val snapshot = repository.snapshot()
            assertEquals("ru.nspk.mirpay", snapshot.targetId)
            assertEquals("special:torch", snapshot.lockscreenActionId)
            assertEquals(20, snapshot.vibrateDurationMs)
            assertEquals("single", snapshot.vibratePattern)
            assertEquals("auto", snapshot.vpnClient)
            assertEquals(raw, all)

            PrefsHelper.setTargetPackage(context, "\t \u00a0 ")
            repository.update(SettingsChange.VibrationDuration(501))
            assertEquals("\u00a0", getString("target_package", null))
            assertEquals(501, getInt("vibrate_duration_ms", 0))
            assertEquals("\u00a0", repository.snapshot().targetId)
            assertEquals(500, repository.snapshot().vibrateDurationMs)

            PrefsHelper.setTargetPackage(context, null)
            assertEquals("ru.nspk.mirpay", getString("target_package", null))
        }
    }

    @Test
    fun longLogRetainsOnlyBoundedText() = runBlocking {
        withLegacyPreferences("last_trigger_log") {
            val repository = SharedPrefsSettingsRepository(context)
            val intent = Intent("legacy.action").putExtra("long", "x".repeat(200))
            PrefsHelper.saveTriggerLog(context, intent, null, "ready")
            val shortLog = repository.snapshot().triggerLog
            assertTrue(shortLog.contains("Extras: long=${"x".repeat(97)}...; \n"))
            assertTrue(shortLog.endsWith("Target: \nStatus: ready"))
            assertEquals(shortLog, getString("last_trigger_log", null))

            repeat(15) { intent.putExtra("extra$it", "y".repeat(200)) }
            PrefsHelper.saveTriggerLog(context, intent, "app:legacy", "ready")
            val cappedLog = repository.snapshot().triggerLog
            assertEquals(1000, cappedLog.length)
            assertTrue(cappedLog.endsWith("..."))
            assertEquals(cappedLog, getString("last_trigger_log", null))

            repository.update(SettingsChange.ClearLog)
            assertEquals("", PrefsHelper.getLastTriggerLog(context))
            assertFalse(contains("last_trigger_log"))
        }
    }

    @Test
    fun resolvesInstalledPackageAndFallsBackToUnmodifiedMissingId() {
        val resolver = LegacyActionLabelResolver(context)
        assertEquals("DuoPress", resolver.resolve(context.packageName, "auto"))
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

    private suspend fun withLegacyPreferences(
        vararg keys: String,
        block: suspend SharedPreferences.() -> Unit,
    ) {
        val preferences = context.getSharedPreferences("vivo_swipe_stub_prefs", Context.MODE_PRIVATE)
        val previous = preferences.all.filterKeys { it in keys }
        try {
            preferences.block()
        } finally {
            val editor = preferences.edit()
            keys.forEach { key -> editor.putPreference(key, previous[key]) }
            assertTrue(editor.commit())
            assertEquals(previous, preferences.all.filterKeys { it in keys })
        }
    }

    private fun SharedPreferences.Editor.putPreference(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
            else -> error("Unsupported preference type: $value")
        }
    }
}
