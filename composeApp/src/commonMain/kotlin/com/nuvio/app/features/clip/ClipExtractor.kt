package com.nuvio.app.features.clip

/**
 * Inputs for a single clip extraction. The URL and headers are the same values
 * the player is already using for playback ([PlayerScreenRuntime.activeSourceUrl] /
 * `activeSourceHeaders`), so a clip is cut from the exact stream on screen.
 */
internal data class ClipExtractRequest(
    val sourceUrl: String,
    val sourceHeaders: Map<String, String>,
    val startMs: Long,
    val endMs: Long,
    val title: String,
)

internal interface ClipTaskHandle {
    fun cancel()
}

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
        onSuccess: (outputFileUri: String) -> Unit,
        onFailure: (message: String) -> Unit,
    ): ClipTaskHandle

    /** Reveal the finished clip in the platform file manager. No-op where unsupported. */
    fun reveal(outputFileUri: String)
}
