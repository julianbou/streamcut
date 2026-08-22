package com.nuvio.app.features.clip

/** No clip export on this platform, so there is no folder to choose. */
internal actual object ClipFolderPicker {
    actual val canPick: Boolean = false
    actual fun pickDirectory(initialPath: String?, onPicked: (String?) -> Unit) = onPicked(null)
}
