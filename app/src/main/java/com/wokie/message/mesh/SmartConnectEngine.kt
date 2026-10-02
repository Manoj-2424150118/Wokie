package com.wokie.message.mesh

import com.wokie.message.transport.PeerNode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

enum class FallbackPolicy {
    PREFERRED_ONLY,
    PREFERRED_WITH_SMART_FALLBACK
}

enum class AutoConnectDecision {
    ALLOW_AUTO_CONNECT,
    WAITING_VERIFICATION,
    BLOCKED_COOLDOWN,
    DISABLED
}

data class RouteScore(
    val transport: String,
    val score: Int,
    val isEligible: Boolean,
    val reason: String
)

/**
 * Deterministic, offline local engine for evaluating transport candidates,
 * scoring routes, managing connection backoff, and gating auto-connection.
 * Fully offline; zero cloud AI dependencies.
 */
class SmartConnectEngine(
    var preferredTransport: String = "NEARBY",
    var fallbackPolicy: FallbackPolicy = FallbackPolicy.PREFERRED_WITH_SMART_FALLBACK,
    var autoConnectEnabled: Boolean = false,
    var requirePeerVerification: Boolean = false
) {
    // Failure tracking for route penalties: transport -> list of failure timestamps
    private val transportFailures = ConcurrentHashMap<String, MutableList<Long>>()
    
    // Per-peer connection attempts and backoff
    private val peerFailureCount = ConcurrentHashMap<String, Int>()
    private val peerLastAttempt = ConcurrentHashMap<String, Long>()
    private val activeConnectingPeers = ConcurrentHashMap.newKeySet<String>()

    // Unusable / insecure transport blacklist
    private val insecureTransports = ConcurrentHashMap.newKeySet<String>()

    // Known / verified peer fingerprints or IDs
    val verifiedPeers = ConcurrentHashMap.newKeySet<String>()

    fun markTransportInsecure(transport: String) {
        insecureTransports.add(canonical(transport))
    }

    fun clearTransportInsecure(transport: String) {
        insecureTransports.remove(canonical(transport))
    }

    fun recordRouteFailure(transport: String) {
        val key = canonical(transport)
        val list = transportFailures.getOrPut(key) { mutableListOf() }
        synchronized(list) {
            list.add(System.currentTimeMillis())
        }
    }

    fun recordRouteSuccess(transport: String) {
        val key = canonical(transport)
        transportFailures[key]?.clear()
        insecureTransports.remove(key)
    }

    fun recordPeerAttempt(peerId: String) {
        activeConnectingPeers.add(peerId)
        peerLastAttempt[peerId] = System.currentTimeMillis()
    }

    fun recordPeerConnected(peerId: String) {
        activeConnectingPeers.remove(peerId)
        peerFailureCount.remove(peerId)
    }

    fun recordPeerFailed(peerId: String, transport: String? = null) {
        activeConnectingPeers.remove(peerId)
        val count = (peerFailureCount[peerId] ?: 0) + 1
        peerFailureCount[peerId] = count
        peerLastAttempt[peerId] = System.currentTimeMillis()
        if (transport != null) {
            recordRouteFailure(transport)
        }
    }

    fun canAttemptPeer(peerId: String, currentTime: Long = System.currentTimeMillis()): Boolean {
        // Enforce single active connection attempt per peer
        if (activeConnectingPeers.contains(peerId)) {
            return false
        }
        val failures = peerFailureCount[peerId] ?: 0
        if (failures == 0) return true

        val lastTime = peerLastAttempt[peerId] ?: 0L
        // Exponential backoff: 1s, 2s, 4s, 8s, max 10s
        val backoffMs = min(1000L * (1 shl min(failures - 1, 3)), 10000L)
        return (currentTime - lastTime) >= backoffMs
    }

    fun scoreTransport(
        transport: String,
        isAvailable: Boolean,
        isActive: Boolean = false,
        currentTime: Long = System.currentTimeMillis()
    ): RouteScore {
        val can = canonical(transport)

        // Hard security requirement: insecure transport is UNUSABLE
        if (insecureTransports.contains(can)) {
            return RouteScore(transport, -1, false, "Security handshake failed (unusable)")
        }

        if (!isAvailable) {
            return RouteScore(transport, -1, false, "Unavailable or missing permissions")
        }

        val baseScore = when (can) {
            "NEARBY" -> 100
            "WIFI DIRECT" -> 85
            "BLE MESH" -> 70
            "HOTSPOT" -> 60
            "CELLULAR" -> 40
            else -> 20
        }

        var total = baseScore

        // Preferred route bonus
        if (can == canonical(preferredTransport)) {
            total += 50
        }

        // Active route continuity bonus
        if (isActive) {
            total += 15
        }

        // Recent failure penalties (failures within last 60 seconds)
        val failures = transportFailures[can] ?: emptyList()
        val recentCount = synchronized(failures) {
            failures.count { currentTime - it < 60_000L }
        }
        val penalty = recentCount * 30
        total -= penalty

        val finalScore = maxOf(0, total)
        val eligible = finalScore > 0
        val reason = if (recentCount > 0) "Score $finalScore (penalized $penalty for recent failures)" else "Score $finalScore"
        return RouteScore(transport, finalScore, eligible, reason)
    }

    fun evaluateBestTransport(
        availableMap: Map<String, Boolean>,
        activeRoute: String? = null,
        currentTime: Long = System.currentTimeMillis()
    ): String? {
        val candidates = listOf("NEARBY", "WIFI DIRECT", "BLE MESH", "HOTSPOT", "CELLULAR")

        if (fallbackPolicy == FallbackPolicy.PREFERRED_ONLY) {
            val pref = canonical(preferredTransport)
            val isAvail = availableMap[pref] ?: false
            val score = scoreTransport(pref, isAvail, pref == activeRoute, currentTime)
            return if (score.isEligible && score.score > 0) pref else null
        }

        // PREFERRED_WITH_SMART_FALLBACK
        val scoredList = candidates.map { t ->
            val avail = availableMap[t] ?: false
            val isActive = activeRoute?.equals(t, ignoreCase = true) == true
            scoreTransport(t, avail, isActive, currentTime)
        }.filter { it.isEligible && it.score > 0 }

        return scoredList.maxByOrNull { it.score }?.transport
    }

    fun evaluateAutoConnect(
        peer: PeerNode,
        isPeerFingerprintVerified: Boolean,
        currentTime: Long = System.currentTimeMillis()
    ): AutoConnectDecision {
        if (!autoConnectEnabled) {
            return AutoConnectDecision.DISABLED
        }

        if (peer.status != "AVAILABLE") {
            return AutoConnectDecision.BLOCKED_COOLDOWN
        }

        if (!canAttemptPeer(peer.endpointId, currentTime)) {
            return AutoConnectDecision.BLOCKED_COOLDOWN
        }

        if (requirePeerVerification && !isPeerFingerprintVerified && !verifiedPeers.contains(peer.endpointId)) {
            return AutoConnectDecision.WAITING_VERIFICATION
        }

        return AutoConnectDecision.ALLOW_AUTO_CONNECT
    }

    private fun canonical(type: String): String {
        val upper = type.uppercase().trim()
        return when {
            upper.startsWith("NEARBY") -> "NEARBY"
            upper.startsWith("WIFI") -> "WIFI DIRECT"
            upper.startsWith("BLE") -> "BLE MESH"
            upper.startsWith("HOTSPOT") -> "HOTSPOT"
            upper.startsWith("CELLULAR") -> "CELLULAR"
            else -> upper
        }
    }
}
