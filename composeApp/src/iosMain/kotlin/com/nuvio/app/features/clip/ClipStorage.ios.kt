package com.nuvio.app.features.clip

/** Clip extraction is unsupported on this platform, so nothing is persisted. */
internal actual object ClipStorage {
    actual fun loadLibraryPayload(): String? = null
    actual fun saveLibraryPayload(payload: String) = Unit
    actual fun loadOutputDir(): String? = null
    actual fun saveOutputDir(path: String?) = Unit
    actual fun loadFilenameTemplate(): String? = null
    actual fun saveFilenameTemplate(template: String?) = Unit
    actual fun loadGroupByTitle(): Boolean = false
    actual fun saveGroupByTitle(enabled: Boolean) = Unit
}
