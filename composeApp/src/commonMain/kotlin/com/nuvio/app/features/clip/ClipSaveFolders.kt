package com.nuvio.app.features.clip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a Save as puts a clip, and what it calls it.
 *
 * [fileStem] is the name without `.mp4`, as the user typed it; the extractor
 * sanitizes it again and falls back to the filename template when nothing
 * usable is left. A name that is already taken keeps both files rather than
 * replacing one -- a clip is minutes of hunting, and Save as is not the place to
 * lose one to a typo.
 */
data class ClipSaveTarget(
    val directory: String,
    val fileStem: String,
)

/**
 * The folders Save as offers, newest first.
 *
 * People who cut clips for an edit save them into that edit's media folder, and
 * they come back to the same two or three projects for weeks. So Save as keeps
 * the last few folders as one-click choices instead of opening a file browser
 * every time, and the clips folder is always among them as the way back.
 *
 * Paths are stored as typed by the picker, one per line. They are not checked
 * for existence here -- common code has no filesystem -- and a folder that has
 * since gone away is the extractor's to handle when a clip is written to it.
 */
internal object ClipSaveFolders {

    private const val MaxRecent = 4

    private val _recent = MutableStateFlow<List<String>>(emptyList())

    /** Folders chosen in Save as, most recent first. */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private val _pickToken = MutableStateFlow(0)

    /**
     * Bumped every time the folder picker returns a folder. The chrome selects
     * the newest folder when it changes, so a cancelled picker -- which also
     * redraws the sheet -- leaves the user's selection alone.
     */
    val pickToken: StateFlow<Int> = _pickToken.asStateFlow()

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _recent.value = ClipStorage.loadSaveFolders()
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinctBy(::normalized)
            ?.take(MaxRecent)
            .orEmpty()
    }

    /** Moves [path] to the front, where the next Save as preselects it. */
    fun remember(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        ensureLoaded()
        val key = normalized(trimmed)
        val next = (listOf(trimmed) + _recent.value.filterNot { normalized(it) == key }).take(MaxRecent)
        if (next == _recent.value) return
        _recent.value = next
        ClipStorage.saveSaveFolders(next.joinToString("\n"))
    }

    /**
     * What Save as lists: the recent folders, then the clips folder unless it is
     * already one of them. The index into this list is what the chrome sends
     * back -- the bridge carries numbers, not paths.
     */
    fun choices(clipsFolder: String): List<String> {
        ensureLoaded()
        val recents = _recent.value
        if (clipsFolder.isBlank()) return recents
        val clipsKey = normalized(clipsFolder)
        return if (recents.any { normalized(it) == clipsKey }) recents else recents + clipsFolder
    }

    /** Whether [path] is the clips folder, so the sheet can say so. */
    fun isClipsFolder(path: String, clipsFolder: String): Boolean =
        clipsFolder.isNotBlank() && normalized(path) == normalized(clipsFolder)

    /** Opens the platform folder picker; a chosen folder goes to the front. */
    fun choose(initialPath: String?) {
        if (!ClipFolderPicker.canPick) return
        ClipFolderPicker.pickDirectory(initialPath, title = "Save clips to") { picked ->
            if (picked.isNullOrBlank()) return@pickDirectory
            remember(picked)
            _pickToken.value += 1
        }
    }

    private fun normalized(path: String): String = path.trim().trimEnd('/', '\\')
}
