package com.krayapp.duopress.platform

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.krayapp.duopress.R
import com.krayapp.duopress.ShizukuShortcuts
import com.krayapp.duopress.VpnController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformAdaptersTest {
    @Test
    fun shortcutIconIsBoundedOrFallsBack() {
        val wideIcon = object : ColorDrawable(Color.CYAN) {
            override fun getIntrinsicWidth() = 512
            override fun getIntrinsicHeight() = 256
        }
        val bitmap = requireNotNull(renderShortcutIcon(wideIcon, 512))
        assertEquals(128, bitmap.width)
        assertEquals(64, bitmap.height)
        assertEquals(Color.CYAN, bitmap.getPixel(0, 0))
        assertNull(renderShortcutIcon(null, 512))
    }

    @Test
    fun grantedShizukuLoadsARealShortcutIcon() = runBlocking {
        // Запускается отдельно на эмуляторе с Contacts и разрешением DuoPress в Shizuku.
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyShortcutIcon") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val shortcut = ShizukuShortcuts.getShortcuts(app, "com.android.contacts")
            .single { it.id == "shortcut-add-contact" }
        val icon = requireNotNull(ShizukuShortcuts.getShortcutIcon(app, shortcut, 160, 32))
        assertTrue(icon.width in 1..32 && icon.height in 1..32)
    }

    @Test
    fun shortcutServiceTargetsAuthorizedAppsProfileNotTheShellProfile() {
        val appUid = 1_010_001
        assertEquals(UserHandle.getUserHandleForUid(1_010_002), requireShortcutCaller(appUid, appUid))
        assertThrows(SecurityException::class.java) { requireShortcutCaller(appUid, Process.SHELL_UID) }
    }

    @Test
    fun unsupportedAndroidShortCircuitsShizukuBeforeBinder() {
        assumeTrue("Requires API 29 or older", Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q)

        assertEquals(ShizukuShortcuts.Status.UNSUPPORTED_API, ShizukuShortcuts.checkStatus())
    }

    @Test
    fun shortcutArgumentsAreValidatedBeforeCheckingShizuku() {
        assertEquals("packageName is empty", assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ShizukuShortcuts.getShortcuts(null, " \t") }
        }.message)
        assertEquals("packageName is empty", assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ShizukuShortcuts.startShortcut(null, null, null) }
        }.message)
        assertEquals("shortcutId is empty", assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ShizukuShortcuts.startShortcut(null, "test.package", " \t") }
        }.message)
        assertEquals("shortcut == null", assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ShizukuShortcuts.getShortcutIcon(null, null, 160, 32) }
        }.message)
    }

    @Test
    fun missingVpnClientReturnsDiagnosticInsteadOfStartingActivity() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val packages = listOf("su.happ.proxyutility", "com.happproxy", "llc.itdev.incy")
        assumeFalse("Requires a device without Happ or Incy", packages.any { isInstalled(app, it) })
        val noExternalActions = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.CONNECTIVITY_SERVICE) {
                    throw AssertionError("Missing VPN client must not read tunnel state")
                }
                return super.getSystemService(name)
            }

            override fun startActivity(intent: Intent) {
                throw AssertionError("Missing VPN client must not start an activity")
            }

            override fun sendBroadcast(intent: Intent) {
                throw AssertionError("Missing VPN client must not send a broadcast")
            }
        }

        for ((setting, label) in listOf("auto" to "Happ / incy", "happ" to "Happ", "incy" to "incy")) {
            assertEquals(
                app.getString(R.string.vpn_status_not_installed, label),
                VpnController.controlVpn(noExternalActions, setting, "toggle"),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
