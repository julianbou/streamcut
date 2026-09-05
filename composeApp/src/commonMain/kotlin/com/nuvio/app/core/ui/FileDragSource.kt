package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

/**
 * Makes a composable a drag source for a file on disk.
 *
 * Dragging a clip straight into Finder, a chat window or an upload field is the
 * shortest path a clip has out of this app -- every alternative goes through
 * "reveal in file manager" and a second window. Null or missing paths are
 * inert rather than an error: a clip whose file has gone simply cannot be
 * dragged.
 *
 * No-op where the platform has no file drag (mobile).
 */
internal expect fun Modifier.fileDragSource(absolutePath: String?): Modifier
