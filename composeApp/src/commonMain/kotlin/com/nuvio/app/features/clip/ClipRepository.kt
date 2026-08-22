package com.nuvio.app.features.clip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the single most recent clip job so the player can render progress and a
 * completion/failure notice. Milestone 1 supports one clip at a time; starting a
 * new clip cancels any in-flight one.
 */
object ClipRepository {
    private val _activeJob = MutableStateFlow<ClipJob?>(null)
    val activeJob: StateFlow<ClipJob?> = _activeJob.asStateFlow()

    private var handle: ClipTaskHandle? = null
    private var counter = 0L

    /** Whether the current platform can extract clips (desktop only for now). */
    val isSupported: Boolean get() = ClipExtractor.isSupported

    /**
     * Begin extracting [startMs]..[endMs] from the currently playing stream.
     * No-ops on unsupported platforms or invalid ranges.
     */
    fun startClip(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        title: String,
        startMs: Long,
        endMs: Long,
    ) {
        if (!isSupported) return
        if (sourceUrl.isBlank() || endMs <= startMs) return

        handle?.cancel()

        val id = "clip-${++counter}"
        _activeJob.value = ClipJob(
            id = id,
            title = title,
            startMs = startMs,
            endMs = endMs,
            status = ClipStatus.Running,
            progress = 0f,
        )

        handle = ClipExtractor.start(
            request = ClipExtractRequest(
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                startMs = startMs,
                endMs = endMs,
                title = title,
            ),
            onProgress = { fraction ->
                update(id) { it.copy(progress = fraction.coerceIn(0f, 1f)) }
            },
            onSuccess = { outputFileUri ->
                update(id) {
                    it.copy(status = ClipStatus.Completed, progress = 1f, outputFileUri = outputFileUri)
                }
            },
            onFailure = { message ->
                update(id) { it.copy(status = ClipStatus.Failed, errorMessage = message) }
            },
        )
    }

    /** Cancel the in-flight clip, if any. */
    fun cancel() {
        handle?.cancel()
        handle = null
        val current = _activeJob.value ?: return
        if (current.status == ClipStatus.Running) {
            _activeJob.value = current.copy(status = ClipStatus.Cancelled)
        }
    }

    /** Dismiss the completion/failure notice. */
    fun dismiss() {
        _activeJob.value = null
    }

    /** Reveal the most recent completed clip in the platform file manager. */
    fun revealOutput() {
        val uri = _activeJob.value
            ?.takeIf { it.status == ClipStatus.Completed }
            ?.outputFileUri
            ?: return
        ClipExtractor.reveal(uri)
    }

    private inline fun update(id: String, transform: (ClipJob) -> ClipJob) {
        val current = _activeJob.value
        if (current != null && current.id == id) {
            _activeJob.value = transform(current)
        }
    }
}
