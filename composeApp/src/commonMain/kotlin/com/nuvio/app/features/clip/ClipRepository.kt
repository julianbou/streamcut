package com.nuvio.app.features.clip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


/**
 * Tracks clip-extraction jobs, one slot per movie/episode.
 *
 * Jobs are keyed by [ClipContentRef.key] rather than held in a single global
 * slot, so an export started on one title never shows its progress -- or its
 * "saved"/"failed" notice -- while a different title is on screen. Two
 * different titles can export at the same time; starting a second clip on the
 * *same* title still replaces the first, which matches the one-trim-at-a-time
 * player UI.
 */
object ClipRepository {
    private val _jobs = MutableStateFlow<Map<String, ClipJob>>(emptyMap())

    /** Every tracked job, keyed by content. Feeds "exports in progress" surfaces. */
    val jobs: StateFlow<Map<String, ClipJob>> = _jobs.asStateFlow()

    private val handles = mutableMapOf<String, ClipTaskHandle>()
    private var counter = 0L

    /** Whether the current platform can extract clips (desktop only for now). */
    val isSupported: Boolean get() = ClipExtractor.isSupported

    /** Absolute path clips are currently written to. Empty where unsupported. */
    fun outputDirPath(): String = ClipExtractor.outputDirPath()

    /** Absolute path used when no custom folder is set. */
    fun defaultOutputDirPath(): String = ClipExtractor.defaultOutputDirPath()

    /**
     * Point future exports at [path]; null restores the default folder.
     * Returns false when the folder is missing or not writable, in which case
     * the previous choice is left untouched.
     */
    fun setOutputDirPath(path: String?): Boolean = ClipExtractor.setOutputDirPath(path)

    /** The job belonging to [contentKey], if one has run this session. */
    fun jobFor(contentKey: String): ClipJob? = _jobs.value[contentKey]

    /**
     * Begin extracting [startMs]..[endMs] from the currently playing stream.
     * No-ops on unsupported platforms or invalid ranges.
     */
    fun startClip(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        content: ClipContentRef,
        startMs: Long,
        endMs: Long,
    ) {
        if (!isSupported) return
        if (sourceUrl.isBlank() || endMs <= startMs) return

        val contentKey = content.key
        handles.remove(contentKey)?.cancel()

        // The counter alone restarts at zero every launch, so it is paired with
        // the clock: library entries outlive the process and their ids must not
        // collide across sessions.
        val id = "clip-${ClipClock.nowEpochMs()}-${++counter}"
        val title = content.label.ifBlank { "Clip" }
        put(
            contentKey,
            ClipJob(
                id = id,
                content = content,
                title = title,
                startMs = startMs,
                endMs = endMs,
                status = ClipStatus.Running,
                progress = 0f,
            ),
        )

        handles[contentKey] = ClipExtractor.start(
            request = ClipExtractRequest(
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                startMs = startMs,
                endMs = endMs,
                title = title,
            ),
            onProgress = { fraction ->
                update(contentKey, id) { it.copy(progress = fraction.coerceIn(0f, 1f)) }
            },
            onSuccess = { output ->
                update(contentKey, id) {
                    it.copy(
                        status = ClipStatus.Completed,
                        progress = 1f,
                        outputFileUri = output.fileUri,
                    )
                }
                ClipLibrary.record(
                    ClipEntry(
                        id = id,
                        content = content,
                        startMs = startMs,
                        endMs = endMs,
                        outputFileUri = output.fileUri,
                        fileName = output.fileName,
                        createdAtEpochMs = ClipClock.nowEpochMs(),
                    ),
                )
            },
            onFailure = { message ->
                update(contentKey, id) { it.copy(status = ClipStatus.Failed, errorMessage = message) }
            },
        )
    }

    /** Cancel the in-flight clip for [contentKey], if any. */
    fun cancel(contentKey: String) {
        handles.remove(contentKey)?.cancel()
        val current = _jobs.value[contentKey] ?: return
        if (current.status == ClipStatus.Running) {
            put(contentKey, current.copy(status = ClipStatus.Cancelled))
        }
    }

    /** Dismiss the completion/failure notice for [contentKey]. */
    fun dismiss(contentKey: String) {
        _jobs.value = _jobs.value - contentKey
    }

    /** Reveal the most recent completed clip for [contentKey] in the file manager. */
    fun revealOutput(contentKey: String) {
        val uri = _jobs.value[contentKey]
            ?.takeIf { it.status == ClipStatus.Completed }
            ?.outputFileUri
            ?: return
        ClipExtractor.reveal(uri)
    }

    private fun put(contentKey: String, job: ClipJob) {
        _jobs.value = _jobs.value + (contentKey to job)
    }

    private inline fun update(contentKey: String, id: String, transform: (ClipJob) -> ClipJob) {
        val current = _jobs.value[contentKey]
        // The id guard drops late callbacks from a job that was already replaced.
        if (current != null && current.id == id) {
            put(contentKey, transform(current))
        }
    }
}
