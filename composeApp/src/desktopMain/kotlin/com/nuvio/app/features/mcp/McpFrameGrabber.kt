package com.nuvio.app.features.mcp

import com.nuvio.app.features.clip.ClipExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** A still taken from a source, and the moment it shows. */
internal class GrabbedFrame(val atMs: Long, val jpeg: ByteArray)

/** Where frames are read from: a remote stream with its request headers, or a file on disk. */
internal class FrameSource(val location: String, val headers: Map<String, String> = emptyMap())

/**
 * Stills for an assistant to look at.
 *
 * The two entry points follow the cost model measured for the filmstrip (see
 * `ClipStrip.desktop.kt`): a seek costs a couple of GOPs wherever it lands, so
 * moments far apart are one seek each, while frames packed into one short
 * window are a single pass that reads that window once. Twelve frames inside
 * thirty seconds cost a quarter as much in one pass as in twelve seeks, and
 * looking closely at one moment is exactly what this is for.
 */
internal object McpFrameGrabber {

    /** One frame per entry of [timesMs], each by its own seek. Frames that could not be read are left out. */
    suspend fun atTimes(source: FrameSource, timesMs: List<Long>, width: Int): List<GrabbedFrame> {
        val ffmpeg = requireFfmpeg()
        // A debrid link allows a handful of connections at once; more than this
        // mostly makes each of them slower.
        val slots = Semaphore(ParallelSeeks)
        return withScratchDir { dir ->
            coroutineScope {
                timesMs.mapIndexed { index, atMs ->
                    async {
                        slots.withPermit {
                            val target = File(dir, "$index.jpg")
                            run(
                                ffmpeg,
                                source,
                                listOf("-ss", seconds(atMs)),
                                listOf("-frames:v", "1", "-vf", "scale=$width:-2"),
                                target.absolutePath,
                            )
                            target.takeIf { it.length() > 0L }?.let { GrabbedFrame(atMs, it.readBytes()) }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }
    }

    /**
     * [count] frames, [stepMs] apart, starting at [fromMs], read in one pass.
     *
     * Each picture is the first source frame at or after its time -- the same
     * frame a seek to that time returns, so the two ways agree and a label is
     * never more than one frame early.
     *
     * `select` rather than the `fps` filter, which would be the obvious choice:
     * `fps` holds its last frame back until it has seen the input that follows,
     * so a run ending near the end of a file silently comes back one short.
     */
    suspend fun sweep(source: FrameSource, fromMs: Long, stepMs: Long, count: Int, width: Int): List<GrabbedFrame> {
        val ffmpeg = requireFfmpeg()
        return withScratchDir { dir ->
            run(
                ffmpeg,
                source,
                listOf("-ss", seconds(fromMs)),
                listOf(
                    // Stops the read if the source ends before the last frame;
                    // otherwise -frames:v ends it as soon as that frame is out.
                    "-t", seconds(stepMs * (count - 1) + SweepTailMs),
                    "-vf", "select=gte(t\\,selected_n*${seconds(stepMs)}),scale=$width:-2",
                    "-fps_mode", "vfr",
                    "-frames:v", count.toString(),
                    "-start_number", "0",
                ),
                File(dir, "%d.jpg").absolutePath,
            )
            (0 until count).mapNotNull { index ->
                File(dir, "$index.jpg").takeIf { it.length() > 0L }
                    ?.let { GrabbedFrame(fromMs + index * stepMs, it.readBytes()) }
            }
        }
    }

    private fun requireFfmpeg(): String = ClipExtractor.ffmpegPath()
        ?: throw McpToolException("StreamCut has no ffmpeg to read frames with. Its Settings show what is missing.")

    private suspend fun run(
        ffmpeg: String,
        source: FrameSource,
        beforeInput: List<String>,
        afterInput: List<String>,
        output: String,
    ) = runInterruptible(Dispatchers.IO) {
        val args = buildList {
            add(ffmpeg)
            add("-hide_banner")
            add("-nostdin")
            add("-loglevel"); add("error")
            ClipExtractor.headersArgumentFor(source.headers)?.let { add("-headers"); add(it) }
            // Before -i, so it is a container-index seek whose cost does not
            // grow with how far into the film it lands.
            addAll(beforeInput)
            add("-i"); add(source.location)
            add("-an"); add("-sn")
            addAll(afterInput)
            add("-q:v"); add(JpegQuality)
            add("-y"); add(output)
        }
        // Discarded rather than read: reading would block past the timeout
        // below, and whether it worked is told by the file, not the log.
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            process.waitFor(ProcessTimeoutSeconds, TimeUnit.SECONDS)
        } finally {
            // Timed out, or the request was abandoned mid-download: either
            // way nothing is waiting for this read any more.
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private suspend fun <T> withScratchDir(block: suspend (File) -> T): T {
        val dir = withContext(Dispatchers.IO) { Files.createTempDirectory("streamcut-frames").toFile() }
        return try {
            block(dir)
        } finally {
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    private fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

    private const val ParallelSeeks = 3

    /** Room after the last frame's time for the frame itself to arrive. */
    private const val SweepTailMs = 1_000L
    private const val ProcessTimeoutSeconds = 75L

    /** ffmpeg's 2-31 scale, lower is better. Enough to read on-screen text without tripling the size. */
    private const val JpegQuality = "4"
}
