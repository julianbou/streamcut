package com.nuvio.app.features.clip

/**
 * Clip extraction is desktop-only for now. Android reports unsupported so the
 * clip UI stays hidden; [start] should never be reached, but fails loudly if it is.
 */
internal actual object ClipExtractor {
    actual val isSupported: Boolean = false

    actual fun start(
        request: ClipExtractRequest,
        onProgress: (fraction: Float) -> Unit,
        onSuccess: (outputFileUri: String) -> Unit,
        onFailure: (message: String) -> Unit,
    ): ClipTaskHandle {
        onFailure("Clipping is not supported on this platform")
        return object : ClipTaskHandle {
            override fun cancel() {}
        }
    }

    actual fun reveal(outputFileUri: String) {}
}
