package com.wokie.message.transport

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.wokie.message.mesh.SmartConnectEngine
import com.wokie.message.mesh.FallbackPolicy
import com.wokie.message.mesh.AutoConnectDecision

enum class TransportState {
    UNAVAILABLE,
    PERMISSION_REQUIRED,
    AVAILABLE_BUT_OFFLINE,
    AVAILABLE,
    ACTIVE,
    CONNECTED
}

data class PeerNode(
    val endpointId: String,
    val name: String,
    val status: String = "AVAILABLE", // AVAILABLE, CONNECTING, CONNECTED, SESSION_EXPIRED, LOST
    val route: String
)

class TransportCoordinator(
    private val context: Context? = null,
    private val localNodeId: String,
    customTransports: List<WokieTransport>? = null,
    val engine: SmartConnectEngine = SmartConnectEngine(),
    private val onMessageReceived: (ByteArray) -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    
    private val transports = mutableListOf<WokieTransport>()
    val enabledTransports = mutableSetOf<String>()
    
    private val _discoveredPeers = MutableStateFlow<List<PeerNode>>(emptyList())
    val discoveredPeers: StateFlow<List<PeerNode>> = _discoveredPeers
    
    private val _activeRoute = MutableStateFlow<String?>("NONE")
    val activeRoute: StateFlow<String?> = _activeRoute

    private val _preferredTransport = MutableStateFlow("NEARBY")
    val preferredTransport: StateFlow<String> = _preferredTransport

    private val _hotspotStatus = MutableStateFlow(HotspotCapabilityStatus.READY)
    val hotspotStatus: StateFlow<HotspotCapabilityStatus> = _hotspotStatus

    private var activeTransport: WokieTransport? = null
    private var isEvaluating = false

    init {
        engine.preferredTransport = _preferredTransport.value

        if (customTransports != null) {
            transports.addAll(customTransports)
        } else if (context != null) {
            val hotspot = LocalHotspotTransport(context, localNodeId)
            hotspot.onHotspotStatusChanged = { status ->
                _hotspotStatus.value = status
            }
            transports.add(NearbyTransport(context, localNodeId))
            transports.add(WifiDirectTransport(context, localNodeId))
            transports.add(hotspot)
            transports.add(BleMeshTransport(context, localNodeId))
            transports.add(InternetTransport(context, localNodeId))
            transports.add(CellularTransport(context, localNodeId))
            transports.add(SatelliteTransport(context, localNodeId))
        }
        
        transports.forEach { t ->
            t.setReceiver { onMessageReceived(it) }
            t.setConnectionCallback(
                onConnected = { 
                    engine.recordRouteSuccess(t.getTransportType())
                    evaluateRoutes() 
                },
                onDisconnected = { 
                    engine.recordRouteFailure(t.getTransportType())
                    evaluateRoutes() 
                }
            )
        }
        
        // Smart health and auto-connect evaluation loop
        scope.launch {
            while (true) {
                delay(3000)
                evaluateRoutes()
            }
        }
    }

    fun findTransport(type: String): WokieTransport? {
        return transports.find { t ->
            t.getTransportType().equals(type, ignoreCase = true) ||
            (type.equals("CELLULAR", ignoreCase = true) && t.getTransportType().startsWith("CELLULAR", ignoreCase = true)) ||
            (type.equals("INTERNET", ignoreCase = true) && t.getTransportType().startsWith("INTERNET", ignoreCase = true)) ||
            (type.equals("WIFI", ignoreCase = true) && t.getTransportType().startsWith("WIFI", ignoreCase = true)) ||
            (type.equals("WIFI DIRECT", ignoreCase = true) && t.getTransportType().startsWith("WIFI", ignoreCase = true)) ||
            (type.equals("HOTSPOT", ignoreCase = true) && t.getTransportType().startsWith("HOTSPOT", ignoreCase = true)) ||
            (type.equals("BLE MESH", ignoreCase = true) && t.getTransportType().equals("BLE MESH", ignoreCase = true))
        }
    }

    fun getTransportState(type: String): TransportState {
        val t = findTransport(type) ?: return TransportState.UNAVAILABLE
        if (!t.isAvailable()) return TransportState.UNAVAILABLE
        if (enabledTransports.contains(t.getTransportType())) return TransportState.ACTIVE
        return TransportState.AVAILABLE
    }

    fun isTransportAvailable(type: String): Boolean {
        return findTransport(type)?.isAvailable() ?: false
    }

    fun setPreferredTransport(type: String) {
        _preferredTransport.value = type
        engine.preferredTransport = type
        
        val target = findTransport(type)
        val canonicalType = target?.getTransportType() ?: type
        
        // Check if preferred transport is available
        val isTargetAvailable = target?.isAvailable() ?: false
        if (isTargetAvailable) {
            val toDisable = enabledTransports.filter { it != canonicalType }
            for (t in toDisable) {
                disableTransport(t)
            }
            enableTransport(canonicalType)
        } else if (engine.fallbackPolicy == FallbackPolicy.PREFERRED_WITH_SMART_FALLBACK) {
            // Smart fallback if preferred is currently unavailable
            val availMap = transports.associate { it.getTransportType() to it.isAvailable() }
            val fallback = engine.evaluateBestTransport(availMap, _activeRoute.value)
            if (fallback != null) {
                val toDisable = enabledTransports.filter { it != fallback }
                for (t in toDisable) {
                    disableTransport(t)
                }
                enableTransport(fallback)
            }
        } else {
            // PREFERRED_ONLY: disable other transports even if target is unavailable
            val toDisable = enabledTransports.filter { it != canonicalType }
            for (t in toDisable) {
                disableTransport(t)
            }
            enableTransport(canonicalType)
        }
    }

    fun setFallbackPolicy(policy: FallbackPolicy) {
        engine.fallbackPolicy = policy
        evaluateRoutes()
    }

    fun setAutoConnect(enabled: Boolean) {
        engine.autoConnectEnabled = enabled
        if (enabled) {
            evaluateRoutes()
        }
    }

    fun enableTransport(type: String) {
        val t = findTransport(type) ?: return
        val canonical = t.getTransportType()
        if (enabledTransports.contains(canonical)) return
        if (t.isAvailable()) {
            enabledTransports.add(canonical)
            t.startAdvertising(localNodeId)
            t.startDiscovery(
                onPeerFound = { id, name -> addPeer(id, name, canonical) },
                onPeerLost = { id -> removePeer(id, canonical) }
            )
            evaluateRoutes()
        }
    }

    fun disableTransport(type: String) {
        val t = findTransport(type) ?: return
        val canonical = t.getTransportType()
        if (!enabledTransports.contains(canonical)) return
        enabledTransports.remove(canonical)
        t.stopDiscovery()
        t.disconnect()
        val filtered = _discoveredPeers.value.filter { it.route != canonical && it.route != type }
        _discoveredPeers.value = filtered
        evaluateRoutes()
    }

    private fun addPeer(id: String, name: String, route: String) {
        val current = _discoveredPeers.value.toMutableList()
        val index = current.indexOfFirst { it.endpointId == id && it.route == route }
        if (index == -1) {
            current.add(PeerNode(id, name, "AVAILABLE", route))
            _discoveredPeers.value = current
        } else {
            // Update status if it was lost
            if (current[index].status == "LOST") {
                current[index] = current[index].copy(status = "AVAILABLE")
                _discoveredPeers.value = current
            }
        }
    }

    private fun removePeer(id: String, route: String) {
        val current = _discoveredPeers.value.toMutableList()
        val existing = current.find { it.endpointId == id && it.route == route }
        if (existing != null) {
            current.remove(existing)
            current.add(existing.copy(status = "LOST"))
            _discoveredPeers.value = current
        }
    }

    fun markPeerSessionExpired(peerId: String) {
        val current = _discoveredPeers.value.toMutableList()
        val idx = current.indexOfFirst { it.endpointId == peerId }
        if (idx != -1) {
            current[idx] = current[idx].copy(status = "SESSION_EXPIRED")
            _discoveredPeers.value = current
        }
    }

    fun markPeerAvailable(peerId: String) {
        val current = _discoveredPeers.value.toMutableList()
        val idx = current.indexOfFirst { it.endpointId == peerId }
        if (idx != -1) {
            current[idx] = current[idx].copy(status = "AVAILABLE")
            _discoveredPeers.value = current
        }
    }

    fun connectTo(peer: PeerNode) {
        if (!engine.canAttemptPeer(peer.endpointId)) {
            return
        }
        val t = transports.find { it.getTransportType() == peer.route }
        if (t != null && enabledTransports.contains(t.getTransportType())) {
            engine.recordPeerAttempt(peer.endpointId)
            t.requestConnection(peer.endpointId)
            activeTransport = t
            _activeRoute.value = t.getTransportType()
        }
    }

    fun disconnect() {
        activeTransport?.disconnect()
        activeTransport = null
        _activeRoute.value = "NONE"
    }

    fun send(packet: ByteArray) {
        activeTransport?.send(packet)
    }
    
    fun getActiveTransportCapabilities(): List<TransportCapability> {
        return activeTransport?.capabilities() ?: emptyList()
    }

    fun evaluateRoutes() {
        if (isEvaluating) return
        isEvaluating = true
        try {
            // 1. Failover / Active route continuity check
            if (activeTransport != null && !enabledTransports.contains(activeTransport!!.getTransportType())) {
                activeTransport?.disconnect()
                activeTransport = null
                _activeRoute.value = "NONE"
            }

            // 2. Preferred Route Return / Fallback evaluation
            if (engine.fallbackPolicy == FallbackPolicy.PREFERRED_WITH_SMART_FALLBACK) {
                val availMap = transports.associate { it.getTransportType() to it.isAvailable() }
                val bestTransport = engine.evaluateBestTransport(availMap, _activeRoute.value)
                if (bestTransport != null && !enabledTransports.contains(bestTransport)) {
                    // If active transport is idle/disconnected, switch to the better candidate
                    if (activeTransport == null) {
                        val toDisable = enabledTransports.filter { it != bestTransport }
                        for (t in toDisable) {
                            disableTransport(t)
                        }
                        enableTransport(bestTransport)
                    }
                }
            }

            // 3. Auto-connect evaluation if enabled and not currently connected
            if (activeTransport == null && engine.autoConnectEnabled) {
                val eligiblePeers = _discoveredPeers.value.filter { it.status == "AVAILABLE" || it.status == "SESSION_EXPIRED" }
                for (peer in eligiblePeers) {
                    val decision = engine.evaluateAutoConnect(peer, isPeerFingerprintVerified = engine.verifiedPeers.contains(peer.endpointId))
                    if (decision == AutoConnectDecision.ALLOW_AUTO_CONNECT) {
                        connectTo(peer)
                        break
                    }
                }
            }
        } finally {
            isEvaluating = false
        }
    }
}
