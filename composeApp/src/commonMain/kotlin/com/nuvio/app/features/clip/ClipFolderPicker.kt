package com.nuvio.app.features.clip

/**
 * Native "choose a folder" dialog for the clips destination.
 *
 * Callback-based rather than blocking: the desktop dialog is modal and has to
 * run on the Swing event thread, so returning a value would mean blocking the
 * Compose frame that opened it.
 */
internal expect object ClipFolderPicker {
    val canPick: Boolean

    /**
     * Opens the picker at [initialPath]; calls back with null when cancelled.
     * [title] names what the folder is for; null keeps the clips-folder wording.
     */
    fun pickDirectory(initialPath: String?, title: String? = null, onPicked: (String?) -> Unit)
}
