package com.krayapp.duopress.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorCompatibilityTest {
    @Test
    fun recognizesVivoAndIqoo() {
        assertTrue(isSupportedVendor("VIVO", "vivo"))
        assertTrue(isSupportedVendor("vivo", "other"))
        assertTrue(isSupportedVendor("BBK", "iQOO"))
        assertTrue(isSupportedVendor("iQoO", "other"))
        assertFalse(isSupportedVendor("Google", "Pixel"))
        assertFalse(isSupportedVendor("", ""))
    }
}
