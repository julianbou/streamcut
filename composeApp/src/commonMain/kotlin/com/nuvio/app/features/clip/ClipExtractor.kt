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
 */
internal data class ClipExtractRequest(
    val sourceUrl: String,
    val sourceHeaders: Map<String, String>,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val audioTrackIndex: Int = -1,
    val subtitle: ClipSubtitleSelection? = null,
)

internal interface ClipTaskHandle {
    fun cancel()
}

/** Where a finished clip landed, so the library can index it. */
internal data class ClipOutput(
    val fileUri: String,
    val fileName: String,
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
