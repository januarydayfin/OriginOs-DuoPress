package com.vivotomirpay.stub.platform

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivotomirpay.stub.ActionRunner
import com.vivotomirpay.stub.PrefsHelper
import com.vivotomirpay.stub.R
import com.vivotomirpay.stub.SwipeEntryActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwipeEntryContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val packageManager get() = app.packageManager
    private val keyguard get() = app.getSystemService(KeyguardManager::class.java)
    private val preferences get() = app.getSharedPreferences("vivo_swipe_stub_prefs", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    @Test
    fun manifestResolvesExactVivoComponent() {
        assertEquals("com.vivotomirpay.stub.debug", app.packageName)
        assertEquals(VIVO_ACTION, SwipeEntryActivity.ACTION_VIVO_SWIPE)
        val component = ComponentName(app.packageName, "com.vivotomirpay.stub.SwipeEntryActivity")
        val query = swipeIntent("2").setPackage(app.packageName)
        val resolved = packageManager.queryIntentActivities(
            query,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER,
        ).single()
        assertEquals(component.packageName, resolved.activityInfo.packageName)
        assertEquals(component.className, resolved.activityInfo.name)
        assertEquals(component, query.resolveActivity(packageManager))
        assertEquals(listOf(VIVO_ACTION), resolved.filter.actionsIterator().asSequence().toList())
        assertEquals(listOf(Intent.CATEGORY_DEFAULT), resolved.filter.categoriesIterator().asSequence().toList())
        assertNull(query.addCategory("contract.NOT_VIVO").resolveActivity(packageManager))

        val entry = packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
        assertTrue(entry.exported)
        assertNull(entry.permission)
        assertEquals("com.vivotomirpay.stub.swipe", entry.taskAffinity)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, entry.launchMode)
        assertTrue(entry.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(entry.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
        val configChanges = ActivityInfo.CONFIG_KEYBOARD_HIDDEN or ActivityInfo.CONFIG_ORIENTATION or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or ActivityInfo.CONFIG_UI_MODE or ActivityInfo.CONFIG_DENSITY
        // PackageManager can add MCC/MNC defaults; the packaged manifest is compared separately.
        val runtimeDefaults = ActivityInfo.CONFIG_MCC or ActivityInfo.CONFIG_MNC
        assertEquals(configChanges, entry.configChanges and runtimeDefaults.inv())
        assertEquals(R.string.swipe_activity_label, entry.labelRes)
        assertEquals(R.style.TransparentSwipeTheme, entry.theme)

        val application = packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
        assertVivoMetadata(application.metaData)
        assertVivoMetadata(entry.metaData)
        with(ContextThemeWrapper(app, entry.theme).obtainStyledAttributes(intArrayOf(
            android.R.attr.windowIsTranslucent,
            android.R.attr.windowBackground,
            android.R.attr.windowNoTitle,
            android.R.attr.windowIsFloating,
            android.R.attr.statusBarColor,
            android.R.attr.navigationBarColor,
        ))) {
            try {
                assertTrue(getBoolean(0, false))
                assertEquals(Color.TRANSPARENT, getColor(1, Color.RED))
                assertTrue(getBoolean(2, false))
                assertFalse(getBoolean(3, true))
                assertEquals(Color.TRANSPARENT, getColor(4, Color.RED))
                assertEquals(Color.TRANSPARENT, getColor(5, Color.RED))
            } finally {
                recycle()
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun requestsOnlyBaselinePermissionsAndOwnAndroidXSignaturePermission() {
        val pkg = packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val own = "com.vivotomirpay.stub.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        assertEquals(listOf(
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FLASHLIGHT",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.VIBRATE",
            own,
            "moe.shizuku.manager.permission.API_V23",
        ).sorted(), pkg.requestedPermissions.orEmpty().sorted())
        val declared = pkg.permissions.orEmpty().single()
        assertEquals(own, declared.name)
        assertEquals(android.content.pm.PermissionInfo.PROTECTION_SIGNATURE, declared.protectionLevel)
    }

    @Test
    fun normalizationPreservesLegacyActionDispatch() = withRestoredPreferences("last_trigger_log") {
        val noLaunch = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent): Unit =
                throw AssertionError("Unexpected external launch: $intent")

            override fun getPackageManager(): PackageManager =
                throw AssertionError("These inputs must resolve before package lookup")
        }
        val nbsp = "\u00a0"
        assertNull(ActionRunner.normalize(null))
        assertNull(ActionRunner.normalize("\u0000\u001f \t\n\r"))
        assertEquals("${nbsp}app:example${nbsp}", ActionRunner.normalize(" ${nbsp}app:example${nbsp}\t"))
        val source = swipeIntent("2").putExtra("marker", "characterization")
        val cases = listOf(
            Triple(null, "", app.getString(R.string.log_reason_not_set)),
            Triple(" \n\t\u0000", "", app.getString(R.string.log_reason_not_set)),
            Triple(" \tapp:\n", "app:", app.getString(R.string.log_app_not_found, "app:")),
            Triple(" \u001fapp: ${app.packageName}\t", app.packageName, app.getString(R.string.log_reason_self)),
            Triple(" \tspecial:invalid\r", "special:invalid", app.getString(R.string.log_unknown_special, "special:invalid")),
            Triple(" \tshortcut:\n", "shortcut:", app.getString(R.string.log_shortcut_invalid, "shortcut:")),
        )
        for ((input, target, status) in cases) {
            assertEquals("input=$input", status, ActionRunner.runAction(noLaunch, input, source))
            assertTriggerLog(PrefsHelper.getLastTriggerLog(app).orEmpty(), "2", target, status)
        }
        ActionRunner.runAction(noLaunch, null, null)
        val noSource = PrefsHelper.getLastTriggerLog(app).orEmpty()
        assertFalse(noSource.contains("Action:"))
        assertFalse(noSource.contains("Extras:"))
    }

    @Test
    fun onCreateAndOnNewIntentUseIncomingIntentAndCurrentPrimary() = withSwipePreferences {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        assumeFalse("Run unlocked lifecycle on an unlocked Android test device", keyguard.isKeyguardLocked)
        assertTrue(preferences.edit()
            .putString("target_package", "special:contract-first")
            .putString("lockscreen_enabled", "not a Boolean; must not be read while unlocked")
            .putInt("lockscreen_action", 1)
            .putBoolean("vibrate_on_trigger", false)
            .commit())
        observeSwipeCreation { entry ->
            assertTrue(entry.isFinishing)
            assertTriggerLog(
                PrefsHelper.getLastTriggerLog(app).orEmpty(), "2", "special:contract-first",
                app.getString(R.string.log_unknown_special, "special:contract-first"),
            )

            assertTrue(preferences.edit().putString("target_package", "special:contract-second").commit())
            val next = swipeIntent("contract-new-intent")
            // Direct framework delivery keeps the just-finished Activity alive inside this main loop turn.
            instrumentation.callActivityOnNewIntent(entry, next)
            assertEquals("contract-new-intent", entry.intent.getStringExtra("lb_from"))
            assertTriggerLog(
                PrefsHelper.getLastTriggerLog(app).orEmpty(), "contract-new-intent", "special:contract-second",
                app.getString(R.string.log_unknown_special, "special:contract-second"),
            )
        }
    }

    @Test
    fun lockedEntryUsesCameraWithoutReadingPrimaryOrLaunchingAnotherApp() = withSwipePreferences {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        assumeTrue("Run locked lifecycle with the Android test device already locked", keyguard.isKeyguardLocked)
        assertTrue(preferences.edit()
            .putInt("target_package", 1)
            .putBoolean("lockscreen_enabled", true)
            .putString("lockscreen_action", "special:camera")
            .putBoolean("vibrate_on_trigger", false)
            .commit())
        val result = Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        val secure = instrumentation.addMonitor(IntentFilter(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE), result, true)
        val regular = instrumentation.addMonitor(IntentFilter(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), result, true)
        try {
            observeSwipeCreation { entry ->
                assertFalse("Camera path must not finish synchronously", entry.isFinishing)
                val status = when {
                    secure.hits == 1 -> app.getString(R.string.log_camera_ok_secure)
                    regular.hits == 1 -> app.getString(R.string.log_camera_ok)
                    else -> app.getString(R.string.log_camera_not_found)
                }
                assertTrue(secure.hits + regular.hits <= 1)
                assertTriggerLog(PrefsHelper.getLastTriggerLog(app).orEmpty(), "2", "special:camera", status)
            }
        } finally {
            instrumentation.removeMonitor(regular)
            instrumentation.removeMonitor(secure)
        }
    }

    private fun assertVivoMetadata(data: Bundle) {
        assertTrue(data.getBoolean("com.vivo.wallet.support.typeswing"))
        assertTrue(data.getBoolean("com.vivo.wallet.nfc.SupportDoubleClick"))
        assertTrue(data.getBoolean("com.vivo.wallet.nfc.CommonSupportDoubleClick"))
        assertEquals(2, data.getInt("com.vivo.wallet.nfc.supportVersion"))
        assertEquals(1, data.getInt("com.vivo.pay.nfc.supportVersion"))
    }

    private fun assertTriggerLog(log: String, extra: String, target: String, status: String) {
        assertTrue(log, log.contains("\nAction: $VIVO_ACTION\n"))
        assertTrue(log, log.contains("lb_from=$extra;"))
        assertTrue(log, log.endsWith("\nTarget: $target\nStatus: $status"))
    }

    private fun observeSwipeCreation(check: (SwipeEntryActivity) -> Unit) {
        val application = app.applicationContext as Application
        val completed = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        var observed: Result<Unit>? = null
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                observed = runCatching {
                    assertEquals(SwipeEntryActivity::class.java, activity.javaClass)
                    check(activity as SwipeEntryActivity)
                }
                completed.countDown()
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (activity is SwipeEntryActivity) destroyed.countDown()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            app.startActivity(swipeIntent("2").setPackage(app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("Swipe entry was not created", completed.await(5, TimeUnit.SECONDS))
            observed!!.getOrThrow()
            assertTrue("Swipe entry did not finish", destroyed.await(5, TimeUnit.SECONDS))
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun withSwipePreferences(test: () -> Unit) = withRestoredPreferences(
        "target_package", "lockscreen_enabled", "lockscreen_action", "vibrate_on_trigger", "last_trigger_log",
        test = test,
    )

    private fun withRestoredPreferences(vararg keys: String, test: () -> Unit) {
        val before = preferences.all
        try {
            test()
        } finally {
            val restore = preferences.edit()
            for (key in keys) {
                when (val value = before[key]) {
                    null -> restore.remove(key)
                    is String -> restore.putString(key, value)
                    is Boolean -> restore.putBoolean(key, value)
                    is Int -> restore.putInt(key, value)
                    else -> error("Unexpected saved type for $key")
                }
            }
            assertTrue(restore.commit())
        }
    }

    private fun swipeIntent(marker: String) = Intent(VIVO_ACTION)
        .addCategory(Intent.CATEGORY_DEFAULT)
        .putExtra("lb_from", marker)

    private companion object {
        const val VIVO_ACTION = "vivo.intent.action.NFC_BUS_SWINGSPLASH"
    }
}
