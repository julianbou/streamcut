package com.nuvio.app.features.clip

import kotlinx.coroutines.flow.StateFlow

/**
 * How far along the filmstrip for the current title is.
 *
 * Everything here is a number because it crosses the player's webview bridge,
 * which carries numbers only. [session] doubles as the directory the frames are
 * published under, which is why it is an incrementing counter rather than the
 * cache key: the page needs to name a folder without being handed a string.
 */
data class ClipStripState(
    /** Incrementing per open; 0 means no strip. */
    val session: Int = 0,
    /** How many frames the finished strip will have. */
    val count: Int = 0,
    /** How many exist so far. Frames arrive coarse-first, not left to right. */
    val ready: Int = 0,
    /** Seconds between frames, so the page can label them without being told each time. */
    val spacingMs: Int = 0,
)

/**
 * The filmstrip: one still every few seconds across a whole film, for finding
 * the scene you want to cut.
 *
 * This is a browsing tool, not a trimming one. Scrubbing a two-hour film to
 * find a moment means seeking, watching, and seeking again; a strip you scroll
 * answers the same question by looking.
 *
 * Frames are built with isolated ffmpeg seeks rather than one decode pass.
 * Measured over HTTP, a tuned seek costs about one GOP flat wherever it lands,
 * so a strip costs roughly `GOP length / spacing` of the film -- for typical
 * encodes at the spacing chosen here, something like a tenth of it. One pass
 * would read every byte no matter how few frames were wanted. (The reverse is
 * true for frames packed close together, which is why this is not how a zoomed
 * strip would be built.)
 */
expect object ClipStrip {
    val isSupported: Boolean

    val state: StateFlow<ClipStripState>

    /**
     * Start (or resume) the strip for a title.
     *
     * [cacheKey] should be stable across playback sessions -- a content key,
     * not a source URL, which for torrents and debrid links carries tokens that
     * change every time. Frames already on disk are kept, so reopening a film
     * costs nothing.
     */
    fun open(cacheKey: String, sourceUrl: String, headers: Map<String, String>, durationMs: Long)

    /** Stop building and forget the session. Frames already written stay cached. */
    fun close()
}
