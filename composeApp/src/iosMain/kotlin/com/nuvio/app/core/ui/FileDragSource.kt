package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

/** No file drag on mobile: there is nowhere to drop one. */
internal actual fun Modifier.fileDragSource(absolutePath: String?): Modifier = this
