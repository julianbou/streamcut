package com.nuvio.app.features.clip

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.math.abs

/**
 * Desktop clip extraction via the system `ffmpeg` binary.
 *
 * Strategy: fast input seek (`-ss` before `-i`) + re-encode to H.264/AAC MP4.
 * Input seeking jumps cheaply to the keyframe before the start point, and
 * because we transcode, ffmpeg decodes forward and drops frames up to the
 * requested time — so cuts are frame-exact, unlike stream copy which snaps to
 * keyframes. Only the byte range covering the clip window is downloaded from
 * the remote (debrid) source.
 *
 * Encoding prefers the platform hardware encoder (VideoToolbox on macOS,
 * NVENC/QSV/AMF elsewhere) and falls back to libx264 if the hardware attempt
 * fails, so a 30s clip still exports in seconds.
 *
 * The clip keeps the audio track the viewer had selected, and an active
 * subtitle is burned into the picture -- an MP4 handed to someone else has no
 * track picker, so what is not in the frame is lost.
 */
internal actual object ClipExtractor {
    private val log = Logger.withTag("ClipExtractor")

    actual val isSupported: Boolean = true

    /**
     * Folder clips are written to. Defaults to `clips/` inside the app data
     * directory; the user can point it anywhere writable from settings.
     *
     * Resolution is deliberately per-call rather than cached: the user can
     * change the folder while a clip is queued, and an unplugged external drive
     * must fall back to the default instead of failing the export.
     */
    private val clipsDir: File
        get() {
            val custom = ClipStorage.loadOutputDir()?.let(::File)
            if (custom != null && custom.isUsableClipDir()) return custom
            return defaultClipsDir
        }

    private val defaultClipsDir: File
        get() = File(DesktopStorage.rootDir.resolve("clips").also { it.createDirectories() }.toUri())

    private fun File.isUsableClipDir(): Boolean =
        runCatching { (exists() || mkdirs()) && isDirectory && canWrite() }.getOrDefault(false)

    @Volatile
    private var cachedEncoders: Set<String>? = null

    // Keyed by path: resolveFfmpegPath asks several binaries what they can do,
    // and a single shared set would answer for the wrong one. The map is
    // already thread-safe, so it needs no @Volatile of its own.
    private val cachedFilters = ConcurrentHashMap<String, Set<String>>()

    @Volatile
    private var cachedFfmpegPath: String? = null

    actual fun start(
        request: ClipExtractRequest,
        onProgress: (fraction: Float) -> Unit,
        onSuccess: (output: ClipOutput) -> Unit,
        onFailure: (message: String) -> Unit,
    ): ClipTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val processRef = AtomicReference<Process?>(null)

        scope.launch {
            try {
                val ffmpeg = resolveFfmpegPath()
                    ?: error("ffmpeg not found. Install ffmpeg or set NUVIO_FFMPEG_PATH.")

                val startSec = request.startMs / 1000.0
                val durationSec = (request.endMs - request.startMs).coerceAtLeast(0L) / 1000.0
                if (durationSec <= 0.0) error("Clip end must be after start")

                val outFile = uniqueOutputFile(request)
                val probe = probeSource(ffmpeg, request)
                val encoders = candidateVideoEncoders(ffmpeg)
                val burn = prepareSubtitleBurn(
                    ffmpeg = ffmpeg,
                    request = request,
                    probe = probe,
                    startSec = startSec,
                    durationSec = durationSec,
                    processRef = processRef,
                )

                var lastError: String? = null
                var succeeded = false
                try {
                    for (encoder in encoders) {
                        ensureActive()
                        val args = buildFfmpegArgs(
                            ffmpeg = ffmpeg,
                            request = request,
                            startSec = startSec,
                            durationSec = durationSec,
                            outFile = outFile,
                            videoEncoder = encoder,
                            probe = probe,
                            burn = burn,
                        )
                        val code = runFfmpeg(args, processRef, durationSec, onProgress) { ensureActive() }
                        if (code == 0 && outFile.exists() && outFile.length() > 0L) {
                            succeeded = true
                            break
                        }
                        lastError = "ffmpeg ($encoder) exited with code $code"
                        outFile.delete()
                    }
                } finally {
                    burn?.tempFile?.delete()
                }
                if (!succeeded) error(lastError ?: "Clip export failed")

                onProgress(1f)
                onSuccess(describeOutput(ffmpeg, outFile, durationSec))
            } catch (cancel: CancellationException) {
                processRef.get()?.destroyForcibly()
                throw cancel
            } catch (error: Throwable) {
                processRef.get()?.destroyForcibly()
                onFailure(error.message ?: "Clip extraction failed")
            }
        }

        return object : ClipTaskHandle {
            override fun cancel() {
                processRef.get()?.destroyForcibly()
                job.cancel()
            }
        }
    }

    /**
     * Probed frame rates by source URL. A rate is a property of the file, so one
     * probe per source is enough for the life of the process -- and for a remote
     * source the probe is an HTTP round trip, which the trim UI must not repeat
     * every time the player re-renders.
     */
    private val frameRateCache = ConcurrentHashMap<String, Double>()

    actual suspend fun probeFrameRate(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
    ): Double {
        if (sourceUrl.isBlank()) return 0.0
        frameRateCache[sourceUrl]?.let { return it }
        val rate = withContext(Dispatchers.IO) {
            runCatching {
                val ffmpeg = resolveFfmpegPath() ?: return@runCatching 0.0
                val args = mutableListOf(ffprobePath(ffmpeg), "-v", "error")
                headersArgument(sourceHeaders)?.let { headers ->
                    args += "-headers"
                    args += headers
                }
                args += listOf(
                    "-select_streams", "v:0",
                    "-show_entries", "stream=avg_frame_rate,r_frame_rate",
                    "-of", "default=noprint_wrappers=1",
                    sourceUrl,
                )
                val process = ProcessBuilder(args).redirectErrorStream(false).start()
                val output = process.inputStream.bufferedReader().readText()
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@runCatching 0.0
                }
                parseFrameRate(output)
            }.getOrElse { 0.0 }
        }
        if (rate > 0.0) frameRateCache[sourceUrl] = rate
        return rate
    }

    /**
     * ffprobe reports rates as rationals -- `24000/1001`, or `0/0` when the
     * container does not say. The division happens here at full precision: 23.976
     * rounded to three places drifts a whole frame within a few minutes.
     */
    private fun parseFrameRate(output: String): Double {
        val fields = output.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator).trim() to
                    line.substring(separator + 1).trim()
            }
            .toMap()
        // avg_frame_rate first: r_frame_rate is the smallest rate that ticks on
        // every frame, which comes back doubled on telecined or interlaced input.
        for (key in listOf("avg_frame_rate", "r_frame_rate")) {
            val parts = fields[key]?.split('/') ?: continue
            val numerator = parts.getOrNull(0)?.toDoubleOrNull() ?: continue
            val denominator = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
            if (numerator <= 0.0 || denominator <= 0.0) continue
            val rate = numerator / denominator
            // Outside this band it is a misparse, not a real film.
            if (rate >= 1.0 && rate <= 480.0) return rate
        }
        return 0.0
    }

    actual suspend fun toolStatus(): ClipToolStatus? = withContext(Dispatchers.IO) {
        val ffmpeg = resolveFfmpegPath() ?: return@withContext ClipToolStatus(null, "", false, false, null)
        val version = runCatching {
            val process = ProcessBuilder(ffmpeg, "-hide_banner", "-version").redirectErrorStream(true).start()
            val first = process.inputStream.bufferedReader().readLine().orEmpty()
            process.waitFor(10, TimeUnit.SECONDS)
            first
        }.getOrNull()
            // Not runnable at all: report it as missing rather than as a toolchain
            // that merely lacks features.
            ?: return@withContext ClipToolStatus(null, "", false, false, null)
        ClipToolStatus(
            path = ffmpeg,
            version = version.removePrefix("ffmpeg version ").substringBefore(" Copyright").trim(),
            burnInSubtitles = hasFilter(ffmpeg, "subtitles"),
            hdrTonemap = hasFilter(ffmpeg, "zscale"),
            hardwareEncoder = candidateVideoEncoders(ffmpeg).firstOrNull { it != "libx264" },
        )
    }

    actual fun reveal(outputFileUri: String) {
        runCatching {
            val file = File(java.net.URI(outputFileUri))
            val target = if (file.exists()) file else file.parentFile ?: return
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val command = when {
                os.contains("mac") ->
                    if (target.isFile) listOf("open", "-R", target.absolutePath)
                    else listOf("open", target.absolutePath)
                os.contains("win") ->
                    if (target.isFile) listOf("explorer.exe", "/select,", target.absolutePath)
                    else listOf("explorer.exe", target.absolutePath)
                else -> listOf("xdg-open", (if (target.isFile) target.parentFile else target).absolutePath)
            }
            ProcessBuilder(command).start()
        }
    }

    actual fun openFile(outputFileUri: String) {
        runCatching {
            val file = fileFor(outputFileUri) ?: return
            if (!file.exists()) return
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val command = when {
                os.contains("mac") -> listOf("open", file.absolutePath)
                os.contains("win") -> listOf("cmd", "/c", "start", "", file.absolutePath)
                else -> listOf("xdg-open", file.absolutePath)
            }
            ProcessBuilder(command).start()
        }
    }

    actual fun exists(outputFileUri: String): Boolean =
        fileFor(outputFileUri)?.exists() == true

    /**
     * Sends a clip to the system trash, falling back to an outright delete only
     * where the platform has no trash to send it to.
     *
     * Clips are minutes of hunting each and the delete button sits next to the
     * others on a card; the wrong one gets clicked eventually, and unlinking the
     * file would make that unrecoverable. The trash is the undo.
     */
    actual fun deleteFile(outputFileUri: String) {
        runCatching {
            val file = fileFor(outputFileUri) ?: return
            if (!file.exists()) return
            val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            if (desktop != null && desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                if (desktop.moveToTrash(file)) return
                log.w { "moveToTrash refused ${file.name}; deleting outright" }
            }
            file.delete()
        }
    }

    actual fun filePathOf(fileUri: String): String =
        runCatching { fileFor(fileUri)?.absolutePath.orEmpty() }.getOrDefault("")

    actual fun outputDirFreeBytes(): Long =
        runCatching { clipsDir.usableSpace }.getOrDefault(0L)

    actual fun outputDirPath(): String = runCatching { clipsDir.absolutePath }.getOrDefault("")

    actual fun defaultOutputDirPath(): String =
        runCatching { defaultClipsDir.absolutePath }.getOrDefault("")

    actual fun setOutputDirPath(path: String?): Boolean {
        val trimmed = path?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) {
            ClipStorage.saveOutputDir(null)
            return true
        }
        val target = File(trimmed)
        if (!target.isUsableClipDir()) return false
        // Store the resolved path so a relative entry cannot follow the working
        // directory around between launches.
        ClipStorage.saveOutputDir(target.absolutePath)
        return true
    }

    private fun fileFor(outputFileUri: String): File? = runCatching {
        if (outputFileUri.startsWith("file:")) File(java.net.URI(outputFileUri)) else File(outputFileUri)
    }.getOrNull()

    private fun runFfmpeg(
        args: List<String>,
        processRef: AtomicReference<Process?>,
        durationSec: Double,
        onProgress: (Float) -> Unit,
        ensureActive: () -> Unit,
    ): Int {
        val process = ProcessBuilder(args)
            .redirectErrorStream(false)
            .start()
        processRef.set(process)

        // ffmpeg writes progress to stderr as "time=HH:MM:SS.xx".
        process.errorStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                ensureActive()
                val seconds = parseFfmpegTimeSeconds(line) ?: continue
                onProgress((seconds / durationSec).coerceIn(0.0, 1.0).toFloat())
            }
        }
        return process.waitFor()
    }

    private fun buildFfmpegArgs(
        ffmpeg: String,
        request: ClipExtractRequest,
        startSec: Double,
        durationSec: Double,
        outFile: File,
        videoEncoder: String,
        probe: SourceProbe,
        burn: SubtitleBurn?,
    ): List<String> {
        val args = mutableListOf(ffmpeg, "-hide_banner", "-nostdin")
        // Robustness for remote (debrid/HTTP) sources.
        args += remoteReadArgs()

        // Forward the same request headers the player uses, if any.
        headersArgument(request.sourceHeaders)?.let { headers ->
            args += "-headers"
            args += headers
        }

        // Fast seek: -ss before -i jumps to the keyframe before the start point;
        // the transcode then decodes forward to the exact requested frame.
        args += "-ss"
        args += formatSeconds(startSec)
        args += "-i"
        args += request.sourceUrl
        // Output duration relative to the seek point.
        args += "-t"
        args += formatSeconds(durationSec)

        // The audio the viewer was hearing, not whatever comes first in the
        // file: a dual-language release plays its original track first, and a
        // clip that silently switches language is the wrong clip.
        val audioStream = resolveAudioStreamIndex(request.audioTrackIndex, probe)

        // Tonemap, then crop, then whatever draws on top. Cropping first would
        // reshape a picture still in HDR, and subtitles must come last or they
        // get cropped away at the edges and tone-curved along with the frame.
        val prefilter = listOfNotNull(tonemapChain(ffmpeg, probe), cropFilter(request.aspect))
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
        if (burn == null) {
            // No graph to join, so the chain rides on -vf and the video is still
            // mapped straight off the input.
            if (prefilter != null) args += listOf("-vf", prefilter)
            args += listOf("-map", "0:v:0")
        } else {
            // Burned-in subtitles come out of a filtergraph, so the video is
            // mapped from its output label instead of straight off the input.
            args += listOf(
                "-filter_complex",
                composeVideoGraph(prefilter, burn.filterGraph),
                "-map",
                "[$BURN_OUTPUT_LABEL]",
            )
        }
        // Subtitle/data streams are never muxed in: the MP4 muxer chokes on
        // image subs, and anything that survives here is already in the frame.
        // -sn is skipped when the graph is rendering the source's subtitles, so
        // there is no chance of it disowning the stream the overlay feeds on.
        args += listOf("-map", "0:a:$audioStream?")
        if (burn?.readsSourceSubtitles != true) args += "-sn"
        args += "-dn"

        args += encoderArgs(
            videoEncoder,
            probe.height,
            targetVideoBitrateBps(request.targetSizeMb, durationSec),
        )
        // 8-bit 4:2:0 so 10-bit HEVC sources produce a universally playable file.
        // Redundant when the tonemap chain ran -- it ends in yuv420p -- but this
        // is also what covers a 10-bit SDR source, which needs no tone curve.
        args += listOf("-pix_fmt", "yuv420p")
        // Stereo AAC plays everywhere (sources are often DTS/TrueHD/5.1).
        args += listOf("-c:a", "aac", "-b:a", "${AUDIO_BITRATE_BPS / 1000}k", "-ac", "2")

        args += "-avoid_negative_ts"
        args += "make_zero"
        // Make the mp4 immediately seekable/streamable.
        args += "-movflags"
        args += "+faststart"
        args += "-y"
        args += outFile.absolutePath
        return args
    }

    private fun encoderArgs(
        videoEncoder: String,
        sourceHeight: Int?,
        targetBitrateBps: Int?,
    ): List<String> {
        // A size cap turns every encoder into a bitrate-driven one: CRF aims at
        // a quality level and will happily overshoot the file size to hold it.
        if (targetBitrateBps != null) {
            val bps = targetBitrateBps
            return listOf(
                "-c:v", videoEncoder,
                "-b:v", "$bps",
                "-maxrate", "${bps * 3 / 2}",
                "-bufsize", "${bps * 2}",
            )
        }
        if (videoEncoder == "libx264") {
            return listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "18")
        }
        // Hardware encoders are bitrate-driven; scale the target to the source size.
        val mbps = when {
            sourceHeight == null -> 10
            sourceHeight >= 2000 -> 20
            sourceHeight >= 1300 -> 14
            sourceHeight >= 1000 -> 10
            sourceHeight >= 700 -> 6
            else -> 4
        }
        return listOf(
            "-c:v", videoEncoder,
            "-b:v", "${mbps}M",
            "-maxrate", "${mbps * 3 / 2}M",
            "-bufsize", "${mbps * 2}M",
        )
    }

    /**
     * Video filter chain that brings an HDR source down to SDR, or null when the
     * source is already SDR or this ffmpeg cannot do it.
     *
     * Without this, `-pix_fmt yuv420p` alone just truncates PQ-encoded BT.2020
     * to 8-bit BT.709 and reinterprets the numbers: highlights clip, everything
     * else lands far too dark, and the whole clip reads as washed-out grey. The
     * chain instead linearises the signal, maps the primaries, applies a real
     * tone curve, and re-encodes to BT.709.
     *
     * `zscale` needs an ffmpeg built with libzimg, which Homebrew's is not; the
     * export falls back to the old behaviour rather than failing, since a
     * flat-looking clip still beats no clip. See the build notes for a build
     * that has it -- the same one needed for subtitle burn-in.
     */
    private fun tonemapChain(ffmpeg: String, probe: SourceProbe): String? {
        if (!probe.isHdr) return null
        if (!hasFilter(ffmpeg, "zscale") || !hasFilter(ffmpeg, "tonemap")) {
            log.w { "HDR source but this ffmpeg cannot tonemap (needs libzimg); exporting untonemapped" }
            return null
        }
        return listOf(
            // npl=100 is the reference SDR display the curve is aimed at.
            "zscale=transfer=linear:npl=100",
            // tonemap works in linear light and wants float; gbrpf32le is the
            // format it is documented against.
            "format=gbrpf32le",
            "zscale=primaries=bt709",
            // hable keeps highlight detail rather than clipping it; desat=0
            // because the default desaturation is what makes skies look grey.
            "tonemap=tonemap=hable:desat=0",
            "zscale=transfer=bt709:matrix=bt709:range=tv",
            "format=yuv420p",
        ).joinToString(",")
    }

    /**
     * Describes the finished clip: a still from its midpoint, its size on disk,
     * and the dimensions it actually came out at.
     *
     * Everything is read from the local output rather than the source. That
     * costs no network, it is fast because the file is small and local, and the
     * still is the honest one -- already tonemapped, cropped and subtitled, so
     * the card shows what the clip really looks like.
     *
     * Best-effort throughout: a clip with no still is still a clip, so nothing
     * here is allowed to fail an export that already succeeded.
     */
    private fun describeOutput(ffmpeg: String, outFile: File, durationSec: Double): ClipOutput {
        val dimensions = runCatching {
            val args = listOf(
                ffprobePath(ffmpeg), "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-of", "default=noprint_wrappers=1",
                outFile.absolutePath,
            )
            val process = ProcessBuilder(args).redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching 0 to 0
            }
            val fields = output.lineSequence().mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator).trim() to
                    line.substring(separator + 1).trim()
            }.toMap()
            (fields["width"]?.toIntOrNull() ?: 0) to (fields["height"]?.toIntOrNull() ?: 0)
        }.getOrElse { 0 to 0 }

        return ClipOutput(
            fileUri = outFile.toURI().toString(),
            fileName = outFile.name,
            thumbnailUri = captureThumbnail(ffmpeg, outFile, durationSec),
            fileSizeBytes = runCatching { outFile.length() }.getOrDefault(0L),
            width = dimensions.first,
            height = dimensions.second,
        )
    }

    /**
     * Grabs a still from the middle of [outFile], or "" if it cannot.
     *
     * The midpoint rather than the first frame: clips often start on a cut, and
     * a black frame tells you nothing about which clip this is -- which is the
     * entire job of the picture on the card.
     *
     * Stills go to the cache directory, not next to the clips. They are
     * regenerable, and the clips folder is somewhere the user opens and shares.
     */
    private fun captureThumbnail(ffmpeg: String, outFile: File, durationSec: Double): String =
        runCatching {
            val target = File(thumbnailDir, "${thumbnailStem(outFile)}.jpg")
            val args = listOf(
                ffmpeg, "-hide_banner", "-nostdin",
                "-ss", formatSeconds(durationSec / 2.0),
                "-i", outFile.absolutePath,
                "-frames:v", "1",
                // -2 keeps the height even without needing to know the aspect,
                // which a shape crop has already changed.
                "-vf", "scale=480:-2",
                "-q:v", "4",
                "-y", target.absolutePath,
            )
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            // Drained, not ignored: a full pipe buffer would deadlock the wait.
            process.inputStream.bufferedReader().readText()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching ""
            }
            if (target.exists() && target.length() > 0L) target.toURI().toString() else ""
        }.getOrDefault("")

    private val thumbnailDir: File
        get() = File(DesktopStorage.cacheDir.resolve("clip-thumbs").also { it.createDirectories() }.toUri())

    /**
     * Stable per output path, so re-exporting to the same file replaces its
     * still instead of leaving the old one orphaned. Hashed because a clip file
     * name carries a title, and titles carry characters a path should not.
     */
    private fun thumbnailStem(outFile: File): String =
        Integer.toHexString(outFile.absolutePath.hashCode())

    /**
     * Centre-crop to [aspect], or null to leave the frame alone.
     *
     * Written as ffmpeg expressions rather than computed here, so it holds for
     * whatever the source turns out to be without a second probe: the crop takes
     * the largest rectangle of the requested shape that fits inside the frame,
     * and `crop` centres by default. Both sides are forced even -- yuv420p
     * subsamples chroma by two, and an odd dimension is rejected outright.
     */
    private fun cropFilter(aspect: ClipAspect): String? {
        val ratio = aspect.ratio ?: return null
        val w = "trunc(min(iw\\,ih*$ratio)/2)*2"
        val h = "trunc(min(ih\\,iw/$ratio)/2)*2"
        return "crop=w=$w:h=$h"
    }

    /**
     * Video bitrate that lands the file near [targetSizeMb], or null for none.
     *
     * The cap is on the whole file, so the audio track and roughly 2% of muxing
     * overhead come off the top before the rest is spread over the runtime. A
     * short clip therefore gets a generous bitrate from the same cap that
     * squeezes a long one, which is the point of expressing it as a size.
     */
    private fun targetVideoBitrateBps(targetSizeMb: Int, durationSec: Double): Int? {
        if (targetSizeMb <= 0 || durationSec <= 0.0) return null
        // Megabytes as the file manager counts them, which is what someone
        // checking against an upload limit is comparing with.
        val totalBits = targetSizeMb.toLong() * 1_000_000L * 8L
        val audioBits = (AUDIO_BITRATE_BPS * durationSec).toLong()
        val videoBits = (totalBits * 97 / 100) - audioBits
        val bps = (videoBits / durationSec).toLong()
        // Floor: below this the cap was unreachable and the picture would be a
        // grey smear -- an oversized clip beats an unwatchable one. Ceiling: a
        // generous cap on a two-second clip works out to an absurd bitrate, and
        // maxrate/bufsize are derived from this, so it has to stay well inside
        // Int range.
        return bps.coerceIn(MIN_VIDEO_BITRATE_BPS, MAX_VIDEO_BITRATE_BPS).toInt()
    }

    /**
     * Splices the tonemap in ahead of a subtitle burn.
     *
     * Order matters: subtitles are authored for SDR, so drawing them first and
     * tonemapping afterwards would drag the text down the same curve as the
     * picture and leave it grey. Every burn graph starts from `[0:v:0]`, so the
     * tonemap takes that input and the burn reads its output instead.
     */
    private fun composeVideoGraph(prefilter: String?, burnGraph: String): String {
        if (prefilter == null) return burnGraph
        return "[0:v:0]$prefilter[$TONEMAP_OUTPUT_LABEL];" +
            burnGraph.replaceFirst("[0:v:0]", "[$TONEMAP_OUTPUT_LABEL]")
    }

    /** Hardware encoder (when the ffmpeg build has one), then libx264 as fallback. */
    private fun candidateVideoEncoders(ffmpeg: String): List<String> {
        val available = availableEncoders(ffmpeg)
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val preferred = when {
            os.contains("mac") -> listOf("h264_videotoolbox")
            os.contains("win") -> listOf("h264_nvenc", "h264_qsv", "h264_amf")
            // VAAPI needs device/filter plumbing this arg builder doesn't do, so
            // Linux tries the self-contained encoders only.
            else -> listOf("h264_nvenc", "h264_qsv")
        }
        val hardware = preferred.firstOrNull { it in available }
        return listOfNotNull(hardware, "libx264").distinct()
    }

    private fun availableEncoders(ffmpeg: String): Set<String> {
        cachedEncoders?.let { return it }
        val encoders = runCatching {
            val process = ProcessBuilder(ffmpeg, "-hide_banner", "-encoders")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            Regex("""\b(h264_[a-z0-9_]+|libx264)\b""").findAll(output).map { it.value }.toSet()
        }.getOrDefault(emptySet())
        cachedEncoders = encoders
        return encoders
    }

    /**
     * What the source's stream layout looks like, as far as the export needs to
     * know: [height] picks the hardware-encoder bitrate, [audioStreamCount]
     * keeps a stale track selection from mapping a stream that is not there,
     * and [subtitleCodecs] decides how a subtitle gets burned in.
     *
     * Every field is best-effort -- a probe that fails leaves them null/empty
     * and each caller falls back to something sane.
     */
    private data class SourceProbe(
        val height: Int? = null,
        val audioStreamCount: Int? = null,
        val subtitleCodecs: List<String> = emptyList(),
        val colorTransfer: String = "",
        val colorPrimaries: String = "",
    ) {
        /**
         * True for HDR10/HDR10+/Dolby Vision (PQ) and HLG.
         *
         * Read off the transfer function rather than the primaries: a BT.2020
         * SDR master exists and needs no tone curve, while the transfer
         * characteristic is what actually makes the picture unviewable when it
         * is thrown away.
         */
        val isHdr: Boolean
            get() = colorTransfer == "smpte2084" || colorTransfer == "arib-std-b67"
    }

    /**
     * Reads the source's stream layout with ffprobe -- a small ranged read of
     * the container header. Best-effort: an empty probe on any failure.
     */
    private fun probeSource(ffmpeg: String, request: ClipExtractRequest): SourceProbe = runCatching {
        val args = mutableListOf(ffprobePath(ffmpeg), "-v", "error")
        headersArgument(request.sourceHeaders)?.let { headers ->
            args += "-headers"
            args += headers
        }
        args += listOf(
            "-show_entries", "stream=codec_type,codec_name,height,color_transfer,color_primaries",
            "-of", "default=noprint_wrappers=0",
            request.sourceUrl,
        )
        val process = ProcessBuilder(args).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching SourceProbe()
        }
        parseProbeOutput(output)
    }.getOrElse { SourceProbe() }

    /**
     * Parses ffprobe's `[STREAM] key=value [/STREAM]` blocks. Stream order is
     * file order, which is exactly the order ffmpeg's `0:a:N` / `0:s:N`
     * selectors count in -- and the order mpv's track list is built in, so the
     * player's track indexes address the same streams here.
     */
    private fun parseProbeOutput(output: String): SourceProbe {
        var height: Int? = null
        var audioStreams = 0
        var colorTransfer = ""
        var colorPrimaries = ""
        val subtitleCodecs = mutableListOf<String>()
        for (block in output.split("[STREAM]").drop(1)) {
            val fields = block.lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator).trim() to
                        line.substring(separator + 1).trim()
                }
                .toMap()
            when (fields["codec_type"]) {
                "video" -> if (height == null) {
                    height = fields["height"]?.toIntOrNull()
                    // ffprobe prints "unknown" for an untagged stream; treated as
                    // SDR, which is the safe reading -- an untagged HDR master is
                    // rare, and tonemapping an SDR picture washes it out.
                    colorTransfer = fields["color_transfer"].orEmpty().takeIf { it != "unknown" }.orEmpty()
                    colorPrimaries = fields["color_primaries"].orEmpty().takeIf { it != "unknown" }.orEmpty()
                }
                "audio" -> audioStreams++
                "subtitle" -> subtitleCodecs += fields["codec_name"].orEmpty()
            }
        }
        return SourceProbe(height, audioStreams, subtitleCodecs, colorTransfer, colorPrimaries)
    }

    /** ffprobe next to the resolved ffmpeg, falling back to PATH. */
    private fun ffprobePath(ffmpeg: String): String {
        val name = if (ffmpeg.endsWith(".exe", ignoreCase = true)) "ffprobe.exe" else "ffprobe"
        return File(ffmpeg).parentFile?.resolve(name)?.takeIf { it.canExecute() }?.absolutePath ?: "ffprobe"
    }

    /**
     * The audio stream to map. A selection past the end of the probed list is
     * dropped rather than trusted -- that only happens when the player's track
     * list and the file have drifted apart, and stream 0 at least has sound.
     */
    private fun resolveAudioStreamIndex(requested: Int, probe: SourceProbe): Int {
        if (requested < 0) return 0
        val count = probe.audioStreamCount ?: return requested
        return if (requested < count) requested else 0
    }

    // --- burned-in subtitles ---

    /** Filtergraph label the burned video leaves on, mapped as the output's video stream. */
    private const val BURN_OUTPUT_LABEL = "vout"

    /** Label the tonemapped picture leaves on, before anything is drawn over it. */
    private const val TONEMAP_OUTPUT_LABEL = "vtm"

    /** Audio bitrate, also subtracted from a size cap before the video gets the rest. */
    private const val AUDIO_BITRATE_BPS = 192_000

    /** Floor for a size-capped encode; below this there is no clip worth having. */
    private const val MIN_VIDEO_BITRATE_BPS = 150_000L

    /** Ceiling for the same: past this the cap is not what is limiting the file. */
    private const val MAX_VIDEO_BITRATE_BPS = 200_000_000L

    /**
     * How far before the in-point subtitle events are collected. A line that is
     * already on screen when the clip starts was extracted long before the cut,
     * so without a run-up it would be missing from the very frames it belongs to.
     */
    private const val SUBTITLE_PREROLL_SEC = 10.0

    /** Codecs the `subtitles` filter cannot render: these are pictures, not text. */
    private val bitmapSubtitleCodecs = setOf(
        "hdmv_pgs_subtitle",
        "dvd_subtitle",
        "dvb_subtitle",
        "xsub",
    )

    /**
     * A prepared subtitle burn: the graph to hand `-filter_complex`, plus the
     * scratch file it reads, which the caller deletes once encoding is done.
     */
    private data class SubtitleBurn(
        val filterGraph: String,
        val tempFile: File?,
        /** True when the graph reads the source's own subtitle stream (sub2video). */
        val readsSourceSubtitles: Boolean = false,
    )

    /**
     * Works out how the requested subtitle gets into the picture, doing any
     * preparation the graph needs up front.
     *
     * Text subtitles are pulled into a local `.ass` first: libavfilter's
     * `subtitles` filter demuxes the file it is given from the very beginning,
     * which over a remote source would mean downloading everything up to the
     * clip. A short extraction pass over the clip window costs one ranged read
     * instead.
     *
     * Image subtitles (PGS, VobSub) cannot be rendered by that filter at all,
     * so they are overlaid straight from the source stream in the same pass.
     *
     * Returns null when there is nothing to draw -- no subtitle was asked for,
     * or none was on screen during the window. Anything that went wrong throws
     * instead, because a clip that quietly lost its subtitles is not the clip
     * the viewer asked for.
     */
    private fun prepareSubtitleBurn(
        ffmpeg: String,
        request: ClipExtractRequest,
        probe: SourceProbe,
        startSec: Double,
        durationSec: Double,
        processRef: AtomicReference<Process?>,
    ): SubtitleBurn? {
        val delaySec = (request.subtitle?.delayMs ?: 0) / 1000.0
        return when (val selection = request.subtitle) {
            null -> null

            is ClipSubtitleSelection.Embedded -> {
                val codec = probe.subtitleCodecs.getOrNull(selection.trackIndex)
                if (probe.subtitleCodecs.isNotEmpty() && codec == null) {
                    // The player and the file disagree about how many subtitle
                    // streams there are. Better to say so than to quietly hand
                    // back a clip with nothing burned into it.
                    error("The selected subtitle track is not in the source stream")
                }
                if (codec in bitmapSubtitleCodecs) {
                    // sub2video: ffmpeg rasterizes the subtitle stream into frames
                    // the overlay filter can composite, timed by the same seek as
                    // the video, so no shifting is involved. This works for image
                    // subtitles only -- sub2video ignores text rects, which is why
                    // everything else goes the libass route below.
                    SubtitleBurn(
                        filterGraph = "[0:v:0][0:s:${selection.trackIndex}]overlay[$BURN_OUTPUT_LABEL]",
                        tempFile = null,
                        readsSourceSubtitles = true,
                    )
                } else {
                    requireSubtitleFilter(ffmpeg)
                    val preroll = minOf(SUBTITLE_PREROLL_SEC, startSec)
                    val temp = createSubtitleTempFile()
                    val extracted = runSubtitlePass(
                        args = buildList {
                            add(ffmpeg)
                            add("-hide_banner")
                            add("-nostdin")
                            addAll(remoteReadArgs())
                            headersArgument(request.sourceHeaders)?.let {
                                add("-headers")
                                add(it)
                            }
                            add("-ss")
                            add(formatSeconds(startSec - preroll))
                            add("-i")
                            add(request.sourceUrl)
                            add("-t")
                            add(formatSeconds(durationSec + preroll))
                            add("-map")
                            add("0:s:${selection.trackIndex}")
                            // ASS in, ASS out: copying keeps the styling the
                            // source authored. Anything else has to be converted.
                            add("-c:s")
                            add(if (codec == "ass" || codec == "ssa") "copy" else "ass")
                            add("-y")
                            add(temp.absolutePath)
                        },
                        processRef = processRef,
                        temp = temp,
                    )
                    when (extracted) {
                        SubtitlePassResult.Failed -> error("Could not read the subtitle track from the source")
                        SubtitlePassResult.Empty -> null
                        SubtitlePassResult.Ok ->
                            SubtitleBurn(burnFilterGraph(temp, preroll - delaySec), temp)
                    }
                }
            }

            is ClipSubtitleSelection.External -> {
                requireSubtitleFilter(ffmpeg)
                val temp = createSubtitleTempFile()
                val fetched = runSubtitlePass(
                    args = listOf(
                        ffmpeg, "-hide_banner", "-nostdin",
                        // The source's headers belong to the video host and are
                        // not sent to whoever is serving the subtitle.
                        "-i", selection.url,
                        "-c:s", "ass",
                        "-y", temp.absolutePath,
                    ),
                    processRef = processRef,
                    temp = temp,
                )
                // A sidecar file is timed against the whole video, so the burn
                // has to look up the clip's frames at their original positions.
                when (fetched) {
                    SubtitlePassResult.Failed -> error("Could not download the selected subtitle")
                    SubtitlePassResult.Empty -> null
                    SubtitlePassResult.Ok ->
                        SubtitleBurn(burnFilterGraph(temp, startSec - delaySec), temp)
                }
            }
        }
    }

    /**
     * Renders [subtitleFile] onto the video. The clip's own timeline starts at
     * zero while the subtitle file's does not, so the picture is walked forward
     * by [offsetSec] into the subtitle's timeline for the lookup and walked
     * back again before it reaches the encoder.
     */
    private fun burnFilterGraph(subtitleFile: File, offsetSec: Double): String {
        val burn = "subtitles=filename=${filterPathArgument(subtitleFile)}"
        if (abs(offsetSec) < 0.001) return "[0:v:0]$burn[$BURN_OUTPUT_LABEL]"
        val sign = if (offsetSec > 0) "+" else "-"
        val magnitude = formatSeconds(abs(offsetSec))
        val inverse = if (offsetSec > 0) "-" else "+"
        return "[0:v:0]setpts=PTS$sign$magnitude/TB,$burn,setpts=PTS$inverse$magnitude/TB[$BURN_OUTPUT_LABEL]"
    }

    /**
     * Escapes a path for a filtergraph argument. Windows drive letters are the
     * reason: an unescaped `C:` reads as the end of the filter's argument list.
     */
    private fun filterPathArgument(file: File): String {
        val escaped = file.absolutePath
            .replace('\\', '/')
            .replace("'", "\\'")
            .replace(":", "\\:")
        return "'$escaped'"
    }

    private fun createSubtitleTempFile(): File =
        File.createTempFile("nuvio-clip-", ".ass").apply { deleteOnExit() }

    /**
     * How a subtitle preparation pass ended. [Empty] and [Failed] are kept
     * apart on purpose: a window with no dialogue in it is a perfectly good
     * clip, while a pass that broke is something the viewer asked for and did
     * not get.
     */
    private enum class SubtitlePassResult { Ok, Empty, Failed }

    private fun runSubtitlePass(
        args: List<String>,
        processRef: AtomicReference<Process?>,
        temp: File,
    ): SubtitlePassResult {
        val result = runCatching {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            processRef.set(process)
            // Drained rather than ignored: a full pipe buffer would deadlock the pass.
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(SUBTITLE_PASS_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                log.w { "Subtitle pass timed out" }
                return@runCatching SubtitlePassResult.Failed
            }
            if (process.exitValue() != 0) {
                log.w { "Subtitle pass failed: ${output.takeLast(400)}" }
                return@runCatching SubtitlePassResult.Failed
            }
            // The ASS muxer writes its header either way, so the events are
            // what say whether anything was on screen during the window.
            if (temp.exists() && hasDialogue(temp)) {
                SubtitlePassResult.Ok
            } else {
                SubtitlePassResult.Empty
            }
        }.getOrElse { error ->
            log.w(error) { "Subtitle pass could not run" }
            SubtitlePassResult.Failed
        }
        if (result != SubtitlePassResult.Ok) temp.delete()
        return result
    }

    private fun hasDialogue(subtitleFile: File): Boolean = runCatching {
        subtitleFile.useLines { lines -> lines.any { it.startsWith("Dialogue:") } }
    }.getOrDefault(false)

    /**
     * Rendering text subtitles is libass' job, and plenty of ffmpeg builds are
     * compiled without it. Failing here says so, instead of letting the encode
     * die on a missing filter -- and the clip row's Subtitles toggle is the way
     * out for anyone who just wants the clip.
     */
    private fun requireSubtitleFilter(ffmpeg: String) {
        if (hasFilter(ffmpeg, "subtitles")) return
        error(
            "ffmpeg was built without libass and cannot render subtitles. " +
                "Turn Subtitles off, or point NUVIO_FFMPEG_PATH at an ffmpeg built with libass.",
        )
    }

    private fun hasFilter(ffmpeg: String, name: String): Boolean {
        cachedFilters[ffmpeg]?.let { return name in it }
        val filters = runCatching {
            val process = ProcessBuilder(ffmpeg, "-hide_banner", "-filters")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            // Columns are "flags name inputs->outputs description"; the name is the second.
            output.lineSequence()
                .mapNotNull { line -> line.trim().split(Regex("\\s+")).getOrNull(1) }
                .toSet()
        }.getOrDefault(emptySet())
        cachedFilters[ffmpeg] = filters
        return name in filters
    }

    private const val SUBTITLE_PASS_TIMEOUT_MINUTES = 5L

    /** Reconnect/timeout options shared by every remote read. */
    private fun remoteReadArgs(): List<String> = listOf(
        "-reconnect", "1",
        "-reconnect_streamed", "1",
        "-reconnect_delay_max", "5",
        "-rw_timeout", "15000000",
    )

    /** Joins headers into libavformat's CRLF-delimited `-headers` block, or null if none. */
    private fun headersArgument(headers: Map<String, String>): String? {
        val relevant = headers.filterKeys { key ->
            val lower = key.lowercase()
            // Host/connection are set by ffmpeg itself; forwarding them breaks requests.
            lower != "host" && lower != "connection" && lower != "content-length"
        }
        if (relevant.isEmpty()) return null
        return relevant.entries.joinToString(separator = "") { (key, value) -> "$key: $value\r\n" }
    }

    private fun uniqueOutputFile(request: ClipExtractRequest): File {
        // Naming is the user's, not the exporter's -- see ClipFilenameTemplate.
        // The stamp is taken once so {date} and {time} cannot disagree across a
        // midnight boundary within one filename.
        val stamp = ClipClock.localStamp(ClipClock.nowEpochMs())
        val stem = ClipFilenameTemplate.render(
            template = ClipFilenameSettings.template(),
            title = request.title,
            startMs = request.startMs,
            endMs = request.endMs,
            dateLabel = stamp.date,
            timeLabel = stamp.time,
        )
        val directory = clipOutputDir(clipsDir, request.folderSegments)
        var candidate = File(directory, "$stem.mp4")
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$stem ($index).mp4")
            index++
        }
        return candidate
    }


    /**
     * Which ffmpeg to cut with, chosen by what it can do rather than by where
     * it is.
     *
     * The first ffmpeg on the machine is not necessarily a usable one: the
     * stock Homebrew build ships without libzimg and libass, so it cannot tone
     * map HDR and cannot burn in subtitles. Picking it because it comes first
     * in a list costs the user two features and says nothing about why -- an
     * HDR clip just comes out grey. So every candidate is asked for its filters
     * and the first fully-equipped one wins, whoever it belongs to.
     *
     * NUVIO_FFMPEG_PATH still overrides everything, unexamined: someone who
     * names a binary means that binary.
     */
    /** The chosen ffmpeg, for other clip-side tools that shell out. See [resolveFfmpegPath]. */
    internal fun ffmpegPath(): String? = resolveFfmpegPath()

    /** Header block for a source, shared with the filmstrip builder. */
    internal fun headersArgumentFor(headers: Map<String, String>): String? = headersArgument(headers)

    private fun resolveFfmpegPath(): String? {
        cachedFfmpegPath?.let { return it }
        val explicit = System.getenv("NUVIO_FFMPEG_PATH")?.takeIf { it.isNotBlank() }
        if (explicit != null && File(explicit).canExecute()) {
            cachedFfmpegPath = explicit
            return explicit
        }
        val installed = (listOfNotNull(bundledFfmpegPath()) + FFMPEG_CANDIDATES).filter { File(it).canExecute() }
        val resolved = installed.firstOrNull { path -> REQUIRED_FILTERS.all { hasFilter(path, it) } }
            ?: installed.firstOrNull()
            // Fall back to PATH resolution; if it is missing, the process start throws.
            ?: "ffmpeg"
        if (resolved !in installed.take(1)) {
            log.i { "Using ffmpeg at $resolved" }
        }
        cachedFfmpegPath = resolved
        return resolved
    }

    /**
     * The ffmpeg shipped inside the app, if this build carries one. The
     * Windows MSI does (see `prepareWindowsFfmpegAppResources`), because a
     * Windows machine has nowhere conventional to find one. It still goes
     * through the filter check below like any other candidate.
     */
    private fun bundledFfmpegPath(): String? {
        val resourcesDir = System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val name = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"
        return File(resourcesDir, "ffmpeg/$name").takeIf(File::isFile)?.absolutePath
    }

    /**
     * Where to look, best-installed first.
     *
     * The application bundles at the end are not ours, and are read, never
     * touched -- they are simply where a jellyfin-ffmpeg (libzimg + libass)
     * already exists on a Mac that has one of these apps. A shipped build must
     * still carry its own; this list is what keeps a development machine from
     * silently exporting grey HDR.
     */
    private val FFMPEG_CANDIDATES = listOf(
        "/opt/homebrew/bin/ffmpeg",
        "/usr/local/bin/ffmpeg",
        "/usr/bin/ffmpeg",
        "/Applications/Stremio.app/Contents/MacOS/ffmpeg",
    )

    /** The filters whose absence silently degrades an export rather than failing it. */
    private val REQUIRED_FILTERS = listOf("zscale", "subtitles")

    /** Millisecond precision with a dot decimal separator, independent of locale. */
    private fun formatSeconds(value: Double): String {
        val ms = (value * 1000).toLong().coerceAtLeast(0L)
        return "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
    }

    /** Parses an ffmpeg progress line, returning the reported output time in seconds. */
    private fun parseFfmpegTimeSeconds(line: String): Double? {
        val marker = line.indexOf("time=")
        if (marker < 0) return null
        val rest = line.substring(marker + 5)
        val token = rest.takeWhile { !it.isWhitespace() }
        if (token.isBlank() || token.startsWith("N/A")) return null
        val parts = token.split(":")
        if (parts.size != 3) return null
        val hours = parts[0].toDoubleOrNull() ?: return null
        val minutes = parts[1].toDoubleOrNull() ?: return null
        val seconds = parts[2].toDoubleOrNull() ?: return null
        return hours * 3600 + minutes * 60 + seconds
    }
}

/**
 * The clips folder [root], or a folder inside it named after what the clip was
 * cut from -- see [ClipFolderLayout].
 *
 * Falls back to [root] when the subfolder cannot be created: a read-only drive,
 * or a name this filesystem refuses, should cost the user a tidy folder, not
 * the clip they just waited five minutes for.
 *
 * File-level and internal rather than a private member, so the fallback can be
 * tested against a real temporary directory without touching the user's own
 * clips folder preference.
 */
internal fun clipOutputDir(root: File, segments: List<String>): File {
    if (segments.isEmpty()) return root
    val target = segments.fold(root) { parent, segment -> File(parent, segment) }
    val usable = runCatching {
        (target.exists() || target.mkdirs()) && target.isDirectory && target.canWrite()
    }.getOrDefault(false)
    return if (usable) target else root
}
