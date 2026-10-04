package com.nuvio.app.features.clip

import com.nuvio.app.features.player.PlayerLaunch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Opens the player on a source from outside the navigation graph, optionally
 * with a clip range already marked.
 *
 * For a cut that was worked out elsewhere and wants a person's eye before it is
 * exported: an assistant proposes the range, the player opens on it with the
 * In and Out points set, and the user nudges and exports as if they had marked
 * it themselves. The same road a deep link takes into the details page, kept
 * apart from it because this one carries a resolved source rather than an id.
 */
internal object ClipPlayerRequests {

    /** A range to mark once the player knows how long the source is. */
    data class Range(val startMs: Long, val endMs: Long, val token: Long)

    private val _pending = MutableStateFlow<PlayerLaunch?>(null)

    /** The player launch waiting for the app's navigation to pick it up. */
    val pending: StateFlow<PlayerLaunch?> = _pending.asStateFlow()

    private val ranges = mutableMapOf<String, Range>()
    private var tokens = 0L

    @Synchronized
    fun open(launch: PlayerLaunch, startMs: Long? = null, endMs: Long? = null) {
        if (startMs != null && endMs != null && endMs > startMs) {
            ranges[launch.sourceUrl] = Range(startMs, endMs, ++tokens)
        } else {
            ranges.remove(launch.sourceUrl)
        }
        _pending.value = launch
    }

    fun markConsumed(launch: PlayerLaunch) {
        if (_pending.value === launch) _pending.value = null
    }

    /**
     * The range the player for [sourceUrl] was opened with, handed over once:
     * opening the same source again by hand must not bring an old range back.
     */
    @Synchronized
    fun takeRange(sourceUrl: String): Range? = ranges.remove(sourceUrl)
}
