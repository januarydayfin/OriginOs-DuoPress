package com.krayapp.duopress.platform

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShizukuInstallTest {
    @Test
    fun storeUsesHttpsOnlyWhenMarketCannotOpen() {
        val market = "market://details?id=moe.shizuku.privileged.api"
        val web = "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
        val attempts = mutableListOf<Intent>()

        assertTrue(openShizukuStore { attempts += it; true })
        assertEquals(listOf(market), attempts.map(Intent::getDataString))
        assertEquals("com.android.vending", attempts.single().`package`)

        attempts.clear()
        assertTrue(openShizukuStore { attempts += it; it.dataString == web })
        assertEquals(listOf(market, web), attempts.map(Intent::getDataString))
        assertEquals(null, attempts.last().`package`)
        assertTrue(attempts.all { it.action == Intent.ACTION_VIEW })

        attempts.clear()
        assertFalse(openShizukuStore { attempts += it; false })
        assertEquals(listOf(market, web), attempts.map(Intent::getDataString))
    }
}
