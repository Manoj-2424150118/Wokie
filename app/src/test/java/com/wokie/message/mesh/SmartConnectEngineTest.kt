package com.wokie.message.mesh

import com.wokie.message.transport.PeerNode
import org.junit.Assert.*
import org.junit.Test

class SmartConnectEngineTest {

    @Test
    fun testPreferredRouteSelectedWhenAvailable() {
        val engine = SmartConnectEngine(
            preferredTransport = "NEARBY",
            fallbackPolicy = FallbackPolicy.PREFERRED_WITH_SMART_FALLBACK
        )
        val availMap = mapOf(
            "NEARBY" to true,
            "WIFI DIRECT" to true,
            "BLE MESH" to true
        )
        val best = engine.evaluateBestTransport(availMap)
        assertEquals("NEARBY", best)
    }

    @Test
    fun testFallbackSelectedWhenPreferredUnavailable() {
        val engine = SmartConnectEngine(
            preferredTransport = "NEARBY",
            fallbackPolicy = FallbackPolicy.PREFERRED_WITH_SMART_FALLBACK
        )
        val availMap = mapOf(
            "NEARBY" to false, // Preferred unavailable
            "WIFI DIRECT" to true,
            "BLE MESH" to true
        )
        val best = engine.evaluateBestTransport(availMap)
        assertEquals("WIFI DIRECT", best)
    }

    @Test
    fun testPreferredRouteRegainedWhenItReturns() {
        val engine = SmartConnectEngine(
            preferredTransport = "NEARBY",
            fallbackPolicy = FallbackPolicy.PREFERRED_WITH_SMART_FALLBACK
        )
        // 1. Nearby is down -> picks WIFI DIRECT
        var availMap = mapOf(
            "NEARBY" to false,
            "WIFI DIRECT" to true
        )
        assertEquals("WIFI DIRECT", engine.evaluateBestTransport(availMap))

        // 2. Nearby returns -> should regain NEARBY
        availMap = mapOf(
            "NEARBY" to true,
            "WIFI DIRECT" to true
        )
        assertEquals("NEARBY", engine.evaluateBestTransport(availMap))
    }

    @Test
    fun testPreferredOnlyPolicyRejectsFallback() {
        val engine = SmartConnectEngine(
            preferredTransport = "NEARBY",
            fallbackPolicy = FallbackPolicy.PREFERRED_ONLY
        )
        val availMap = mapOf(
            "NEARBY" to false,
            "WIFI DIRECT" to true
        )
        val best = engine.evaluateBestTransport(availMap)
        assertNull(best)
    }

    @Test
    fun testFailedRoutePenaltyAndBackoff() {
        val engine = SmartConnectEngine(
            preferredTransport = "NEARBY",
            fallbackPolicy = FallbackPolicy.PREFERRED_WITH_SMART_FALLBACK
        )
        val peerId = "PEER_123"

        assertTrue(engine.canAttemptPeer(peerId))
        engine.recordPeerAttempt(peerId)
        assertFalse(engine.canAttemptPeer(peerId)) // Locked while active

        // Record failure
        engine.recordPeerFailed(peerId, "NEARBY")
        assertFalse(engine.canAttemptPeer(peerId)) // Within backoff window (1s)

        // Multiple failures on NEARBY penalize its score
        for (i in 1..4) {
            engine.recordRouteFailure("NEARBY")
        }
        val scoreNearby = engine.scoreTransport("NEARBY", isAvailable = true)
        val scoreWifi = engine.scoreTransport("WIFI DIRECT", isAvailable = true)
        // Due to heavy recent failure penalty on NEARBY, WIFI DIRECT should score higher
        assertTrue(scoreWifi.score > scoreNearby.score)
    }

    @Test
    fun testInsecureRouteMarkedUnusable() {
        val engine = SmartConnectEngine(preferredTransport = "NEARBY")
        engine.markTransportInsecure("NEARBY")

        val score = engine.scoreTransport("NEARBY", isAvailable = true)
        assertEquals(-1, score.score)
        assertFalse(score.isEligible)

        val availMap = mapOf("NEARBY" to true, "WIFI DIRECT" to true)
        val best = engine.evaluateBestTransport(availMap)
        assertEquals("WIFI DIRECT", best)
    }

    @Test
    fun testAutoConnectPeerTrustGate() {
        val engine = SmartConnectEngine(
            autoConnectEnabled = true,
            requirePeerVerification = true
        )
        val peer = PeerNode(endpointId = "NODE_UNKNOWN", name = "UNKNOWN_NODE", route = "NEARBY")

        // Unknown unverified peer must be gated
        val decision = engine.evaluateAutoConnect(peer, isPeerFingerprintVerified = false)
        assertEquals(AutoConnectDecision.WAITING_VERIFICATION, decision)

        // Once verified, auto-connect is permitted
        val verifiedDecision = engine.evaluateAutoConnect(peer, isPeerFingerprintVerified = true)
        assertEquals(AutoConnectDecision.ALLOW_AUTO_CONNECT, verifiedDecision)
    }
}
