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
    fun circleToSearchWaitsForFinishAndFor550msFromSelection() {
        val host = FakeHost()
        host.advanceTo(100)
        host.menu.select("special:circle_to_search")
        host.menu.select("special:camera")
        host.menu.dismiss()
        assertEquals(listOf("close"), host.events)

        host.advanceTo(320)
        host.menu.onHidden()
        host.menu.onHidden()
        host.menu.onWindowDetached()
        host.menu.onWindowDetached()
        assertEquals(listOf("close", "finish"), host.events)
        host.advanceTo(649)
        assertEquals(listOf("close", "finish"), host.events)
        host.advanceTo(650)
        assertEquals(listOf("close", "finish", "circle@650"), host.events)
        host.advanceTo(900)
        assertEquals(listOf("close", "finish", "circle@650"), host.events)
    }

    @Test
    fun circleToSearchNeverDispatchesWhileWindowIsStillAttached() {
        val host = FakeHost()
        host.menu.select("special:circle_to_search")
        host.advanceTo(220)
        host.menu.onHidden()
        host.advanceTo(800)
        assertEquals(listOf("close", "finish"), host.events)

        host.menu.onWindowDetached()
        host.advanceTo(800)
        assertEquals(listOf("close", "finish", "circle@800"), host.events)
    }

    @Test
    fun immediateActionDispatchesBeforeClosingAndOnlyOnce() {
        val host = FakeHost()
        host.menu.select("app:org.example.app")
        host.menu.select("special:camera")
        host.menu.onHidden()
        host.menu.onWindowDetached()
        host.advanceTo(550)

        assertEquals(listOf("run:app:org.example.app", "close", "finish"), host.events)
    }

    @Test
    fun dismissalPreventsLateSelection() {
        val host = FakeHost()
        host.menu.dismiss()
        host.menu.select("special:circle_to_search")
        host.menu.onHidden()
        host.menu.onWindowDetached()
        host.advanceTo(550)

        assertEquals(listOf("close", "finish"), host.events)
    }

    private class FakeHost {
        val events = mutableListOf<String>()
        var shortcutComplete: (() -> Unit)? = null
        private var now = 0L
        private val queued = mutableListOf<Pair<Long, () -> Unit>>()
        val menu = MenuSelection(
            nowMs = { now },
            scheduleAt = { deadline, task -> queued += deadline to task },
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
            dispatchCircleToSearch = { events += "circle@$now" },
        )

        fun advanceTo(time: Long) {
            val (due, pending) = queued.partition { it.first <= time }
            queued.clear()
            queued.addAll(pending)
            due.sortedBy { it.first }.forEach { (deadline, task) ->
                now = maxOf(deadline, now)
                task()
            }
            now = time
        }
    }
}
