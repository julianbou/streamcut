package com.nuvio.app.features.clip

/**
 * Inputs for a single clip extraction. The URL and headers are the same values
 * the player is already using for playback ([PlayerScreenRuntime.activeSourceUrl] /
 * `activeSourceHeaders`), so a clip is cut from the exact stream on screen.
 *
 * [audioTrackIndex] is the position of the playing audio track among the
 * source's audio streams -- the same ordinal the player's track list uses --
 * so a clip keeps the language the viewer had selected instead of whatever
 * happens to come first in the file. -1 means "first audio stream".
 *
 * [subtitle] is the subtitle to burn into the picture, or null to leave the
 * clip clean.
 *
 * [aspect] reshapes the frame by centre-cropping; [targetSizeMb] caps the
 * output, trading quality for a file that fits whatever it is being sent
 * through. 0 means no cap, which is the default and the better clip.
 *
 * [folderSegments] are folders to create under the clips folder and write into,
 * outermost first -- see [ClipFolderLayout]. Empty writes flat, which is the
 * default. Resolved when the job is queued rather than when it runs, so a clip
 * already in the queue lands where it did when it was started even if the
 * setting is changed while it waits.
 */
internal data class ClipExtractRequest(
    val sourceUrl: String,
    val sourceHeaders: Map<String, String>,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val audioTrackIndex: Int = -1,
    val subtitle: ClipSubtitleSelection? = null,
    val aspect: ClipAspect = ClipAspect.Source,
    val targetSizeMb: Int = 0,
    val folderSegments: List<String> = emptyList(),
)

internal interface ClipTaskHandle {
    fun cancel()
}

/**
 * Where a finished clip landed, and what it turned out to be.
 *
 * [thumbnailUri] is a still from the middle of the clip; [width]/[height] are
 * the clip's own dimensions, which stop matching the source's as soon as a
 * shape crop is involved. All of it is read off the finished local file rather
 * than the source -- no second network read, and the still shows what is really
 * in the clip, tonemapped and cropped and subtitled.
 */
internal data class ClipOutput(
    val fileUri: String,
    val fileName: String,
    val thumbnailUri: String = "",
    val fileSizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * Platform entry point for clip extraction.
 *
 * Desktop performs a frame-exact ffmpeg re-encode to a shareable H.264/AAC MP4,
 * downloading only the byte range for the selected window. Mobile targets are no-ops for now
 * ([isSupported] is false), so the clip UI stays hidden there without needing
 * per-call platform guards elsewhere.
 */
internal expect object ClipExtractor {
    /** Whether clip extraction is available on the current platform. */
    val isSupported: Boolean

    fun start(
        request: ClipExtractRequest,
        onProgress: (fraction: Float) -> Unit,
        onSuccess: (output: ClipOutput) -> Unit,
        onFailure: (message: String) -> Unit,
    ): ClipTaskHandle

    /**
     * Frames per second of [sourceUrl]'s video stream, or 0.0 when it cannot be
     * determined.
     *
     * The trim UI steps In/Out one frame at a time, which needs the source's
     * real rate: a fixed guess is off by a whole frame every few presses on
     * anything that is not 24fps. The native player does not expose a rate, so
     * this is probed from the container instead. Suspending because it reaches
     * the network -- for a remote source it is a ranged read of the header.
     */
    suspend fun probeFrameRate(sourceUrl: String, sourceHeaders: Map<String, String>): Double

    /** Reveal the finished clip in the platform file manager. No-op where unsupported. */
    fun reveal(outputFileUri: String)

    /** Open the clip in the system's default video player. No-op where unsupported. */
    fun openFile(outputFileUri: String)

    /** Whether the clip file is still on disk. */
    fun exists(outputFileUri: String): Boolean

    /** Delete the clip file. Missing files count as deleted. */
    fun deleteFile(outputFileUri: String)

    /**
     * Plain filesystem path for a `file:` URI, or "" if there is none.
     *
     * Clip identity is a URI everywhere else, but dragging a file out and
     * showing a path to a human both need the path itself.
     */
    fun filePathOf(fileUri: String): String

    /** Free space on the volume holding the output folder, or 0 if unknown. */
    fun outputDirFreeBytes(): Long

    /** Absolute path clips are written to right now (custom folder, or the default). */
    fun outputDirPath(): String

    /** Absolute path used when the user has not chosen a folder. */
    fun defaultOutputDirPath(): String

    /**
     * Point future exports at [path]; pass null to fall back to the default.
     * Returns false if the folder is unusable (missing, or not writable).
     */
    fun setOutputDirPath(path: String?): Boolean
}
