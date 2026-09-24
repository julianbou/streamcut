package com.nuvio.app.features.clip

import kotlinx.serialization.Serializable

/**
 * Lifecycle of a single clip-extraction job.
 *
 * A clip is produced by seeking into an already-resolved stream URL and
 * re-encoding the selected range to disk. Several clips can be marked in a row
 * without waiting for the previous export, so a job starts [Queued] and becomes
 * [Running] once [ClipRepository] has a free encoding slot.
 */
enum class ClipStatus {
    Queued,
    Running,
    Completed,
    Failed,
    Cancelled,
}

/**
 * Identifies the movie or episode a clip was cut from.
 *
 * Every job and every saved clip carries one, which is what keeps export
 * progress from leaking across titles: the player renders only the jobs whose
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
 * Frame shape a clip is exported in.
 *
 * A clip headed for a phone screen is not the shape it was shot in, and
 * reshaping it afterwards costs a second re-encode and another generation of
 * loss. [ratio] is width over height; null leaves the source frame alone.
 *
 * Ordinals are the wire format -- the webview bridge carries numbers, not
 * strings -- so entries may be appended but not reordered.
 */
enum class ClipAspect(val label: String, val ratio: Double?) {
    Source("Source", null),
    Wide("16:9", 16.0 / 9.0),
    Square("1:1", 1.0),
    Portrait("4:5", 4.0 / 5.0),
    Vertical("9:16", 9.0 / 16.0),
    ;

    companion object {
        fun fromOrdinal(value: Int): ClipAspect = entries.getOrElse(value) { Source }
    }
}

/**
 * The subtitle a clip is rendered with. Burning is the only option that
 * survives sharing: an MP4 the viewer sends to someone else has no track
 * picker, so the subtitle has to be part of the picture.
 *
 * [delayMs] mirrors the player's subtitle delay so a manually re-synced
 * subtitle lands on the same frames it did on screen.
 */
sealed interface ClipSubtitleSelection {
    val delayMs: Int

    /** A subtitle stream inside the source, by its ordinal among subtitle streams. */
    data class Embedded(val trackIndex: Int, override val delayMs: Int = 0) : ClipSubtitleSelection

    /** A sidecar subtitle file (addon subtitles), by URL. */
    data class External(val url: String, override val delayMs: Int = 0) : ClipSubtitleSelection
}

/**
 * Snapshot of an in-flight or finished clip, observed by the player UI.
 *
 * [id] is the job's identity for its whole life: every clip started gets its
 * own, so a second export on a title never disturbs the first.
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
    /** The folder Save as chose, or null for the clips folder. */
    val destinationDir: String? = null,
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
    // Everything below defaults, so a library persisted before these existed
    // still decodes instead of coming back empty on upgrade. A clip from then
    // simply has no still and no size until it is exported again.
    val thumbnailUri: String = "",
    val fileSizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val contentKey: String get() = content.key

    /** e.g. `1080p`, or blank when the clip predates dimension capture. */
    val resolutionLabel: String
        get() = if (width <= 0 || height <= 0) "" else "${height}p"

    /** e.g. `24.3 MB`, blank when unknown. Decimal MB, as the file manager counts. */
    val fileSizeLabel: String
        get() = when {
            fileSizeBytes <= 0L -> ""
            fileSizeBytes < 1_000_000L -> "${(fileSizeBytes / 1_000L).coerceAtLeast(1L)} KB"
            fileSizeBytes < 1_000_000_000L -> {
                val tenths = (fileSizeBytes / 100_000L)
                "${tenths / 10}.${tenths % 10} MB"
            }
            else -> {
                val tenths = (fileSizeBytes / 100_000_000L)
                "${tenths / 10}.${tenths % 10} GB"
            }
        }
}
