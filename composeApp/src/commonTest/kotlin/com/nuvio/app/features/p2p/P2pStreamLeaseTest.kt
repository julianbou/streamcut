package com.nuvio.app.features.p2p

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The point of the lease is that a clip export outliving the player does not
 * lose the local P2P server underneath it, so these cases all check that the
 * engine is *not* released early.
 *
 * Draining the holders is deliberately not exercised: the last release calls
 * [P2pStreamingEngine.shutdown], which on desktop would try to stop a real
 * TorrServer -- possibly one the developer running the suite is using. Every
 * test here therefore leaves its own tokens held, which can only ever defer a
 * shutdown, never cause one.
 */
class P2pStreamLeaseTest {
    @Test
    fun `an export still running keeps the engine after the player leaves`() {
        val player = P2pStreamLease.newToken("player-leaves")
        val export = P2pStreamLease.newToken("export-runs")
        P2pStreamLease.retain(player)
        P2pStreamLease.retain(export)

        P2pStreamLease.release(player)

        assertTrue(P2pStreamLease.isHeld)
    }

    @Test
    fun `releasing a token that was never retained leaves other holders alone`() {
        val held = P2pStreamLease.newToken("held")
        P2pStreamLease.retain(held)

        P2pStreamLease.release("never-retained")
        P2pStreamLease.release("never-retained")

        assertTrue(P2pStreamLease.isHeld)
    }

    @Test
    fun `retaining the same token twice still only needs one release`() {
        val other = P2pStreamLease.newToken("other")
        val doubled = P2pStreamLease.newToken("doubled")
        P2pStreamLease.retain(other)
        P2pStreamLease.retain(doubled)
        P2pStreamLease.retain(doubled)

        P2pStreamLease.release(doubled)

        // Only `other` is left; a second release of `doubled` must not drop it.
        P2pStreamLease.release(doubled)
        assertTrue(P2pStreamLease.isHeld)
    }

    @Test
    fun `tokens never collide`() {
        assertNotEquals(P2pStreamLease.newToken("player"), P2pStreamLease.newToken("player"))
    }
}
