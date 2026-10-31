package com.rushi.modulesmanager.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object ShizukuInstaller {
    private const val PERMISSION_REQUEST_CODE = 0x5348
    private const val PERMISSION_TIMEOUT_MS = 60_000L
    private const val INSTALL_TIMEOUT_SECONDS = 120L
    private const val BINDER_WAIT_MS = 2_000L
    private const val SHIZUKU_PACKAGE = "moe.shizuku.manager"

    /** What this app can do with Shizuku right now. The three states need different words. */
    enum class Status {
        NOT_RUNNING,
        PERMISSION_REQUIRED,
        READY,
    }

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isGranted(): Boolean = isRunning() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun status(): Status = when {
        !isRunning() -> Status.NOT_RUNNING
        isGranted() -> Status.READY
        else -> Status.PERMISSION_REQUIRED
    }

    /** Shizuku hands its binder over asynchronously, so a just-started service is not missed. */
    suspend fun awaitRunning(): Boolean {
        if (isRunning()) return true
        val deadline = System.currentTimeMillis() + BINDER_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(100)
            if (isRunning()) return true
        }
        return false
    }

    /** Opens the Shizuku app, which is the only place the service can be started. */
    fun openManager(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    suspend fun ensurePermission(): Boolean {
        if (isGranted()) return true
        if (!awaitRunning()) return false
        return withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                lateinit var listener: Shizuku.OnRequestPermissionResultListener
                listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                    if (requestCode == PERMISSION_REQUEST_CODE) {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                continuation.invokeOnCancellation {
                    Shizuku.removeRequestPermissionResultListener(listener)
                }
                try {
                    Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                } catch (_: Throwable) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    /** Streams the APK into `pm install -S`, so the shell user never needs access to app files. */
    suspend fun install(apk: File): String? = withContext(Dispatchers.IO) {
        val size = apk.length()
        if (size <= 0L) return@withContext "APK is empty"

        val process = try {
            val binder = Shizuku.getBinder() ?: return@withContext "Shizuku is not running"
            RemoteProcess(
                IShizukuService.Stub.asInterface(binder).newProcess(
                    arrayOf("pm", "install", "-r", "-t", "-S", size.toString()),
                    null,
                    null,
                ),
            )
        } catch (t: Throwable) {
            return@withContext t.message ?: "Could not start the Shizuku install"
        }

        try {
            apk.inputStream().use { input ->
                process.outputStream.use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            if (!process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                return@withContext "The Shizuku install timed out"
            }
            val output = (stdout + "\n" + stderr).trim()
            val exitCode = process.exitValue()
            if (exitCode == 0 && output.contains("Success", ignoreCase = true)) {
                null
            } else {
                output.ifBlank { "Shizuku install failed (exit $exitCode)" }
            }
        } catch (t: Throwable) {
            t.message ?: "Shizuku install failed"
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    /** Runs `pm uninstall` as the shell user. Returns null on success, or the output describing the failure. */
    suspend fun uninstall(packageName: String): String? = withContext(Dispatchers.IO) {
        val process = try {
            val binder = Shizuku.getBinder() ?: return@withContext "Shizuku is not running"
            RemoteProcess(
                IShizukuService.Stub.asInterface(binder).newProcess(
                    arrayOf("pm", "uninstall", "--user", "current", packageName),
                    null,
                    null,
                ),
            )
        } catch (t: Throwable) {
            return@withContext t.message ?: "Could not start the Shizuku uninstall"
        }

        try {
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            if (!process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                return@withContext "The Shizuku uninstall timed out"
            }
            val output = (stdout + "\n" + stderr).trim()
            val exitCode = process.exitValue()
            if (exitCode == 0 && output.contains("Success", ignoreCase = true)) {
                null
            } else {
                output.ifBlank { "Shizuku uninstall failed (exit $exitCode)" }
            }
        } catch (t: Throwable) {
            t.message ?: "Shizuku uninstall failed"
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    private class RemoteProcess(private val remote: IRemoteProcess) : Process() {
        private val input by lazy { ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream) }
        private val output by lazy { ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream) }
        private val error by lazy { ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream) }

        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun getErrorStream(): InputStream = error
        override fun waitFor(): Int = remote.waitFor()

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            remote.waitForTimeout(timeout, unit.toString())

        override fun exitValue(): Int = remote.exitValue()

        override fun destroy() {
            runCatching { remote.destroy() }
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = remote.alive()
    }
}
