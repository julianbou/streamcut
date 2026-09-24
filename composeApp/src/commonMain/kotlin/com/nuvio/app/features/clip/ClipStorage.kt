package com.nuvio.app.features.clip

/**
 * Persistence for the clip library and the user's chosen output folder.
 *
 * Mirrors the shape of [com.nuvio.app.features.home.HomeCatalogSettingsStorage]:
 * a tiny string in/out contract per platform, with all parsing kept in common
 * code. Mobile targets have no clip extractor yet, so their implementations are
 * inert.
 */
internal expect object ClipStorage {
    fun loadLibraryPayload(): String?
    fun saveLibraryPayload(payload: String)

    /** Absolute path of the user's chosen clips folder, or null for the default. */
    fun loadOutputDir(): String?
    fun saveOutputDir(path: String?)

    /** The user's clip-filename template, or null to use [ClipFilenameTemplate.Default]. */
    fun loadFilenameTemplate(): String?
    fun saveFilenameTemplate(template: String?)

    /** Whether clips are filed into per-title folders. See [ClipFolderLayout]. */
    fun loadGroupByTitle(): Boolean
    fun saveGroupByTitle(enabled: Boolean)

    /** Folders recently chosen in Save as, newest first, one per line. See [ClipSaveFolders]. */
    fun loadSaveFolders(): String?
    fun saveSaveFolders(payload: String?)
}
