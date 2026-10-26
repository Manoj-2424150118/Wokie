package com.wokie.message.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-level contract behind the reconnect fix: TransportCoordinator now
 * calls recordPeerConnected / recordPeerFailed / cancelPeerAttempt from the
 * transport callbacks. Before that wiring existed, NOTHING in production
 * called them, so a peer stayed in activeConnectingPeers forever after the
 * first attempt and could never be reconnected without restarting the app.
 */
class ReconnectGateTest {

    @Test
    fun connectedPeer_canBeAttemptedAgain() {
        val engine = SmartConnectEngine()
        val peer = "endpoint-1"
        assertTrue(engine.canAttemptPeer(peer))
        engine.recordPeerAttempt(peer)
        assertFalse(engine.canAttemptPeer(peer)) // attempt in flight

        engine.recordPeerConnected(peer)
        assertTrue(engine.canAttemptPeer(peer)) // free to reconnect after a disconnect
    }

    @Test
    fun failedPeer_canBeAttemptedAfterBackoff() {
        val engine = SmartConnectEngine()
        val peer = "endpoint-2"
        val now = System.currentTimeMillis()
        engine.recordPeerAttempt(peer)
        engine.recordPeerFailed(peer) // failure count 1 -> 1 s backoff from last attempt

        assertFalse(engine.canAttemptPeer(peer, now + 500))
        assertTrue(engine.canAttemptPeer(peer, now + 5_000))
    }

    @Test
    fun cancelledAttempt_freesPeerImmediatelyWithoutPenalty() {
        val engine = SmartConnectEngine()
        val peer = "endpoint-3"
        engine.recordPeerAttempt(peer)
        assertFalse(engine.canAttemptPeer(peer))

        engine.cancelPeerAttempt(peer)
        assertTrue(engine.canAttemptPeer(peer)) // no backoff after a user cancel
    }
}
