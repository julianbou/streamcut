package com.nuvio.app.features.clip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The filmstrip needs ffmpeg and the desktop player's webview; neither exists here. */
actual object ClipStrip {
    actual val isSupported: Boolean = false

    private val _state = MutableStateFlow(ClipStripState())
    actual val state: StateFlow<ClipStripState> = _state.asStateFlow()

    actual fun open(cacheKey: String, sourceUrl: String, headers: Map<String, String>, durationMs: Long) = Unit
    actual fun close() = Unit
}
