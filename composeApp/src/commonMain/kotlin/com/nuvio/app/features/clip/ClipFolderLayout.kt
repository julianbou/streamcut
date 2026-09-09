package com.nuvio.app.features.clip

/**
 * Where a clip is filed inside the clips folder.
 *
 * Off, every clip lands in one flat folder, which is fine until you have cut
 * thirty of them: the only thing telling two titles apart is a filename prefix,
 * and the folder is unusable in a file manager. On, clips are grouped the way
 * the source material already is -- one folder per film, and a series nested
 * `Show/Season 02/Episode 05` so a season's clips sit together and an episode's
 * sit tighter still.
 *
 * The segments are deliberately **not** localized. A folder name is written to
 * disk once and read forever; if it followed the app language, changing that
 * language would start a second `Temporada 02` next to the first and split one
 * show's clips across both, with nothing to merge them but the user.
 */
internal object ClipFolderLayout {

    /**
     * Folder segments for [content], outermost first, or empty when the clip
     * should be written flat.
     *
     * Empty is returned for anything without a usable title -- a direct-URL
     * playback has none -- because a folder called `Clip` groups nothing and
     * only adds a level to click through.
     */
    fun segmentsFor(content: ClipContentRef): List<String> {
        val title = ClipFilenameTemplate.sanitize(content.title)
        if (title.isBlank()) return emptyList()

        val season = content.seasonNumber
        val episode = content.episodeNumber
        if (season == null || episode == null) return listOf(title)

        return listOf(title, seasonFolderName(season), episodeFolderName(episode))
    }

    /**
     * The same segments joined for display, e.g. `Severance/Season 02/Episode 05`.
     * Blank when the clip is written flat.
     */
    fun previewFor(content: ClipContentRef): String = segmentsFor(content).joinToString("/")

    /**
     * Padded to two digits so a file manager's alphabetical sort is also the
     * broadcast order -- `Season 10` sorts before `Season 2` otherwise.
     * Negative numbers cannot come from the catalog, but a specials season is
     * genuinely 0, and `Season 00` is the right home for it.
     */
    private fun seasonFolderName(season: Int): String =
        "Season " + season.coerceAtLeast(0).toString().padStart(2, '0')

    private fun episodeFolderName(episode: Int): String =
        "Episode " + episode.coerceAtLeast(0).toString().padStart(2, '0')
}
