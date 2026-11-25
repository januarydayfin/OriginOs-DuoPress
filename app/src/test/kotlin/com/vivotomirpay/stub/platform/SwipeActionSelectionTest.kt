package com.vivotomirpay.stub.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeActionSelectionTest {
    @Test
    fun unlockedAlwaysUsesPrimary() {
        assertEquals("app:example.primary", resolveSwipeAction(false, true, "special:camera", "app:example.primary"))
        assertEquals("app:example.primary", resolveSwipeAction(false, false, "special:torch", "app:example.primary"))
    }

    @Test
    fun lockedWithOptOutUsesPrimary() {
        assertEquals("special:menu", resolveSwipeAction(true, false, "special:camera", "special:menu"))
    }

    @Test
    fun lockedCameraPreserved() {
        assertEquals("special:camera", resolveSwipeAction(true, true, "special:camera", "app:example.primary"))
    }

    @Test
    fun lockedInvalidUsesTorch() {
        for (action in listOf("app:example.other", "", " special:camera ", "SPECIAL:TORCH", "special:torch")) {
            assertEquals("special:torch", resolveSwipeAction(true, true, action, "app:example.primary"))
        }
    }
}
