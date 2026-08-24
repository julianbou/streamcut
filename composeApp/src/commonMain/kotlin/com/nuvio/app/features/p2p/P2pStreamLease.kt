package com.nuvio.app.features.p2p

/**
 * Who still needs the local P2P server running.
 *
 * Playback used to be the only user of the engine, so the player shut it down
 * on its way out. A clip cut from a torrent breaks that assumption: ffmpeg
 * reads the same local stream URL, and it keeps reading after the player is
 * gone -- stopping the server underneath it fails the export.
 *
 * So the shutdown is refcounted instead. The player holds a lease for as long
 * as it is on screen, every clip export holds one until it settles, and the
 * engine is shut down when the last holder lets go. Nothing here starts the
 * engine: a lease only defers the stop, so holding one while no torrent is
 * playing costs nothing.
 *
 * Main-thread only, like the repositories that call it.
 */
object P2pStreamLease {
    private val holders = mutableSetOf<String>()
    private var counter = 0L

    /** A token no other holder can collide with. */
    fun newToken(prefix: String): String = "$prefix-${++counter}"

    /** Keep the engine alive for [token]. Holding twice is the same as once. */
    fun retain(token: String) {
        holders += token
    }

    /**
     * Let [token] go, shutting the engine down if it was the last holder.
     * Releasing a token that was never retained is a no-op, which is what lets
     * callers release unconditionally on every path out.
     */
    fun release(token: String) {
        if (!holders.remove(token)) return
        if (holders.isEmpty()) P2pStreamingEngine.shutdown()
    }

    /** Whether anything still depends on the engine. */
    val isHeld: Boolean get() = holders.isNotEmpty()
}
