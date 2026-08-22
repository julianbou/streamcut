package com.nuvio.app.features.clip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

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

    /** Forget a clip and delete the file it points at. */
    fun delete(id: String) {
        ensureLoaded()
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        ClipExtractor.deleteFile(entry.outputFileUri)
        _entries.value = _entries.value.filterNot { it.id == id }
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

    private fun persist() {
        runCatching { json.encodeToString(_entries.value) }
            .onSuccess(ClipStorage::saveLibraryPayload)
    }
}
