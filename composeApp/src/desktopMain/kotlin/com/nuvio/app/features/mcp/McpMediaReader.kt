package com.nuvio.app.features.mcp

import com.nuvio.app.features.clip.ClipExtractor
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** What is inside a source: enough to pick the right audio, find its own subtitles and know how long it is. */
internal class StreamLayout(
    val durationMs: Long?,
    val video: Video?,
    val audio: List<AudioTrack>,
    val subtitles: List<SubtitleTrack>,
    val chapters: List<Chapter>,
) {
    class Video(val codec: String, val width: Int, val height: Int, val fps: Double?, val hdr: Boolean)

    /** [index] counts among audio streams only, which is how ffmpeg's `0:a:N` and the exporter address them. */
    class AudioTrack(
        val index: Int,
        val language: String,
        val title: String,
        val codec: String,
        val channels: Int,
        val isDefault: Boolean,
    )

    /** [index] counts among subtitle streams only. [isText] is false for picture subtitles, which have no lines to read. */
    class SubtitleTrack(
        val index: Int,
        val language: String,
        val title: String,
        val codec: String,
        val isText: Boolean,
        val isForced: Boolean,
    )

    class Chapter(val startMs: Long, val endMs: Long, val title: String)
}

/**
 * Reads what a source says about itself, rather than what an addon says about
 * it: its tracks, its own subtitles and where its shots change.
 *
 * Every read here is ranged. A probe is the container header; subtitles and
 * cuts are read for a window, by seeking with `-ss` before `-i`, so the cost is
 * the window's share of the file and not the file.
 */
internal object McpMediaReader {

    suspend fun probe(source: FrameSource): StreamLayout {
        val ffmpeg = requireFfmpeg()
        val output = run(
            buildList {
                add(ClipExtractor.ffprobePathFor(ffmpeg))
                add("-v"); add("error")
                addRemoteOptions(source)
                add("-print_format"); add("json")
                add("-show_format"); add("-show_streams"); add("-show_chapters")
                add(source.location)
            },
            timeoutSeconds = ProbeTimeoutSeconds,
        )
        return parseProbe(output)
            ?: throw McpToolException("The source did not answer a probe. The link may have expired: call list_streams again.")
    }

    /** Null when [json] is not a probe of something playable. */
    fun parseProbe(json: String): StreamLayout? {
        val root = runCatching { Json.parseToJsonElement(json.substring(json.indexOf('{'))) as? JsonObject }
            .getOrNull() ?: return null
        val streams = (root["streams"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (streams.isEmpty()) return null

        var video: StreamLayout.Video? = null
        val audio = ArrayList<StreamLayout.AudioTrack>()
        val subtitles = ArrayList<StreamLayout.SubtitleTrack>()
        for (stream in streams) {
            val codec = stream.text("codec_name")
            val tags = stream["tags"] as? JsonObject
            val disposition = stream["disposition"] as? JsonObject
            when (stream.text("codec_type")) {
                // Cover art is a video stream too; the film is the first one that is not a still.
                "video" -> if (video == null && disposition?.number("attached_pic") != 1) {
                    val transfer = stream.text("color_transfer")
                    video = StreamLayout.Video(
                        codec = codec,
                        width = stream.number("width") ?: 0,
                        height = stream.number("height") ?: 0,
                        fps = rate(stream.text("avg_frame_rate")) ?: rate(stream.text("r_frame_rate")),
                        hdr = transfer == "smpte2084" || transfer == "arib-std-b67",
                    )
                }
                "audio" -> audio += StreamLayout.AudioTrack(
                    index = audio.size,
                    language = tags.tag("language"),
                    title = tags.tag("title"),
                    codec = codec,
                    channels = stream.number("channels") ?: 0,
                    isDefault = disposition?.number("default") == 1,
                )
                "subtitle" -> subtitles += StreamLayout.SubtitleTrack(
                    index = subtitles.size,
                    language = tags.tag("language"),
                    title = tags.tag("title"),
                    codec = codec,
                    isText = !ClipExtractor.isBitmapSubtitleCodec(codec),
                    isForced = disposition?.number("forced") == 1,
                )
            }
        }
        val chapters = (root["chapters"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().mapNotNull { chapter ->
            val start = seconds(chapter.text("start_time")) ?: return@mapNotNull null
            StreamLayout.Chapter(
                startMs = start,
                endMs = seconds(chapter.text("end_time")) ?: start,
                // Many rips name a chapter after its own start time, which says nothing the time does not.
                title = (chapter["tags"] as? JsonObject).tag("title").takeUnless { ChapterClock.matches(it) }.orEmpty(),
            )
        }
        return StreamLayout(
            durationMs = seconds((root["format"] as? JsonObject)?.text("duration").orEmpty()),
            video = video,
            audio = audio,
            subtitles = subtitles,
            chapters = chapters,
        )
    }

    /**
     * The lines of one of the source's own text subtitle tracks between
     * [fromMs] and [toMs], in the source's timeline.
     *
     * These are the only subtitle timings that are right for this source by
     * construction: a subtitle file from an addon was timed for whichever
     * release its author had, and two files for one film can sit most of a
     * minute apart.
     */
    suspend fun embeddedCues(source: FrameSource, trackIndex: Int, fromMs: Long, toMs: Long): List<SubtitleSyncCue> {
        val ffmpeg = requireFfmpeg()
        // A line that began before the window still has to be found, and the
        // demuxer only hands over packets that start after the seek point.
        val seekMs = (fromMs - SubtitlePrerollMs).coerceAtLeast(0L)
        val text = withScratchFile(".srt") { target ->
            run(
                buildList {
                    add(ffmpeg)
                    add("-hide_banner"); add("-nostdin")
                    add("-loglevel"); add("error")
                    addRemoteOptions(source)
                    // The source's own clock, kept. Left to itself the SRT
                    // writer starts its file at zero: a line that falls
                    // between the keyframe the seek lands on and the point
                    // asked for comes out negative, and the writer then slides
                    // every line forward to make it zero. The lines come back
                    // late by however far that one was before the seek, which
                    // differs from one window to the next -- the same line was
                    // read 5 s apart from two windows over a real stream.
                    add("-copyts"); add("-start_at_zero")
                    add("-ss"); add(clock(seekMs))
                    add("-i"); add(source.location)
                    add("-to"); add(clock(toMs))
                    add("-map"); add("0:s:$trackIndex")
                    add("-c:s"); add("srt")
                    add("-y"); add(target.absolutePath)
                },
                timeoutSeconds = WindowTimeoutSeconds,
            )
            target.takeIf { it.length() > 0L }?.readText().orEmpty()
        }
        return PlayerSubtitleCueParser.parse(text, "embedded.srt")
            .filter { it.endTimeMs > fromMs && it.startTimeMs < toMs }
    }

    /**
     * The moments between [fromMs] and [toMs] where the picture cuts to a new
     * shot, each the time of the first frame of that shot.
     *
     * Found by ffmpeg's scene score rather than by looking: one pass over the
     * window returns every cut in it, where finding one by eye costs a run of
     * frames and the attention to compare them.
     */
    suspend fun cuts(source: FrameSource, fromMs: Long, toMs: Long, threshold: Double): List<Long> {
        val ffmpeg = requireFfmpeg()
        val log = run(
            buildList {
                add(ffmpeg)
                add("-hide_banner"); add("-nostdin")
                // showinfo reports through the log, at this level.
                add("-loglevel"); add("info")
                addRemoteOptions(source)
                add("-ss"); add(clock(fromMs))
                add("-i"); add(source.location)
                add("-t"); add(clock(toMs - fromMs))
                add("-an"); add("-sn")
                // Scored on a small picture: the score is a difference of
                // whole frames, and a cut is as plain at 320 wide as at 4K.
                add("-vf"); add("scale=320:-2,select=gt(scene\\,$threshold),showinfo")
                add("-f"); add("null"); add("-")
            },
            timeoutSeconds = WindowTimeoutSeconds,
        )
        return parseCutTimes(log, fromMs)
    }

    /** Pulls `pts_time` out of showinfo's lines, which are the only ones the selected frames produce. */
    fun parseCutTimes(log: String, fromMs: Long): List<Long> =
        log.lineSequence()
            .filter { "showinfo" in it }
            .mapNotNull { ShowinfoTime.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            .map { fromMs + (it * 1_000).toLong() }
            .toList()

    private fun MutableList<String>.addRemoteOptions(source: FrameSource) {
        // Meaningless for a file on disk, and ffprobe rejects reconnect there.
        if (!source.location.startsWith("http", ignoreCase = true)) return
        addAll(ClipExtractor.remoteReadArgsForTools())
        ClipExtractor.headersArgumentFor(source.headers)?.let { add("-headers"); add(it) }
    }

    private fun requireFfmpeg(): String = ClipExtractor.ffmpegPath()
        ?: throw McpToolException("StreamCut has no ffmpeg to read the source with. Its Settings show what is missing.")

    /** Runs to completion or [timeoutSeconds], and returns everything the process printed. */
    private suspend fun run(args: List<String>, timeoutSeconds: Long): String = withScratchFile(".log") { log ->
        runInterruptible(Dispatchers.IO) {
            // To a file rather than a pipe: a pipe has to be read while the
            // process runs, and that read cannot be given a deadline.
            val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
            try {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            log.readText()
        }
    }

    private suspend fun <T> withScratchFile(suffix: String, block: suspend (File) -> T): T {
        val file = runInterruptible(Dispatchers.IO) { Files.createTempFile("streamcut-read", suffix).toFile() }
        return try {
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun clock(ms: Long): String = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

    private fun seconds(text: String): Long? = text.toDoubleOrNull()?.let { (it * 1_000).toLong() }

    /** `24000/1001` -> 23.976. Null for ffprobe's `0/0`, which it prints when it does not know. */
    private fun rate(text: String): Double? {
        val numerator = text.substringBefore('/').toDoubleOrNull() ?: return null
        val denominator = text.substringAfter('/', "1").toDoubleOrNull() ?: return null
        return if (numerator <= 0.0 || denominator <= 0.0) null else numerator / denominator
    }

    private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.number(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    /** Tag names are written by whoever muxed the file, in whatever case they liked. */
    private fun JsonObject?.tag(name: String): String =
        this?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.let { (it.value as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

    private val ShowinfoTime = Regex("""pts_time:\s*([0-9.]+)""")
    private val ChapterClock = Regex("""\d{1,2}:\d{2}:\d{2}([.,]\d+)?""")

    private const val SubtitlePrerollMs = 10_000L
    private const val ProbeTimeoutSeconds = 25L
    private const val WindowTimeoutSeconds = 100L
}
