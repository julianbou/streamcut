package com.nuvio.app.features.clip

import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager

internal actual object ClipFolderPicker {
    actual val canPick: Boolean = !java.awt.GraphicsEnvironment.isHeadless()

    actual fun pickDirectory(initialPath: String?, onPicked: (String?) -> Unit) {
        if (!canPick) {
            onPicked(null)
            return
        }
        SwingUtilities.invokeLater {
            val chosen = runCatching {
                // Match the host desktop rather than Swing's default cross-platform
                // look, which reads as an alien dialog on macOS and Windows.
                runCatching {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
                }
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isMultiSelectionEnabled = false
                    dialogTitle = "Choose clips folder"
                    initialPath?.let { path ->
                        val start = File(path)
                        if (start.isDirectory) currentDirectory = start
                    }
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile?.absolutePath
                } else {
                    null
                }
            }.getOrNull()
            onPicked(chosen)
        }
    }
}
