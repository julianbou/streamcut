package com.nuvio.app.features.clip

/**
 * Lifecycle of a single clip-extraction job.
 *
 * Kept intentionally small: a clip is produced by seeking into an
 * already-resolved stream URL and re-encoding the selected range to disk, so
 * there is no separate "queued" vs "downloading" distinction like the full
 * downloads feature has.
 */
enum class ClipStatus {
    Running,
    Completed,
    Failed,
    Cancelled,
}

/**
 * Snapshot of an in-flight or finished clip, observed by the player UI.
 *
 * [startMs]/[endMs] are positions in the source timeline (milliseconds).
 * [progress] is 0f..1f, derived from ffmpeg's reported output time.
 */
data class ClipJob(
    val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val status: ClipStatus,
    val progress: Float = 0f,
    val outputFileUri: String? = null,
    val errorMessage: String? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}
