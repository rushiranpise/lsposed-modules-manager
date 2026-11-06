package com.rushi.modulesmanager.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes installs handed to an external installer.
 *
 * The system installer is asynchronous and sends no completion callback to this
 * app. Keeping one package in flight stops two installer dialogs from stacking.
 * The slot is released by a package broadcast, by returning from the installer,
 * or by the timeout when the dialog was dismissed.
 */
class SystemInstallSerializer {
    private val pending = MutableStateFlow<String?>(null)

    suspend fun awaitFreeAndMarkPending(
        packageName: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        val acquired = withTimeoutOrNull(timeoutMs) {
            while (!pending.compareAndSet(null, packageName)) {
                pending.first { it == null }
            }
        }
        if (acquired == null) {
            // A dismissed installer must not deadlock every later hand-off.
            pending.value = packageName
        }
    }

    fun markCompleted(packageName: String) {
        // A late broadcast for the previous package must not release the next one.
        pending.compareAndSet(packageName, null)
    }

    fun isHeldBy(packageName: String): Boolean = pending.value == packageName

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 120_000L
    }
}
