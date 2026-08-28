package com.nuvio.app.features.clip

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories

private val log = Logger.withTag("ClipStrip")

/**
 * Roughly how many stills a strip should have, whatever the film's length.
 *
 * The number is a browsing decision, not a technical one: a few hundred
 * thumbnails is what a person can actually scroll through looking for a scene.
 * A feature film lands near this; a short one is capped by [MIN_SPACING_MS]
 * instead, since below about fifteen seconds consecutive stills start repeating
 * each other and cost bytes for nothing.
 */
private const val TARGET_FRAMES = 300
private const val MIN_SPACING_MS = 15_000L
private const val MAX_FRAMES = 600

/** Two at a time: more only splits the same bandwidth into slower pieces. */
private const val CONCURRENCY = 2

/** Wide enough to recognise a scene, small enough that the whole strip is ~1 MB. */
private const val THUMB_WIDTH = 200

actual object ClipStrip {

    actual val isSupported: Boolean get() = ClipExtractor.isSupported

    private val _state = MutableStateFlow(ClipStripState())
    actual val state: StateFlow<ClipStripState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val sessions = AtomicInteger(0)

    actual fun open(cacheKey: String, sourceUrl: String, headers: Map<String, String>, durationMs: Long) {
        close()
        if (!isSupported || sourceUrl.isBlank() || durationMs <= 0L) return

        val spacingMs = (durationMs / TARGET_FRAMES).coerceAtLeast(MIN_SPACING_MS)
        // The last sample is pulled back from the end: a seek past the final
        // keyframe returns nothing, and a strip that ends in a gap looks broken
        // rather than finished.
        val count = ((durationMs - spacingMs) / spacingMs).toInt().coerceIn(1, MAX_FRAMES)
        val session = sessions.incrementAndGet()

        val cacheDir = File(stripRoot, "${cacheKey.sanitized()}-$spacingMs").also { it.mkdirs() }
        val publishDir = publishDirFor(session) ?: return

        _state.value = ClipStripState(
            session = session,
            count = count,
            ready = 0,
            spacingMs = spacingMs.toInt(),
        )

        job = scope.launch {
            val ffmpeg = ClipExtractor.ffmpegPath() ?: return@launch
            val headerArg = ClipExtractor.headersArgumentFor(headers)
            val order = ArrayDeque(bisectionOrder(count))
            val done = AtomicInteger(0)

            // Workers pull from one shared queue rather than taking a slice
            // each, so a slow region does not leave the rest of the film
            // unsampled while one worker grinds.
            val workers = List(CONCURRENCY) {
                launch {
                    while (true) {
                        ensureActive()
                        val index = synchronized(order) { order.removeFirstOrNull() } ?: break
                        val target = File(cacheDir, "$index.jpg")
                        if (!target.isUsable()) {
                            captureFrame(
                                ffmpeg = ffmpeg,
                                headerArg = headerArg,
                                sourceUrl = sourceUrl,
                                atMs = index * spacingMs,
                                target = target,
                            )
                        }
                        if (target.isUsable()) {
                            publish(target, File(publishDir, "$index.jpg"))
                            val ready = done.incrementAndGet()
                            // Only while this session is still the current one:
                            // a strip closed mid-build must not keep moving a
                            // progress bar that belongs to nothing.
                            _state.update(session) { it.copy(ready = ready) }
                        }
                    }
                }
            }
            workers.joinAll()
            log.i { "Strip $session finished: ${done.get()}/$count frames" }
        }
    }

    actual fun close() {
        job?.cancel()
        job = null
        _state.value = ClipStripState()
    }

    /**
     * One still, with the flags that make an isolated seek cheap.
     *
     * `-ss` before `-i` is a container-index seek, so its cost does not grow
     * with position. `-blocksize` is the one that matters most over a network:
     * without it ffmpeg reads far past the frame it was asked for, which
     * measured 7.4 MB a frame instead of 2.6 MB. `-skip_frame nokey` decodes
     * only keyframes, which is 58x less work and lands on a clean frame.
     */
    private fun captureFrame(
        ffmpeg: String,
        headerArg: String?,
        sourceUrl: String,
        atMs: Long,
        target: File,
    ) {
        runCatching {
            val args = buildList {
                add(ffmpeg)
                add("-hide_banner")
                add("-nostdin")
                add("-loglevel"); add("error")
                add("-blocksize"); add("16384")
                add("-probesize"); add("65536")
                add("-analyzeduration"); add("0")
                add("-skip_frame"); add("nokey")
                headerArg?.let { add("-headers"); add(it) }
                add("-ss"); add(formatSeconds(atMs))
                add("-i"); add(sourceUrl)
                add("-frames:v"); add("1")
                add("-vf"); add("scale=$THUMB_WIDTH:-2")
                add("-q:v"); add("6")
                add("-y"); add(target.absolutePath)
            }
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            // Drained, not ignored: a full pipe buffer would deadlock the wait.
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                log.w { "Strip frame at ${atMs}ms timed out" }
                return
            }
            if (!target.isUsable() && output.isNotBlank()) {
                log.w { "Strip frame at ${atMs}ms failed: ${output.trim().take(200)}" }
            }
        }.onFailure { log.w(it) { "Strip frame at ${atMs}ms threw" } }
    }

    /**
     * Makes a cached frame readable by the player's webview.
     *
     * The page is a `file:` document whose read access is limited to its own
     * directory, so frames living in the cache are invisible to it however
     * correct their path. A hard link costs no bytes and puts a second name for
     * the same file where the page can see it; copying is the fallback for the
     * case where the cache and the page are on different volumes.
     */
    private fun publish(source: File, link: File) {
        if (link.exists()) return
        runCatching { Files.createLink(link.toPath(), source.toPath()) }
            .recoverCatching { source.copyTo(link, overwrite = true) }
            .onFailure { log.w(it) { "Could not publish strip frame ${source.name}" } }
    }

    /**
     * Frame order: ends first, then repeatedly the midpoint of every gap.
     *
     * So the strip is complete-but-coarse at every moment and quietly sharpens,
     * instead of filling in from the left. You can find your scene before it has
     * finished building, which is the whole point of doing it progressively.
     */
    internal fun bisectionOrder(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val seen = BooleanArray(count)
        val order = ArrayList<Int>(count)
        fun take(index: Int) {
            if (index in 0 until count && !seen[index]) {
                seen[index] = true
                order += index
            }
        }
        take(0)
        take(count - 1)
        val gaps = ArrayDeque<Pair<Int, Int>>()
        gaps += 0 to count - 1
        while (gaps.isNotEmpty()) {
            val (low, high) = gaps.removeFirst()
            val mid = (low + high) / 2
            if (mid == low || mid == high) continue
            take(mid)
            gaps += low to mid
            gaps += mid to high
        }
        return order
    }

    private val stripRoot: File
        get() = File(DesktopStorage.cacheDir.resolve("clip-strips").also { it.createDirectories() }.toUri())

    /**
     * Where the page looks for frames: a per-session folder inside the controls
     * page's own directory, named by a number so the bridge never has to carry
     * a string. Previous sessions are cleared, since their links are dead
     * weight the moment a new strip opens.
     */
    private fun publishDirFor(session: Int): File? = runCatching {
        val controlsRoot = File(URI(NativePlayerBridge.controlsPageUrl)).parentFile
        val root = File(controlsRoot, "strip")
        root.listFiles()?.forEach { it.deleteRecursively() }
        File(root, session.toString()).also { it.mkdirs() }
    }.onFailure { log.w(it) { "Could not prepare the strip directory" } }.getOrNull()

    private fun File.isUsable(): Boolean = exists() && length() > 0L

    private fun formatSeconds(ms: Long): String =
        "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

    /** Content keys carry colons and slashes; directory names cannot. */
    private fun String.sanitized(): String =
        map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").take(80).ifBlank { "strip" }

    private inline fun MutableStateFlow<ClipStripState>.update(
        session: Int,
        transform: (ClipStripState) -> ClipStripState,
    ) {
        val current = value
        if (current.session != session) return
        value = transform(current)
    }
}
