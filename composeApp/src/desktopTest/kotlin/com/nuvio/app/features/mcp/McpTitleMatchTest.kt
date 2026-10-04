package com.nuvio.app.features.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The file names here are the ones a stream addon really returned for
 * Spider-Man (2002): the right film, its two sequels, and everything between.
 */
class McpTitleMatchTest {

    private fun film(fileName: String) = McpTitleMatch.mismatch(fileName, "Spider-Man", 2002, null, null)

    @Test
    fun `the film that was asked for passes, however its name is written`() {
        assertNull(film("Spider-Man 2002 REMASTERED 1080p 10bit BluRay 8CH X265 HEVC-PSA.mkv"))
        assertNull(film("Spider-Man.2002.UHD.BluRay.2160p.TrueHD.Atmos.7.1.HEVC.REMUX-FraMeSToR.mkv"))
        assertNull(film("SpiderMan 2002 720p BluRay 264 DuaL-TURKO.mkv"))
        assertNull(film("01. Spider-Man (2002) DS4K 1080p 10bit BDRip x265 [Hindi (NF) DDP 5.1 II English DDP 5.1] ESubs — PeruGuy.mkv"))
    }

    @Test
    fun `a sequel is caught by its number`() {
        assertNotNull(film("Spider-Man 2 2004 1080p BluRay DDP 5 1 10bit H 265-iVy.mkv"))
        assertNotNull(film("SpiderMan 3 2007 720p BluRay x264 DuaL-TURKO.mkv"))
        assertNotNull(McpTitleMatch.mismatch("The Godfather Part II 1974 1080p.mkv", "The Godfather", 1972, null, null))
    }

    @Test
    fun `another film that only shares the name is caught by its year`() {
        assertNotNull(film("Spider-Man.No.Way.Home.2021.1080p.WEB-DL.mkv"))
        assertNotNull(film("The Amazing Spider-Man 2012 1080p BluRay.mkv"))
    }

    @Test
    fun `a title that ends in a number is not mistaken for its own sequel`() {
        assertNull(McpTitleMatch.mismatch("Spider-Man 2 2004 1080p BluRay.mkv", "Spider-Man 2", 2004, null, null))
    }

    @Test
    fun `a name that says nothing either way is left alone`() {
        assertNull(film("Человек-паук 1080p BDRip.mkv"))
        assertNull(film("movie.mkv"))
        assertNull(film("Spider-Man 1080p BluRay x264.mkv"))
    }

    @Test
    fun `a release dated the year after is the same film`() {
        assertNull(film("Spider-Man 2003 DVDRip.avi"))
    }

    @Test
    fun `an episode is checked against the one that was asked for`() {
        assertNull(McpTitleMatch.mismatch("Severance.S01E07.Defiant.Jazz.1080p.mkv", "Severance", 2022, 1, 7))
        assertNull(McpTitleMatch.mismatch("Severance 1x07 1080p.mkv", "Severance", 2022, 1, 7))
        assertNotNull(McpTitleMatch.mismatch("Severance.S01E08.1080p.mkv", "Severance", 2022, 1, 7))
        assertNotNull(McpTitleMatch.mismatch("Severance.S02E07.1080p.mkv", "Severance", 2022, 1, 7))
        // A season pack names no episode, so there is nothing to contradict.
        assertNull(McpTitleMatch.mismatch("Severance.S01.COMPLETE.1080p", "Severance", 2022, 1, 7))
    }

    @Test
    fun `the year is read off a release label`() {
        assertEquals(2002, McpTitleMatch.yearOf("2002"))
        assertEquals(2022, McpTitleMatch.yearOf("2022-"))
        assertNull(McpTitleMatch.yearOf(null))
    }
}
