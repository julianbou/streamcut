package com.nuvio.app.features.clip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The folder layout decides where a file lands on the user's disk, so the cases
 * that matter are the ones that would put it somewhere surprising: a title the
 * filesystem will not take verbatim, a title that is not there at all, and the
 * ordering a file manager will apply to seasons.
 */
class ClipFolderLayoutTest {

    @Test
    fun `a film gets one folder named after it`() {
        val dune = ClipContentRef(videoId = "tt1160419", title = "Dune")
        assertEquals(listOf("Dune"), ClipFolderLayout.segmentsFor(dune))
    }

    @Test
    fun `an episode nests under its show and season`() {
        val episode = ClipContentRef(
            videoId = "tt11280740:2:5",
            title = "Severance",
            seasonNumber = 2,
            episodeNumber = 5,
        )
        assertEquals(
            listOf("Severance", "Season 02", "Episode 05"),
            ClipFolderLayout.segmentsFor(episode),
        )
    }

    @Test
    fun `episodes of one season share a folder, seasons do not`() {
        val base = ClipContentRef(videoId = "tt11280740", title = "Severance", seasonNumber = 2)
        val first = ClipFolderLayout.segmentsFor(base.copy(episodeNumber = 1))
        val second = ClipFolderLayout.segmentsFor(base.copy(episodeNumber = 2))
        val otherSeason = ClipFolderLayout.segmentsFor(
            base.copy(seasonNumber = 3, episodeNumber = 1),
        )
        assertEquals(first.take(2), second.take(2))
        assertTrue(first != second)
        assertTrue(first.take(2) != otherSeason.take(2))
    }

    @Test
    fun `season numbers are padded so a file manager sorts them in order`() {
        val base = ClipContentRef(title = "Show", episodeNumber = 1)
        val second = ClipFolderLayout.segmentsFor(base.copy(seasonNumber = 2))[1]
        val tenth = ClipFolderLayout.segmentsFor(base.copy(seasonNumber = 10))[1]
        assertEquals("Season 02", second)
        assertEquals("Season 10", tenth)
        assertTrue(second < tenth)
    }

    @Test
    fun `a specials season keeps its own folder rather than borrowing season one`() {
        val specials = ClipContentRef(title = "Show", seasonNumber = 0, episodeNumber = 3)
        assertEquals(listOf("Show", "Season 00", "Episode 03"), ClipFolderLayout.segmentsFor(specials))
    }

    @Test
    fun `a title the filesystem would refuse is sanitized, not passed through`() {
        val awkward = ClipContentRef(title = "Face/Off: the \"good\" one")
        val segments = ClipFolderLayout.segmentsFor(awkward)
        assertEquals(1, segments.size)
        assertTrue(segments.single().none { it in "/\\:*?\"<>|" }, segments.single())
        assertTrue(segments.single().isNotBlank())
    }

    @Test
    fun `a title that sanitizes away writes flat rather than into a nameless folder`() {
        assertEquals(emptyList(), ClipFolderLayout.segmentsFor(ClipContentRef(title = "///")))
        assertEquals(emptyList(), ClipFolderLayout.segmentsFor(ClipContentRef.Empty))
    }

    @Test
    fun `a direct-url playback with no title is never grouped, episode or not`() {
        val untitled = ClipContentRef(seasonNumber = 1, episodeNumber = 1)
        assertEquals(emptyList(), ClipFolderLayout.segmentsFor(untitled))
    }

    @Test
    fun `the preview reads as the path it will create`() {
        val episode = ClipContentRef(title = "Severance", seasonNumber = 2, episodeNumber = 5)
        assertEquals("Severance/Season 02/Episode 05", ClipFolderLayout.previewFor(episode))
        assertEquals("", ClipFolderLayout.previewFor(ClipContentRef.Empty))
    }
}
