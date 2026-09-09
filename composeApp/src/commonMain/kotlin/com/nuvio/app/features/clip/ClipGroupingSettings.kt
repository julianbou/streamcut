package com.nuvio.app.features.clip

/**
 * Whether exports are filed into per-title folders, cached in memory over
 * [ClipStorage].
 *
 * Read on the export path, which runs on an IO thread once per clip, so it is
 * cached rather than hitting the preference store each time -- the same reason
 * [ClipFilenameSettings] is.
 *
 * Off by default: turning it on changes where files land, and a setting that
 * moves the user's output without being asked for is a setting that loses it.
 */
internal object ClipGroupingSettings {

    private var cached: Boolean = false
    private var loaded = false

    /** Whether new clips go into a folder named after what they were cut from. */
    fun isEnabled(): Boolean {
        if (!loaded) {
            cached = ClipStorage.loadGroupByTitle()
            loaded = true
        }
        return cached
    }

    fun setEnabled(enabled: Boolean) {
        ClipStorage.saveGroupByTitle(enabled)
        cached = enabled
        loaded = true
    }

    /**
     * Folder segments a clip of [content] would be written into, or empty for
     * the flat folder. Already accounts for the setting, so the export path can
     * ask this one question instead of two.
     */
    fun folderSegmentsFor(content: ClipContentRef): List<String> =
        if (isEnabled()) ClipFolderLayout.segmentsFor(content) else emptyList()

    /**
     * The path an episode clip would be written to under [enabled], for the
     * settings row.
     *
     * An episode rather than a film, because it is the case the setting has to
     * explain: one folder per film is obvious from the title alone, three
     * nested folders per episode is not. It reuses [ClipFilenameSettings]'
     * example so the row shows the user's own naming, not a second invented one.
     */
    fun preview(enabled: Boolean): String {
        val fileName = ClipFilenameSettings.preview(ClipFilenameSettings.template())
        if (!enabled) return fileName
        val folder = ClipFolderLayout.previewFor(
            ClipContentRef(title = "Severance", seasonNumber = 2, episodeNumber = 5),
        )
        return if (folder.isBlank()) fileName else "$folder/$fileName"
    }
}
