package com.rushi.modulesmanager.data

import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One manual APK save. The request lives in the app container and the foreground
 * service performs the transfer, so the save does not depend on an activity or
 * a ViewModel staying alive.
 */
class ApkSaveCenter {

    data class Request(
        val packageName: String,
        val label: String,
        val downloadUrl: String,
        val originalDownloadUrl: String?,
        val size: Long,
        val exportName: String,
        val destination: File,
    )

    data class State(
        val label: String,
        val fileName: String,
        val downloaded: Long = 0L,
        val total: Long = 0L,
        val phase: Phase = Phase.PREPARING,
    ) {
        enum class Phase { PREPARING, DOWNLOADING, SAVING }

        val fraction: Float?
            get() = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var request: Request? = null

    /** Claims the single save slot. A second save is refused until this one finishes. */
    @Synchronized
    fun begin(request: Request): Boolean {
        if (_state.value != null) return false
        this.request = request
        _state.value = State(
            label = request.label,
            fileName = request.exportName,
            total = request.size,
        )
        return true
    }

    @Synchronized
    fun currentRequest(): Request? = request

    fun phase(phase: State.Phase) {
        _state.value = _state.value?.copy(phase = phase)
    }

    fun progress(downloaded: Long, total: Long) {
        val current = _state.value ?: return
        _state.value = current.copy(
            downloaded = downloaded,
            total = if (total > 0L) total else current.total,
        )
    }

    fun finish(message: String) {
        val hadSave = _state.value != null
        request = null
        _state.value = null
        if (hadSave) _messages.tryEmit(message)
    }

    fun cancel() {
        request = null
        _state.value = null
    }
}
