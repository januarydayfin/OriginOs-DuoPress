package com.krayapp.duopress.platform

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutServiceConnectionTest {
    @Test
    fun reusesLiveConnection() = runTest {
        val host = Host()
        val first = async { host.connection.awaitService() }
        runCurrent()
        host.attempts.single().connected("first")
        assertEquals("first", first.await())
        assertEquals("first", host.connection.awaitService())
        assertEquals(1, host.attempts.size)
        assertEquals(0, host.closed)
    }

    @Test
    fun reconnectsAfterDeathAndIgnoresTimedOutCallback() = runTest {
        val host = Host()
        val timedOut = async { runCatching { host.connection.awaitService() } }
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(timedOut.await().exceptionOrNull() is IllegalStateException)
        assertEquals(1, host.closed)

        val second = async { host.connection.awaitService() }
        runCurrent()
        host.attempts[0].connected("too late")
        runCurrent()
        assertFalse(second.isCompleted)
        host.attempts[1].connected("second")
        assertEquals("second", second.await())
        host.attempts[1].disconnected()

        val third = async { host.connection.awaitService() }
        runCurrent()
        host.attempts[2].connected("third")
        assertEquals("third", third.await())
    }

    private class Host {
        val attempts = mutableListOf<Attempt>()
        var closed = 0
        val connection = ShortcutServiceConnection<String>(
            bind = { connected, disconnected ->
                attempts.add(Attempt(connected, disconnected))
                val close: () -> Unit = { closed++ }
                close
            },
            isAlive = { true },
        )
    }

    private data class Attempt(val connected: (String) -> Unit, val disconnected: () -> Unit)
}
