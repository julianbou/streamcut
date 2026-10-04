package com.nuvio.app.features.mcp

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cases the matcher exists for: a line broken across cues, a line
 * misremembered, and a query of ordinary words that must not match everything.
 */
class SubtitlePhraseSearchTest {

    private fun cue(startSeconds: Int, text: String) =
        SubtitleSyncCue(startTimeMs = startSeconds * 1_000L, endTimeMs = startSeconds * 1_000L + 2_000L, text = text)

    private val film = SubtitlePhraseIndex(
        listOf(
            cue(10, "What is it you want from me?"),
            cue(20, "I'm gonna make him an offer"),
            cue(23, "he can't refuse."),
            cue(40, "The weather is nice in the morning."),
            cue(50, "Leave the gun. Take the cannoli."),
            cue(60, "É uma oferta, señor."),
        ),
    )

    @Test
    fun `a line inside one cue is an exact hit with that cue's times`() {
        val hit = film.search("leave the gun").first()
        assertTrue(hit.isExact)
        assertEquals(50_000L, hit.startMs)
        assertEquals(52_000L, hit.endMs)
    }

    @Test
    fun `a line split across two cues is found as one range`() {
        val hit = film.search("make him an offer he can't refuse").first()
        assertTrue(hit.isExact)
        assertEquals(20_000L, hit.startMs)
        assertEquals(25_000L, hit.endMs)
        assertEquals("I'm gonna make him an offer he can't refuse.", hit.text)
    }

    @Test
    fun `punctuation, case, apostrophes and accents do not matter`() {
        assertEquals(23_000L, film.search("HE CANT REFUSE!").first().startMs)
        assertEquals(60_000L, film.search("e uma oferta senor").first().startMs)
    }

    @Test
    fun `a misremembered line still lands on the right moment`() {
        val hit = film.search("leave the gun take the canoli").first()
        assertFalse(hit.isExact)
        assertEquals(50_000L, hit.startMs)
    }

    @Test
    fun `ordinary words alone do not match an unrelated line`() {
        assertTrue(film.search("the president is in the building").isEmpty())
    }

    @Test
    fun `one moment is reported once`() {
        assertEquals(1, film.search("take the cannoli").size)
    }

    @Test
    fun `nothing to search yields nothing`() {
        assertTrue(film.search("   ").isEmpty())
        assertTrue(SubtitlePhraseIndex(emptyList()).search("anything").isEmpty())
    }
}
