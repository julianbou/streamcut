package com.nuvio.app.features.clip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The order frames are fetched in is the whole of the progressive behaviour:
 * the strip has to be usable while it builds, which means every prefix of this
 * order must already cover the entire film.
 */
class ClipStripOrderTest {

    @Test
    fun `every frame is fetched exactly once`() {
        val order = ClipStrip.bisectionOrder(40)
        assertEquals(40, order.size)
        assertEquals((0 until 40).toSet(), order.toSet())
    }

    @Test
    fun `the ends come first, so the strip spans the film immediately`() {
        val order = ClipStrip.bisectionOrder(40)
        assertEquals(listOf(0, 39, 19), order.take(3))
    }

    @Test
    fun `every prefix stays spread across the film rather than bunching at one end`() {
        val count = 200
        val order = ClipStrip.bisectionOrder(count)
        for (k in listOf(4, 8, 16, 32, 64)) {
            val gap = widestUnsampledGap(order.take(k), count)
            // Roughly count/k, which is the guarantee that makes a half-built
            // strip worth scrolling: any moment in the film is within half a
            // gap of a frame you can already see.
            val allowed = 2 * count / k + 2
            assertTrue(gap <= allowed, "after $k frames the widest gap was $gap, expected <= $allowed")
        }
    }

    @Test
    fun `the spread test would reject filling in from the left`() {
        // Guards the assertion above: measured only between sampled frames,
        // sequential order looks perfect while leaving most of the film blank.
        val count = 200
        assertTrue(widestUnsampledGap((0 until 32).toList(), count) > 100)
    }

    /**
     * The largest stretch of film with no frame in it, counting the run before
     * the first sample and after the last -- which is exactly where a
     * left-to-right fill hides its emptiness.
     */
    private fun widestUnsampledGap(taken: List<Int>, count: Int): Int {
        val marks = listOf(-1) + taken.sorted() + listOf(count)
        return marks.zipWithNext { low, high -> high - low }.max()
    }

    @Test
    fun `degenerate counts do not throw`() {
        assertEquals(emptyList(), ClipStrip.bisectionOrder(0))
        assertEquals(listOf(0), ClipStrip.bisectionOrder(1))
        assertEquals(listOf(0, 1), ClipStrip.bisectionOrder(2))
    }
}


/**
 * A still costs about one GOP whatever its resolution, so the source's bitrate
 * is what decides whether a strip is a few hundred megabytes or a few
 * gigabytes. These are the cases where getting that wrong is felt.
 */
class ClipStripSpacingTest {

    private val twoHours = 7_200_000L

    @Test
    fun `a modest stream gets the finest grid`() {
        // 2 Mb/s: ~1 MB a frame, so even hundreds of frames stay well inside
        // the budget and there is no reason to coarsen beyond the floor.
        assertEquals(SPACING_LADDER.first(), ClipStrip.spacingFor(twoHours, 2_000_000L))
    }

    @Test
    fun `a coarser source always gets a coarser grid, never a finer one`() {
        val spacings = listOf(2_000_000L, 10_000_000L, 25_000_000L, 60_000_000L, 120_000_000L)
            .map { ClipStrip.spacingFor(twoHours, it) }
        assertEquals(spacings.sorted(), spacings, "spacing should not decrease as bitrate rises: $spacings")
    }

    @Test
    fun `4K coarsens rather than costing gigabytes`() {
        val spacing = ClipStrip.spacingFor(twoHours, 40_000_000L)
        assertTrue(spacing > 15_000L, "40 Mb/s should not use the finest grid, got $spacing")
        // The point of coarsening: the strip has to stay affordable.
        val frames = twoHours / spacing
        val estimatedBytes = frames * (40_000_000L / 8) * 4
        assertTrue(estimatedBytes <= 900L * 1024 * 1024, "estimated ${estimatedBytes / 1_000_000} MB")
    }

    @Test
    fun `an unreadable bitrate still produces a usable strip`() {
        val spacing = ClipStrip.spacingFor(twoHours, 0L)
        // Not the finest rung: not knowing what a frame costs is a reason to
        // fetch fewer of them, not more.
        assertTrue(spacing > SPACING_LADDER.first(), "unknown bitrate should be cautious, got $spacing")
        assertTrue(twoHours / spacing in 1..400)
    }

    @Test
    fun `spacing only ever lands on the ladder, so a title keeps its cache`() {
        // Reads the real ladder rather than restating it: a copy here would
        // pass while saying nothing the moment the ladder changed.
        val ladder = SPACING_LADDER.toSet()
        listOf(0L, 1_000_000L, 8_000_000L, 25_000_000L, 40_000_000L, 90_000_000L, 200_000_000L)
            .forEach { bitrate ->
                val spacing = ClipStrip.spacingFor(twoHours, bitrate)
                assertTrue(spacing in ladder, "bitrate $bitrate produced off-ladder spacing $spacing")
            }
    }
}
