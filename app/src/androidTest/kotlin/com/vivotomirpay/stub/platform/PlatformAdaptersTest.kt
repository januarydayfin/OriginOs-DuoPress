package com.vivotomirpay.stub.platform

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivotomirpay.stub.R
import com.vivotomirpay.stub.ShizukuShortcuts
import com.vivotomirpay.stub.VpnController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformAdaptersTest {
    @Test
    fun unsupportedAndroidShortCircuitsShizukuBeforeBinder() {
        assumeTrue("Requires API 29 or older", Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q)

        assertEquals(ShizukuShortcuts.Status.UNSUPPORTED_API, ShizukuShortcuts.checkStatus())
    }

    @Test
    fun shortcutArgumentsAreValidatedBeforeCheckingShizuku() {
        assertEquals("packageName is empty", assertThrows(IllegalArgumentException::class.java) {
            ShizukuShortcuts.getShortcuts(null, " \t")
        }.message)
        assertEquals("packageName is empty", assertThrows(IllegalArgumentException::class.java) {
            ShizukuShortcuts.startShortcut(null, null, null)
        }.message)
        assertEquals("shortcutId is empty", assertThrows(IllegalArgumentException::class.java) {
            ShizukuShortcuts.startShortcut(null, "test.package", " \t")
        }.message)
        assertEquals("shortcut == null", assertThrows(IllegalArgumentException::class.java) {
            ShizukuShortcuts.getShortcutIcon(null, null)
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
