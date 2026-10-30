package com.rushi.modulesmanager.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Tells the downloader whether a usable network is up, and lets a failed transfer wait for
 * a reconnection instead of hammering a dead socket.
 */
interface NetworkMonitor {

    /** Whether a network that is actually validated can reach the internet. */
    fun isAvailable(): Boolean

    /** Suspends until a validated network is available again, or [timeoutMs] passes. */
    suspend fun awaitAvailable(timeoutMs: Long): Boolean

    object AlwaysOnline : NetworkMonitor {
        override fun isAvailable(): Boolean = true
        override suspend fun awaitAvailable(timeoutMs: Long): Boolean = true
    }
}

/**
 * [NetworkMonitor] backed by the default network callback. "Available" means the default
 * network carries both INTERNET and VALIDATED, so a Wi-Fi link that is still associated but
 * has no upstream (router rebooting, phone out of range) is treated as offline.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isAvailable(): Boolean {
        val manager = connectivity ?: return true
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network).isUsable()
    }

    override suspend fun awaitAvailable(timeoutMs: Long): Boolean {
        val manager = connectivity ?: return true
        // Register before the second check: a reconnection between the first check and
        // the registration must not be missed.
        val reconnected = CompletableDeferred<Unit>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = qualify(network)

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = qualify(network)

            private fun qualify(network: Network) {
                if (manager.getNetworkCapabilities(network).isUsable()) {
                    reconnected.complete(Unit)
                }
            }
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            if (isAvailable()) reconnected.complete(Unit)
            withTimeoutOrNull(timeoutMs) { reconnected.await() } != null
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            // Some OEM builds reject callbacks in a restricted state; let the transfer decide.
            true
        } finally {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}

private fun NetworkCapabilities?.isUsable(): Boolean =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

/**
 * Retry policy for a transfer that died mid-stream. Reconnection is preferred over a fixed
 * delay: with no validated network the retry waits for one to come back, up to a bounded
 * time, so a download survives a Wi-Fi drop instead of failing behind the user's back.
 */
class DownloadRetry(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val offlineWaitMs: Long = DEFAULT_OFFLINE_WAIT_MS,
    private val network: NetworkMonitor = NetworkMonitor.AlwaysOnline,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /** Whether [attempt] (1-based) may run again after [error]. */
    fun canRetry(attempt: Int, error: Throwable): Boolean =
        attempt < maxAttempts && isTransientDownloadError(error)

    /**
     * Waits before attempt [nextAttempt] (2 for the first retry). Returns false when the
     * device stayed offline for the whole grace period, so the failure can surface.
     */
    suspend fun awaitNextAttempt(nextAttempt: Int): Boolean {
        if (!network.isAvailable()) return network.awaitAvailable(offlineWaitMs)
        sleep(backoffMs(nextAttempt))
        return true
    }

    /** 1s, 2s, 4s, ... capped at the maximum delay. */
    fun backoffMs(nextAttempt: Int): Long {
        val index = (nextAttempt - 2).coerceAtLeast(0)
        return if (index >= 32) maxDelayMs else {
            (baseDelayMs shl index).coerceAtMost(maxDelayMs)
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 4
        const val DEFAULT_BASE_DELAY_MS: Long = 1_000L
        const val DEFAULT_MAX_DELAY_MS: Long = 8_000L
        const val DEFAULT_OFFLINE_WAIT_MS: Long = 20_000L
    }
}

/** A request the server answered with an HTTP error. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** Only 408, 429 and 5xx are worth another attempt; a 404 will not change. */
fun isTransientDownloadError(error: Throwable): Boolean = when (error) {
    is HttpStatusException -> error.code == 408 || error.code == 429 || error.code >= 500
    is IOException -> true
    else -> false
}
