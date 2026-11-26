package com.krayapp.duopress.menu

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuSelectionTest {
    @Test
    fun shortcutDelaysCloseAndDispatchesOnlyOnce() {
        val host = FakeHost()
        val id = "shortcut:pkg:home:Home"
        host.menu.select(id)
        host.menu.select(id)
        host.menu.onHidden()
        assertEquals(listOf("dispatch:$id"), host.events)

        host.shortcutComplete?.invoke() ?: error("No shortcut in flight")
        host.menu.onHidden()
        host.menu.onHidden()
        assertEquals(listOf("dispatch:$id", "close", "finish"), host.events)
    }

    @Test
    fun immediateActionDispatchesBeforeClosingAndOnlyOnce() {
        val host = FakeHost()
        host.menu.select("app:org.example.app")
        host.menu.select("special:camera")
        host.menu.onHidden()

        assertEquals(listOf("run:app:org.example.app", "close", "finish"), host.events)
    }

    @Test
    fun dismissalPreventsLateSelection() {
        val host = FakeHost()
        host.menu.dismiss()
        host.menu.select("special:camera")
        host.menu.onHidden()

        assertEquals(listOf("close", "finish"), host.events)
    }

    private class FakeHost {
        val events = mutableListOf<String>()
        var shortcutComplete: (() -> Unit)? = null
        val menu = MenuSelection(
            close = { events += "close" },
            finish = { events += "finish" },
            dispatch = { id, complete ->
                if (id.startsWith("shortcut:")) {
                    events += "dispatch:$id"
                    shortcutComplete = complete
                } else {
                    events += "run:$id"
                    complete()
                }
            },
        )
    }
}
