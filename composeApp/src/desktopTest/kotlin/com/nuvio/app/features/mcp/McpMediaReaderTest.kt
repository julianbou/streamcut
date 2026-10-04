package com.nuvio.app.features.mcp

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What is read out of ffprobe and ffmpeg, and what is made of it, without running either. */
class McpMediaReaderTest {

    private val probe = """
        {
          "streams": [
            {"index":0,"codec_name":"hevc","codec_type":"video","width":3840,"height":2160,
             "avg_frame_rate":"24000/1001","color_transfer":"smpte2084","disposition":{"default":1,"attached_pic":0}},
            {"index":1,"codec_name":"eac3","codec_type":"audio","channels":6,
             "disposition":{"default":1},"tags":{"language":"hin","title":"Hindi DDP 5.1"}},
            {"index":2,"codec_name":"truehd","codec_type":"audio","channels":8,
             "disposition":{"default":0},"tags":{"LANGUAGE":"eng"}},
            {"index":3,"codec_name":"subrip","codec_type":"subtitle","disposition":{"forced":1},"tags":{"language":"eng"}},
            {"index":4,"codec_name":"subrip","codec_type":"subtitle","disposition":{"forced":0},"tags":{"language":"eng"}},
            {"index":5,"codec_name":"hdmv_pgs_subtitle","codec_type":"subtitle","tags":{"language":"spa"}},
            {"index":6,"codec_name":"mjpeg","codec_type":"video","width":600,"height":900,"disposition":{"attached_pic":1}}
          ],
          "chapters": [
            {"start_time":"0.000000","end_time":"312.500000","tags":{"title":"Opening"}},
            {"start_time":"312.500000","end_time":"7265.000000"}
          ],
          "format": {"duration":"7265.123000"}
        }
    """.trimIndent()

    @Test
    fun `a probe is read into tracks numbered the way ffmpeg addresses them`() {
        val layout = assertNotNull(McpMediaReader.parseProbe(probe))
        assertEquals(7_265_123L, layout.durationMs)

        val video = assertNotNull(layout.video)
        assertEquals(3840 to 2160, video.width to video.height)
        assertTrue(video.hdr)
        assertEquals(23.976, video.fps!!, 0.001)

        // Numbered among their own kind, not by container index.
        assertEquals(listOf(0 to "hin", 1 to "eng"), layout.audio.map { it.index to it.language })
        assertTrue(layout.audio[0].isDefault)
        assertEquals(listOf(0, 1, 2), layout.subtitles.map { it.index })
        assertTrue(layout.subtitles[0].isForced)
        assertTrue(layout.subtitles[1].isText)
        assertFalse(layout.subtitles[2].isText)

        assertEquals(listOf("Opening", ""), layout.chapters.map { it.title })
        assertEquals(312_500L, layout.chapters[1].startMs)
    }

    @Test
    fun `ffprobe's warnings before the JSON do not spoil it, and a failed probe is null`() {
        assertNotNull(McpMediaReader.parseProbe("[matroska] some warning\n$probe"))
        assertNull(McpMediaReader.parseProbe("Server returned 403 Forbidden"))
        assertNull(McpMediaReader.parseProbe("""{"streams":[]}"""))
    }

    @Test
    fun `cut times are read from showinfo and placed on the source's timeline`() {
        val log = """
            Input #0, matroska,webm, from 'x':
            [Parsed_showinfo_2 @ 0x600] config in time_base: 1/1000
            [Parsed_showinfo_2 @ 0x600] n:   0 pts:   4171 pts_time:4.171   duration: 41 fmt:yuv420p
            [Parsed_showinfo_2 @ 0x600] n:   1 pts:   9843 pts_time:9.843   duration: 41 fmt:yuv420p
            frame=    2 fps=0.0 q=-0.0 size=N/A time=00:00:09.84
        """.trimIndent()
        assertEquals(listOf(64_171L, 69_843L), McpMediaReader.parseCutTimes(log, fromMs = 60_000))
        assertEquals(emptyList(), McpMediaReader.parseCutTimes("frame=0", fromMs = 0))
    }

    // --- contact sheet ---

    private fun frame(atMs: Long, color: Color): GrabbedFrame {
        val image = BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = color; fillRect(0, 0, 160, 90); dispose() }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }
        return GrabbedFrame(atMs, bytes.toByteArray())
    }

    @Test
    fun `a contact sheet lays its frames out in rows and wraps the last one`() {
        val frames = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN).mapIndexed { i, c -> frame(i * 1_000L, c) }
        val sheet = assertNotNull(McpContactSheet.compose(frames, columns = 3) { "0:00:0${it / 1_000}" })
        val image = ImageIO.read(ByteArrayInputStream(sheet))
        // Three across, two down, with a gutter around every cell.
        assertEquals(3 * 160 + 4 * 4, image.width)
        assertEquals(2 * 90 + 3 * 4, image.height)
        // Far corner of the second cell, well clear of its time label: still green.
        val pixel = Color(image.getRGB(4 + 160 + 4 + 150, 4 + 80))
        assertTrue(pixel.green > 180 && pixel.red < 90, "expected the second frame's green, got $pixel")
    }

    @Test
    fun `nothing readable makes no sheet`() {
        assertNull(McpContactSheet.compose(listOf(GrabbedFrame(0, byteArrayOf(1, 2, 3))), columns = 4) { "" })
        assertNull(McpContactSheet.compose(emptyList(), columns = 4) { "" })
    }

    // --- activity log ---

    @Test
    fun `a call is one line, with long arguments cut and whitespace flattened`() {
        val line = McpActivityLog.line(
            at = LocalDateTime.of(2026, 10, 4, 3, 5, 9),
            tool = "create_clip",
            arguments = "{\"stream_id\":\"s7\",\n \"start_ms\":1000}" + " ".repeat(5) + "x".repeat(400),
            failed = false,
            tookMs = 4210,
        )
        assertTrue(line.startsWith("2026-10-04 03:05:09  ok      create_clip  4210ms  {\"stream_id\":\"s7\", \"start_ms\":1000} x"))
        assertFalse('\n' in line)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun `the log is appended to and remembers its last line`() {
        val file = File.createTempFile("mcp-activity", ".log").apply { delete() }
        try {
            val log = McpActivityLog(file)
            assertNull(log.lastLine())
            log.record("search_titles", "{}", failed = false, tookMs = 12)
            log.record("create_clip", "{}", failed = true, tookMs = 30)
            assertEquals(2, file.readLines().size)
            assertTrue("FAILED  create_clip" in log.lastLine()!!)
        } finally {
            file.delete()
        }
    }
}
