package com.nuvio.app.features.clip

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager

internal actual object ClipFolderPicker {
    actual val canPick: Boolean = !java.awt.GraphicsEnvironment.isHeadless()

    private val isMac: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    actual fun pickDirectory(initialPath: String?, title: String?, onPicked: (String?) -> Unit) {
        if (!canPick) {
            onPicked(null)
            return
        }
        SwingUtilities.invokeLater {
            val dialogTitle = title ?: "Choose clips folder"
            val start = initialPath?.let(::File)?.takeIf { it.isDirectory }
            val chosen = runCatching {
                if (isMac) pickWithFinderPanel(dialogTitle, start) else pickWithSwing(dialogTitle, start)
            }.getOrNull()
            onPicked(chosen)
        }
    }

    /**
     * The Finder's own open panel: sidebar favourites, search, and New Folder.
     * Swing's chooser on macOS has none of those -- the usual reason to pick a
     * folder is a new edit project, and it could not even make one.
     *
     * AWT shows a folder panel when this property is set at the moment the
     * dialog opens, so it is set around the one call and put back.
     */
    private fun pickWithFinderPanel(dialogTitle: String, start: File?): String? {
        val property = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(property)
        System.setProperty(property, "true")
        try {
            val dialog = FileDialog(null as Frame?, dialogTitle, FileDialog.LOAD).apply {
                isMultipleMode = false
                start?.let { directory = it.absolutePath }
            }
            dialog.isVisible = true
            val directory = dialog.directory ?: return null
            val file = dialog.file ?: return null
            return File(directory, file).absolutePath
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    /**
     * Elsewhere AWT's FileDialog cannot choose folders at all, so Swing's
     * chooser stays -- in the system look, whose Windows toolbar has a new
     * folder button.
     */
    private fun pickWithSwing(dialogTitle: String, start: File?): String? {
        // Match the host desktop rather than Swing's default cross-platform
        // look, which reads as an alien dialog on Windows.
        runCatching {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        }
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
            this.dialogTitle = dialogTitle
            start?.let { currentDirectory = it }
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }
}
