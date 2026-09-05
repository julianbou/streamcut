package com.nuvio.app.features.clip

/** A wall-clock date and time, as they go into a filename. */
internal data class ClipStamp(val date: String, val time: String)

internal expect object ClipClock {
    fun nowEpochMs(): Long

    /**
     * [epochMs] in the machine's own timezone, as `2026-08-26` and `14-03-21`.
     *
     * Local rather than UTC because these end up in filenames the user reads:
     * a clip cut at eleven at night should not be dated tomorrow. The time uses
     * dashes because a colon is a path separator on some of the filesystems
     * these files get copied to.
     */
    fun localStamp(epochMs: Long): ClipStamp
}
