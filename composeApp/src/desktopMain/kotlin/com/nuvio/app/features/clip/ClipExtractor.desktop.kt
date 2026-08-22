package com.nuvio.app.features.clip

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories

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
 */
internal actual object ClipExtractor {
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
                val sourceHeight = probeSourceHeight(ffmpeg, request)
                val encoders = candidateVideoEncoders(ffmpeg)

                var lastError: String? = null
                var succeeded = false
                for (encoder in encoders) {
                    ensureActive()
                    val args = buildFfmpegArgs(
                        ffmpeg = ffmpeg,
                        request = request,
                        startSec = startSec,
                        durationSec = durationSec,
                        outFile = outFile,
                        videoEncoder = encoder,
                        sourceHeight = sourceHeight,
                    )
                    val code = runFfmpeg(args, processRef, durationSec, onProgress) { ensureActive() }
                    if (code == 0 && outFile.exists() && outFile.length() > 0L) {
                        succeeded = true
                        break
                    }
                    lastError = "ffmpeg ($encoder) exited with code $code"
                    outFile.delete()
                }
                if (!succeeded) error(lastError ?: "Clip export failed")

                onProgress(1f)
                onSuccess(ClipOutput(fileUri = outFile.toURI().toString(), fileName = outFile.name))
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

    actual fun deleteFile(outputFileUri: String) {
        runCatching { fileFor(outputFileUri)?.delete() }
    }

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
        sourceHeight: Int?,
    ): List<String> {
        val args = mutableListOf(
            ffmpeg,
            "-hide_banner",
            "-nostdin",
            // Robustness for remote (debrid/HTTP) sources.
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "5",
            "-rw_timeout", "15000000",
        )

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

        // First video + first audio stream only; drop subtitles/data so the MP4
        // muxer never fails on image subs or unsupported codecs.
        args += listOf("-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn")

        args += encoderArgs(videoEncoder, sourceHeight)
        // 8-bit 4:2:0 so 10-bit HEVC/HDR sources produce a universally playable file.
        args += listOf("-pix_fmt", "yuv420p")
        // Stereo AAC plays everywhere (sources are often DTS/TrueHD/5.1).
        args += listOf("-c:a", "aac", "-b:a", "192k", "-ac", "2")

        args += "-avoid_negative_ts"
        args += "make_zero"
        // Make the mp4 immediately seekable/streamable.
        args += "-movflags"
        args += "+faststart"
        args += "-y"
        args += outFile.absolutePath
        return args
    }

    private fun encoderArgs(videoEncoder: String, sourceHeight: Int?): List<String> {
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
     * Reads the source's video height with ffprobe (a small ranged read of the
     * container header) to pick a sensible hardware-encoder bitrate. Best-effort:
     * returns null on any failure and the encoder uses a default.
     */
    private fun probeSourceHeight(ffmpeg: String, request: ClipExtractRequest): Int? = runCatching {
        val ffprobe = File(ffmpeg).parentFile?.resolve("ffprobe")?.takeIf { it.canExecute() }?.absolutePath
            ?: "ffprobe"
        val args = mutableListOf(ffprobe, "-v", "error")
        headersArgument(request.sourceHeaders)?.let { headers ->
            args += "-headers"
            args += headers
        }
        args += listOf(
            "-select_streams", "v:0",
            "-show_entries", "stream=height",
            "-of", "csv=p=0",
            request.sourceUrl,
        )
        val process = ProcessBuilder(args).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        output.lineSequence().firstNotNullOfOrNull { it.trim().toIntOrNull() }
    }.getOrNull()

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
        val base = request.title.ifBlank { "clip" }.sanitizeFileName()
        val startLabel = formatTimeLabel(request.startMs)
        val endLabel = formatTimeLabel(request.endMs)
        val stem = "$base ${startLabel}-$endLabel"
        var candidate = File(clipsDir, "$stem.mp4")
        var index = 1
        while (candidate.exists()) {
            candidate = File(clipsDir, "$stem ($index).mp4")
            index++
        }
        return candidate
    }

    private fun resolveFfmpegPath(): String? {
        val candidates = buildList {
            System.getenv("NUVIO_FFMPEG_PATH")?.takeIf { it.isNotBlank() }?.let { add(it) }
            add("/opt/homebrew/bin/ffmpeg")
            add("/usr/local/bin/ffmpeg")
            add("/usr/bin/ffmpeg")
        }
        candidates.firstOrNull { File(it).canExecute() }?.let { return it }
        // Fall back to PATH resolution; if it is missing, the process start throws.
        return "ffmpeg"
    }

    /** Millisecond precision with a dot decimal separator, independent of locale. */
    private fun formatSeconds(value: Double): String {
        val ms = (value * 1000).toLong().coerceAtLeast(0L)
        return "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
    }

    private fun formatTimeLabel(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0L)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            "${h}h${m.toString().padStart(2, '0')}m${s.toString().padStart(2, '0')}s"
        } else {
            "${m}m${s.toString().padStart(2, '0')}s"
        }
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(120).ifBlank { "clip" }

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
