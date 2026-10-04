package com.nuvio.app.features.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Which frames a request means, and whether they are cheap to read together:
 * the part of `get_frames` that is arithmetic rather than video.
 */
class FramePlanTest {

    @Test
    fun `listed moments are one seek each, in order and without repeats`() {
        val plan = FramePlan.from(atMs = listOf(90_000, 5_000, 90_000), fromMs = null, toMs = null, count = null)
        assertIs<FramePlan.Seeks>(plan)
        assertEquals(listOf(5_000L, 90_000L), plan.timesMs)
    }

    @Test
    fun `a short run is one pass with both ends included`() {
        val plan = FramePlan.from(atMs = null, fromMs = 60_000, toMs = 62_000, count = 5)
        assertIs<FramePlan.Sweep>(plan)
        assertEquals(listOf(60_000L, 60_500L, 61_000L, 61_500L, 62_000L), plan.timesMs)
    }

    @Test
    fun `a run spread across the film is seeks, since a pass would read everything between`() {
        val plan = FramePlan.from(atMs = null, fromMs = 0, toMs = 3_600_000, count = 4)
        assertIs<FramePlan.Seeks>(plan)
        assertEquals(listOf(0L, 1_200_000L, 2_400_000L, 3_600_000L), plan.timesMs)
    }

    @Test
    fun `a step that does not divide evenly never overshoots the end`() {
        val plan = FramePlan.from(atMs = null, fromMs = 0, toMs = 1_000, count = 4)
        assertEquals(listOf(0L, 333L, 666L, 999L), plan.timesMs)
    }

    @Test
    fun `the frame count is capped rather than refused for a run`() {
        assertEquals(MaxFrames, FramePlan.from(atMs = null, fromMs = 0, toMs = 10_000, count = 500).timesMs.size)
        assertEquals(2, FramePlan.from(atMs = null, fromMs = 0, toMs = 10_000, count = 1).timesMs.size)
    }

    @Test
    fun `a contact sheet may ask for more frames than a run of full ones`() {
        assertEquals(24, FramePlan.from(atMs = null, fromMs = 0, toMs = 3_600_000, count = 24, maxFrames = 30).timesMs.size)
        assertEquals(30, FramePlan.from(atMs = null, fromMs = 0, toMs = 3_600_000, count = 99, maxFrames = 30).timesMs.size)
    }

    @Test
    fun `requests that do not say which frames are refused with the reason`() {
        assertFailsWith<McpToolException> { FramePlan.from(atMs = null, fromMs = null, toMs = null, count = null) }
        assertFailsWith<McpToolException> { FramePlan.from(atMs = null, fromMs = 5_000, toMs = 5_000, count = 3) }
        assertFailsWith<McpToolException> { FramePlan.from(atMs = listOf(1), fromMs = 0, toMs = 9, count = null) }
        assertFailsWith<McpToolException> { FramePlan.from(atMs = List(MaxFrames + 1) { it * 1_000L }, null, null, null) }
        assertFailsWith<McpToolException> { FramePlan.from(atMs = listOf(-1), fromMs = null, toMs = null, count = null) }
    }
}
