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
 * Whether a source accepts the tuned seek options is discovered by reading
 * ffmpeg's own words, so the exact wording is worth pinning: get it wrong and
 * the strip silently builds nothing, which is what it did.
 */
class ClipStripTuningFallbackTest {

    @Test
    fun `names the option ffmpeg could not place`() {
        // Verbatim from ffmpeg 7.1 given -blocksize on an input that has no
        // protocol to consume it.
        val output = """
            Option blocksize not found.
            Error opening input file testsrc2=size=320x180:duration=1.
            Error opening input files: Option not found
        """.trimIndent()
        assertEquals("blocksize", rejectedTuningOption(output))
    }

    @Test
    fun `ignores an option this code never passes`() {
        assertEquals(null, rejectedTuningOption("Option frobnicate not found."))
    }

    @Test
    fun `an ordinary failure is not mistaken for a rejected option`() {
        assertEquals(null, rejectedTuningOption("Connection to tcp://host failed: Connection refused"))
        assertEquals(null, rejectedTuningOption(""))
    }
}
