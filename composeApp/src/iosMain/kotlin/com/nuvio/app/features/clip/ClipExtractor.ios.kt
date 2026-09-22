package com.nuvio.app.features.clip

/**
 * Clip extraction is desktop-only for now. [isSupported] being false keeps the
 * clip UI hidden here, so none of the other members are ever reached.
 */
internal actual object ClipExtractor {
    actual val isSupported: Boolean = false

    actual fun start(
        request: ClipExtractRequest,
        onProgress: (fraction: Float) -> Unit,
        onSuccess: (output: ClipOutput) -> Unit,
        onFailure: (message: String) -> Unit,
    ): ClipTaskHandle {
        onFailure("Clip export is not available on this platform yet")
        return object : ClipTaskHandle {
            override fun cancel() = Unit
        }
    }

    actual suspend fun probeFrameRate(sourceUrl: String, sourceHeaders: Map<String, String>): Double = 0.0

    actual suspend fun toolStatus(): ClipToolStatus? = null

    actual fun reveal(outputFileUri: String) = Unit
    actual fun openFile(outputFileUri: String) = Unit
    actual fun exists(outputFileUri: String): Boolean = false
    actual fun deleteFile(outputFileUri: String) = Unit
    actual fun filePathOf(fileUri: String): String = ""
    actual fun outputDirFreeBytes(): Long = 0L
    actual fun outputDirPath(): String = ""
    actual fun defaultOutputDirPath(): String = ""
    actual fun setOutputDirPath(path: String?): Boolean = false
}
