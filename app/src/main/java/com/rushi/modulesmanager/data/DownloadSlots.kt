package com.rushi.modulesmanager.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single place that decides how many APK transfers may run at once.
 *
 * "Update all" and a module-page install both take a slot here, so the setting applies to
 * the app rather than to one screen. Waiters are admitted first in, first out; a transfer
 * already running when the setting changes finishes normally.
 */
class DownloadSlots(private val limit: () -> Int) {
    private val mutex = Mutex()
    private var active = 0
    private val waiters = mutableListOf<CompletableDeferred<Unit>>()

    val currentLimit: Int
        get() = limit().coerceIn(MIN_LIMIT, MAX_LIMIT)

    suspend fun <T> withSlot(block: suspend () -> T): T {
        val turn = CompletableDeferred<Unit>()
        mutex.withLock {
            waiters += turn
            admit()
        }
        try {
            turn.await()
            return block()
        } finally {
            mutex.withLock {
                if (turn.isCompleted) {
                    active--
                    admit()
                } else {
                    // Cancelled while still queued: leave without holding a slot.
                    waiters.remove(turn)
                }
            }
        }
    }

    /** Hands free slots to the longest-waiting transfers. Caller holds [mutex]. */
    private fun admit() {
        while (active < currentLimit && waiters.isNotEmpty()) {
            active++
            waiters.removeAt(0).complete(Unit)
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 3
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 5
    }
}
