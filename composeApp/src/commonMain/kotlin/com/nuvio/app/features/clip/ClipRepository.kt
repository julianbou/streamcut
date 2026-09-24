package com.nuvio.app.features.clip

import com.nuvio.app.features.p2p.P2pStreamLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


/**
 * Tracks clip-extraction jobs.
 *
 * Every export gets its own job, so marking a second range while the first is
 * still encoding queues it rather than replacing it -- on the same title or a
 * different one. Jobs are addressed by [ClipJob.id] and carry a
 * [ClipContentRef], which is what keeps an export started on one title from
 * showing its progress -- or its "saved"/"failed" notice -- while a different
 * title is on screen.
 *
 * Extraction itself lives in a process-wide scope inside [ClipExtractor], so
 * leaving the player, or the title, does not disturb a running export. A clip
 * cut from a torrent additionally holds a [P2pStreamLease] for as long as the
 * job lives, so the local P2P server it reads from outlives the player too.
 *
 * All mutation is funnelled onto the main thread by [onMain]: ffmpeg reports
 * progress and completion from IO threads, and the queue below is
 * read-modify-write.
 */
object ClipRepository {
    /**
     * How many exports encode at the same time; the rest wait as
     * [ClipStatus.Queued]. Each job is a transcode plus a ranged download of
     * the source, so running more of them at once mostly makes every clip
     * slower rather than the batch faster.
     */
    private const val MaxConcurrentExports = 2

    /**
     * Finished jobs kept per title before the oldest are dropped. They are only
     * notices -- the permanent record of an exported clip is [ClipLibrary] --
     * so a long session does not need to accumulate them.
     */
    private const val MaxFinishedJobsPerContent = 4

    private val _jobs = MutableStateFlow<List<ClipJob>>(emptyList())

    /** Every tracked job, newest first. Feeds "exports in progress" surfaces. */
    val jobs: StateFlow<List<ClipJob>> = _jobs.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Running jobs only, by job id. Its size is the count against the cap. */
    private val handles = mutableMapOf<String, ClipTaskHandle>()

    /** Waiting jobs, by job id, in the order they were requested. */
    private val queue = mutableMapOf<String, ClipExtractRequest>()

    private var counter = 0L

    /** Whether the current platform can extract clips (desktop only for now). */
    val isSupported: Boolean get() = ClipExtractor.isSupported

    /** Absolute path clips are currently written to. Empty where unsupported. */
    fun outputDirPath(): String = ClipExtractor.outputDirPath()

    /** Free space where clips are written, for the library header. */
    fun outputDirFreeBytes(): Long = ClipExtractor.outputDirFreeBytes()

    /** Plain filesystem path for a clip's `file:` URI, for dragging and display. */
    fun filePathOf(fileUri: String): String = ClipExtractor.filePathOf(fileUri)

    /** Opens the clips folder itself in the platform file manager. */
    fun revealOutputDir() {
        val path = ClipExtractor.outputDirPath()
        // openFile rather than reveal: revealing a folder selects it in its
        // parent, which is one level away from what "Open folder" promises.
        if (path.isNotBlank()) ClipExtractor.openFile(path)
    }

    /** Absolute path used when no custom folder is set. */
    fun defaultOutputDirPath(): String = ClipExtractor.defaultOutputDirPath()

    /**
     * Point future exports at [path]; null restores the default folder.
     * Returns false when the folder is missing or not writable, in which case
     * the previous choice is left untouched.
     */
    fun setOutputDirPath(path: String?): Boolean = ClipExtractor.setOutputDirPath(path)

    /** Jobs belonging to [contentKey] that have run this session, newest first. */
    fun jobsFor(contentKey: String): List<ClipJob> =
        _jobs.value.filter { it.contentKey == contentKey }

    /**
     * Queue an extraction of [startMs]..[endMs] from the currently playing
     * stream. Starts immediately when a slot is free. No-ops on unsupported
     * platforms or invalid ranges.
     *
     * Pass [retainsP2pStream] when [sourceUrl] points at the local P2P server,
     * so it is kept running until the job settles.
     *
     * [audioTrackIndex] and [subtitle] carry what the viewer is watching --
     * the selected audio language, and the subtitle to burn in -- so the file
     * on disk matches the player instead of the source's defaults.
     *
     * [saveTo] is Save as: an exact folder and name, which also skips the
     * per-title subfolders -- the user picked where the file goes.
     */
    fun startClip(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        content: ClipContentRef,
        startMs: Long,
        endMs: Long,
        retainsP2pStream: Boolean = false,
        audioTrackIndex: Int = -1,
        subtitle: ClipSubtitleSelection? = null,
        aspect: ClipAspect = ClipAspect.Source,
        targetSizeMb: Int = 0,
        saveTo: ClipSaveTarget? = null,
    ) {
        if (!isSupported) return
        if (sourceUrl.isBlank() || endMs <= startMs) return

        val title = content.label.ifBlank { "Clip" }
        val request = ClipExtractRequest(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            startMs = startMs,
            endMs = endMs,
            title = title,
            audioTrackIndex = audioTrackIndex,
            subtitle = subtitle,
            aspect = aspect,
            targetSizeMb = targetSizeMb,
            folderSegments = if (saveTo == null) ClipGroupingSettings.folderSegmentsFor(content) else emptyList(),
            saveTo = saveTo,
        )
        onMain {
            // A double-click on Export would otherwise encode the same range
            // twice, into two files.
            val alreadyQueued = _jobs.value.any { job ->
                job.contentKey == content.key &&
                    job.startMs == startMs &&
                    job.endMs == endMs &&
                    job.destinationDir == saveTo?.directory &&
                    (job.status == ClipStatus.Queued || job.status == ClipStatus.Running)
            }
            if (alreadyQueued) return@onMain
            // The counter alone restarts at zero every launch, so it is paired
            // with the clock: library entries outlive the process and their ids
            // must not collide across sessions.
            val id = "clip-${ClipClock.nowEpochMs()}-${++counter}"
            _jobs.value = listOf(
                ClipJob(
                    id = id,
                    content = content,
                    title = title,
                    startMs = startMs,
                    endMs = endMs,
                    status = ClipStatus.Queued,
                    progress = 0f,
                    destinationDir = saveTo?.directory,
                ),
            ) + _jobs.value
            queue[id] = request
            // Taken before the job can start, so a clip still waiting its turn
            // keeps the stream alive as surely as one already encoding.
            if (retainsP2pStream) P2pStreamLease.retain(id)
            trimFinished(content.key)
            pump()
        }
    }

    /** Cancel the job with [jobId], whether it is running or still queued. */
    fun cancel(jobId: String) {
        onMain {
            queue.remove(jobId)
            handles.remove(jobId)?.cancel()
            // Cancellation is the one ending [ClipExtractor] reports through no
            // callback, so the lease has to be dropped here.
            P2pStreamLease.release(jobId)
            update(jobId) { job ->
                if (job.status == ClipStatus.Running || job.status == ClipStatus.Queued) {
                    job.copy(status = ClipStatus.Cancelled)
                } else {
                    job
                }
            }
            pump()
        }
    }

    /** Dismiss the completion/failure notice for [jobId]. */
    fun dismiss(jobId: String) {
        onMain { _jobs.value = _jobs.value.filterNot { it.id == jobId } }
    }

    /** Reveal the clip produced by [jobId] in the file manager. */
    fun revealOutput(jobId: String) {
        val uri = _jobs.value
            .firstOrNull { it.id == jobId && it.status == ClipStatus.Completed }
            ?.outputFileUri
            ?: return
        ClipExtractor.reveal(uri)
    }

    /** Fills every free slot with the oldest waiting jobs. */
    private fun pump() {
        while (handles.size < MaxConcurrentExports) {
            val id = queue.keys.firstOrNull() ?: return
            val request = queue.remove(id) ?: return
            // The job can be cancelled or dismissed while it waits, in which
            // case its slot goes to whatever is behind it in the queue.
            val job = _jobs.value.firstOrNull { it.id == id && it.status == ClipStatus.Queued }
                ?: continue
            launchJob(job, request)
        }
    }

    private fun launchJob(job: ClipJob, request: ClipExtractRequest) {
        val id = job.id
        update(id) { it.copy(status = ClipStatus.Running, progress = 0f) }
        handles[id] = ClipExtractor.start(
            request = request,
            onProgress = { fraction ->
                onMain { update(id) { it.copy(progress = fraction.coerceIn(0f, 1f)) } }
            },
            onSuccess = { output ->
                onMain {
                    handles.remove(id)
                    P2pStreamLease.release(id)
                    update(id) {
                        it.copy(
                            status = ClipStatus.Completed,
                            progress = 1f,
                            outputFileUri = output.fileUri,
                        )
                    }
                    // Recorded from the captured job rather than from the list:
                    // the file is on disk either way, even if the notice was
                    // dismissed while it encoded.
                    ClipLibrary.record(
                        ClipEntry(
                            id = id,
                            content = job.content,
                            startMs = job.startMs,
                            endMs = job.endMs,
                            outputFileUri = output.fileUri,
                            fileName = output.fileName,
                            createdAtEpochMs = ClipClock.nowEpochMs(),
                            thumbnailUri = output.thumbnailUri,
                            fileSizeBytes = output.fileSizeBytes,
                            width = output.width,
                            height = output.height,
                        ),
                    )
                    trimFinished(job.contentKey)
                    pump()
                }
            },
            onFailure = { message ->
                onMain {
                    handles.remove(id)
                    P2pStreamLease.release(id)
                    update(id) { it.copy(status = ClipStatus.Failed, errorMessage = message) }
                    trimFinished(job.contentKey)
                    pump()
                }
            },
        )
    }

    /** Drops the oldest settled notices for one title past the retention cap. */
    private fun trimFinished(contentKey: String) {
        val finished = _jobs.value.filter { it.contentKey == contentKey && it.isFinished }
        if (finished.size <= MaxFinishedJobsPerContent) return
        val stale = finished.drop(MaxFinishedJobsPerContent).map { it.id }.toSet()
        _jobs.value = _jobs.value.filterNot { it.id in stale }
    }

    private val ClipJob.isFinished: Boolean
        get() = status != ClipStatus.Running && status != ClipStatus.Queued

    private inline fun update(jobId: String, transform: (ClipJob) -> ClipJob) {
        _jobs.value = _jobs.value.map { if (it.id == jobId) transform(it) else it }
    }

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }
}
