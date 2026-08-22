package com.nuvio.app.features.clip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The grouping key is what keeps one title's export progress off another
 * title's screen, so the cases where two things must NOT share a key are the
 * point of this test.
 */
class ClipContentRefTest {
    @Test
    fun `different movies never share a key`() {
        val dune = ClipContentRef(videoId = "tt1160419", title = "Dune")
        val arrival = ClipContentRef(videoId = "tt2543164", title = "Arrival")
        assertNotEquals(dune.key, arrival.key)
    }

    @Test
    fun `episodes of one series never share a key`() {
        val base = ClipContentRef(videoId = "tt11280740", title = "Severance", seasonNumber = 2)
        assertNotEquals(
            base.copy(episodeNumber = 1).key,
            base.copy(episodeNumber = 2).key,
        )
    }

    @Test
    fun `the same episode keeps one key across poster and title changes`() {
        val first = ClipContentRef(
            videoId = "tt11280740",
            title = "Severance",
            seasonNumber = 2,
            episodeNumber = 5,
            posterUrl = "https://example.com/a.jpg",
        )
        // Metadata can be enriched mid-playback; that must not orphan a running job.
        val enriched = first.copy(posterUrl = "https://example.com/b.jpg")
        assertEquals(first.key, enriched.key)
    }

    @Test
    fun `direct-url playback falls back to the title`() {
        val untitled = ClipContentRef(videoId = "", title = "Holiday footage")
        assertEquals("title:Holiday footage", untitled.key)
        assertNotEquals(untitled.key, ClipContentRef(videoId = "", title = "Other footage").key)
    }

    @Test
    fun `label carries the episode code for series only`() {
        assertEquals("Dune", ClipContentRef(videoId = "tt1160419", title = "Dune").label)
        assertEquals(
            "Severance S02E05",
            ClipContentRef(
                videoId = "tt11280740",
                title = "Severance",
                seasonNumber = 2,
                episodeNumber = 5,
            ).label,
        )
    }
}
