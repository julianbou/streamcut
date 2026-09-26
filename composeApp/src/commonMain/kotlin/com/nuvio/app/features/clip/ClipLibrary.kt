package com.nuvio.app.features.clip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Clips removed from view but not yet from disk.
 *
 * Held rather than deleted for as long as the undo bar is up. [entries] is what
 * the clip belonged to and is restored wholesale, so an undo puts a clip back
 * with its stills, size and provenance intact instead of a reconstruction.
 */
data class ClipUndoBatch(val entries: List<ClipEntry>)

/**
 * How long a deleted clip can be brought back.
 *
 * Long enough to notice a mistake, short enough that the bar is not still
 * sitting there when you have moved on to something else.
 */
private const val CLIP_UNDO_WINDOW_MS = 7_000L

/**
 * The clips that finished exporting, newest first.
 *
 * Backed by a small JSON blob rather than the app database: the list is
 * user-scale (tens, not thousands), and keeping it self-contained means the
 * clipper adds no schema that upstream Nuvio migrations could collide with.
 */
object ClipLibrary {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _entries = MutableStateFlow<List<ClipEntry>>(emptyList())
    val entries: StateFlow<List<ClipEntry>> = _entries.asStateFlow()

    private var loaded = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _pendingUndo = MutableStateFlow<ClipUndoBatch?>(null)

    /** The batch the undo bar is currently offering to restore, if any. */
    val pendingUndo: StateFlow<ClipUndoBatch?> = _pendingUndo.asStateFlow()

    private var undoJob: Job? = null

    /** Reads the persisted library once per process. Safe to call repeatedly. */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val payload = ClipStorage.loadLibraryPayload()?.takeIf { it.isNotBlank() } ?: return
        val stored = runCatching {
            json.decodeFromString<List<ClipEntry>>(payload)
        }.getOrElse { emptyList() }
        // Drop clips whose file the user deleted from disk behind our back, so
        // the list never offers a row that cannot be opened.
        val existing = stored.filter { ClipExtractor.exists(it.outputFileUri) }
        _entries.value = existing.sortedByDescending { it.createdAtEpochMs }
        if (existing.size != stored.size) persist()
    }

    /** Clips cut from one movie/episode, newest first. */
    fun entriesFor(contentKey: String): List<ClipEntry> =
        _entries.value.filter { it.contentKey == contentKey }

    internal fun record(entry: ClipEntry) {
        ensureLoaded()
        // Same output path == same clip: replace rather than accumulate.
        val deduped = _entries.value.filterNot { it.outputFileUri == entry.outputFileUri }
        _entries.value = (listOf(entry) + deduped).sortedByDescending { it.createdAtEpochMs }
        persist()
    }

    /** Take a clip out of the library, undoably. See [delete] below. */
    fun delete(id: String) = delete(listOf(id))

    /**
     * Take clips out of the library, with a window in which that can be taken
     * back.
     *
     * Nothing is trashed here. The entries leave [entries] at once -- the point
     * of an undo bar is that the delete looks done -- but the files stay where
     * they are, and stay *persisted*, until [commitPendingDelete] runs. That
     * ordering is deliberate: if the app dies inside the undo window, the next
     * launch finds a clip that is still on disk and still in the library, which
     * is the only failure here that costs nobody anything. Quitting inside the
     * window has the same effect, and for the same reason.
     */
    fun delete(ids: List<String>) {
        ensureLoaded()
        val removed = _entries.value.filter { it.id in ids }
        if (removed.isEmpty()) return
        // A second delete finishes the first: two undo bars would be ambiguous
        // about which one a click restores, and the older batch is the one the
        // user has already looked away from.
        commitPendingDelete()
        _entries.value = _entries.value - removed.toSet()
        _pendingUndo.value = ClipUndoBatch(entries = removed)
        persist()
        undoJob = scope.launch {
            delay(CLIP_UNDO_WINDOW_MS)
            commitPendingDelete()
        }
    }

    /** Put the pending batch back in the library. The files never moved. */
    fun undoDelete() {
        undoJob?.cancel()
        undoJob = null
        val batch = _pendingUndo.value ?: return
        _pendingUndo.value = null
        _entries.value = (_entries.value + batch.entries)
            .sortedByDescending { it.createdAtEpochMs }
        persist()
    }

    /** Let the pending batch go: move the files to the system Trash for real. */
    fun commitPendingDelete() {
        undoJob?.cancel()
        undoJob = null
        val batch = _pendingUndo.value ?: return
        _pendingUndo.value = null
        batch.entries.forEach { entry ->
            ClipExtractor.deleteFile(entry.outputFileUri)
            // The still is a cache entry keyed by the clip's path, so leaving it
            // behind would hand the next clip written to that path the wrong picture.
            if (entry.thumbnailUri.isNotBlank()) ClipExtractor.deleteFile(entry.thumbnailUri)
            ClipExtractor.deleteHoverFrames(entry.outputFileUri)
        }
        persist()
    }

    /** Show a saved clip in the platform file manager. */
    fun reveal(id: String) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        ClipExtractor.reveal(entry.outputFileUri)
    }

    /** Open a saved clip in the system's default video player. */
    fun open(id: String) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        ClipExtractor.openFile(entry.outputFileUri)
    }

    /**
     * Writes the visible library *plus* anything waiting on an undo, so a clip
     * is only forgotten once its file has actually gone to the Trash. The two
     * can never disagree about whether a clip exists.
     */
    private fun persist() {
        val all = _entries.value + _pendingUndo.value?.entries.orEmpty()
        runCatching { json.encodeToString(all.sortedByDescending { it.createdAtEpochMs }) }
            .onSuccess(ClipStorage::saveLibraryPayload)
    }
}
