package com.nuvio.app.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
internal actual fun Modifier.fileDragSource(absolutePath: String?): Modifier {
    if (absolutePath.isNullOrBlank()) return this
    return dragAndDropSource(
        // An explicit decoration, rather than letting the modifier snapshot the
        // card it is attached to. The default caches the composable's own
        // drawing, and that cache is not re-recorded when an async image
        // finishes loading -- which left every clip card showing an empty box.
        drawDragDecoration = {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                cornerRadius = CornerRadius(12f, 12f),
            )
        },
    ) { _ ->
        val file = File(absolutePath)
        // Checked at drag time, not at composition: the file can be trashed
        // from another window while the card is still on screen.
        if (!file.isFile) return@dragAndDropSource null
        DragAndDropTransferData(
            transferable = DragAndDropTransferable(FileTransferable(file)),
            // Copy, never Move: the drop target must not be able to relocate
            // the user's clip out of their clips folder.
            supportedActions = listOf(DragAndDropTransferAction.Copy),
        )
    }
}

/**
 * A single file offered as `javaFileListFlavor`, which is what Finder, Explorer,
 * upload fields and every AWT file dialog understand.
 */
private class FileTransferable(private val file: File) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor == DataFlavor.javaFileListFlavor

    override fun getTransferData(flavor: DataFlavor): Any {
        if (flavor != DataFlavor.javaFileListFlavor) throw UnsupportedFlavorException(flavor)
        return listOf(file)
    }
}
