package com.krayapp.duopress.platform

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Сохраняет живое соединение и не принимает callback от предыдущей попытки. */
internal class ShortcutServiceConnection<T : Any>(
    private val bind: ((T) -> Unit, () -> Unit) -> (() -> Unit),
    private val isAlive: (T) -> Boolean,
    private val timeoutMs: Long = 5_000,
) {
    private val mutex = Mutex()
    private var active: Binding<T>? = null

    suspend fun awaitService(): T = mutex.withLock {
        active?.let {
            if (!it.disconnected.get() && isAlive(it.service)) return@withLock it.service
            active = null
            it.unbind()
        }

        val result = CompletableDeferred<T>()
        val disconnected = AtomicBoolean(false)
        val unbind = bind({ result.complete(it) }, {
            disconnected.set(true)
            result.completeExceptionally(IllegalStateException("Shortcut UserService disconnected"))
        })
        var retained = false
        try {
            val service = withTimeoutOrNull(timeoutMs) { result.await() }
                ?: error("Shortcut UserService did not connect within $timeoutMs ms")
            check(!disconnected.get() && isAlive(service)) { "Shortcut UserService disconnected" }
            active = Binding(service, disconnected, unbind)
            retained = true
            service
        } finally {
            if (!retained) {
                result.cancel()
                unbind()
            }
        }
    }

    private class Binding<T>(val service: T, val disconnected: AtomicBoolean, val unbind: () -> Unit)
}
