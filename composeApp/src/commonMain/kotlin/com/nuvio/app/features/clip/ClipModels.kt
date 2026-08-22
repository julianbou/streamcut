package com.nuvio.app.features.clip

import kotlinx.serialization.Serializable

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
 * Identifies the movie or episode a clip was cut from.
 *
 * Every job and every saved clip carries one, which is what keeps export
 * progress from leaking across titles: the player renders only the job whose
 * key matches what is currently on screen, and the clip library groups by it.
 *
 * [videoId] is the catalog id (`tt1234567`, or `tt1234567:1:2` for episodes)
 * when the player knows it. It can be blank for a direct-URL playback, so
 * [key] falls back to the display title -- good enough to keep two different
 * things apart, which is all the grouping needs.
 */
@Serializable
data class ClipContentRef(
    val videoId: String = "",
    val title: String = "",
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String = "",
) {
    /** Stable grouping key. Episodes of one series stay separate from each other. */
    val key: String
        get() = buildString {
            append(videoId.ifBlank { "title:$title" })
            if (seasonNumber != null && episodeNumber != null) {
                append(":s").append(seasonNumber).append("e").append(episodeNumber)
            }
        }

    /** Title as shown next to a clip, e.g. `Severance S02E05`. */
    val label: String
        get() = buildString {
            append(title)
            if (seasonNumber != null && episodeNumber != null) {
                append(" S").append(seasonNumber.toString().padStart(2, '0'))
                append("E").append(episodeNumber.toString().padStart(2, '0'))
            }
        }

    companion object {
        val Empty = ClipContentRef()
    }
}

/**
 * Snapshot of an in-flight or finished clip, observed by the player UI.
 *
 * [startMs]/[endMs] are positions in the source timeline (milliseconds).
 * [progress] is 0f..1f, derived from ffmpeg's reported output time.
 */
data class ClipJob(
    val id: String,
    val content: ClipContentRef,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val status: ClipStatus,
    val progress: Float = 0f,
    val outputFileUri: String? = null,
    val errorMessage: String? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val contentKey: String get() = content.key
}

/**
 * A clip that finished exporting and still exists on disk.
 *
 * Persisted as JSON so the home screen and the in-player list can show a
 * library across restarts. [outputFileUri] is the canonical identity -- a
 * re-export to the same path replaces the entry rather than duplicating it.
 */
@Serializable
data class ClipEntry(
    val id: String,
    val content: ClipContentRef,
    val startMs: Long,
    val endMs: Long,
    val outputFileUri: String,
    val fileName: String,
    val createdAtEpochMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val contentKey: String get() = content.key
}
