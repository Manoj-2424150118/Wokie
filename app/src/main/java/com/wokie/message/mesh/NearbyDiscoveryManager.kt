package com.wokie.message.mesh

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

data class DiscoveredPeer(
    val endpointId: String,
    val temporaryDiscoveryId: String,
    val protocolVersion: String = "1.0",
    val capabilities: String = "E2EE",
    var lastSeenTimeMs: Long = System.currentTimeMillis()
)

class NearbyDiscoveryManager(
    private val saveConfig: (String, Any) -> Unit = { _, _ -> },
    private val getConfig: (String, Any) -> Any = { _, def -> def }
) {
    val stateMachine = DiscoveryStateMachine()
    
    // Store peers independently
    private val discoveredPeers = mutableMapOf<String, DiscoveredPeer>()
    
    // Rate Limiting
    private val incomingRequestTimestamps = mutableMapOf<String, Long>()
    private val REQUEST_COOLDOWN_MS = 5000L // 5 seconds per temporary ID
    
    // Discovery Lifetime
    private var discoveryStartTimeMs = 0L

    // Current Temp ID
    var currentTemporaryId: String = UUID.randomUUID().toString().take(8)
        private set

    fun rotateTemporaryId() {
        currentTemporaryId = UUID.randomUUID().toString().take(8)
    }

    var nearbyModeEnabled: Boolean
        get() = getConfig("nearby_mode", false) as Boolean
        set(value) = saveConfig("nearby_mode", value)

    var visibleToOthers: Boolean
        get() = getConfig("visible_to_others", false) as Boolean
        set(value) = saveConfig("visible_to_others", value)

    var autoAccept: Boolean
        get() = getConfig("auto_accept", false) as Boolean
        set(value) = saveConfig("auto_accept", value)

    var discoveryDurationMinutes: Int
        get() = getConfig("discovery_duration", 10) as Int
        set(value) = saveConfig("discovery_duration", value)

    fun startDiscovery() {
        if (!nearbyModeEnabled) return
        rotateTemporaryId()
        discoveryStartTimeMs = System.currentTimeMillis()
        discoveredPeers.clear()
        stateMachine.transitionTo(ConnectionState.DISCOVERING)
        println("DISCOVERY: Started with temp ID: $currentTemporaryId")
    }

    fun stopDiscovery() {
        stateMachine.transitionTo(ConnectionState.IDLE)
        discoveredPeers.clear()
        println("DISCOVERY: Stopped")
    }
    
    fun onPeerDiscovered(endpointId: String, payload: String) {
        if (stateMachine.currentState != ConnectionState.DISCOVERING && stateMachine.currentState != ConnectionState.PEER_FOUND) {
            return
        }
        
        try {
            // Very simple robust parsing without bringing in gson/moshi yet, avoiding complex parsing crash
            val parts = payload.split("|")
            if (parts.size >= 3 && parts[0] == "WOKIE") {
                val protocol = parts[1]
                val tempId = parts[2]
                
                if (protocol != "1.0") return // Ignore invalid protocol version
                
                val peer = DiscoveredPeer(endpointId, tempId, protocol, "E2EE", System.currentTimeMillis())
                discoveredPeers[endpointId] = peer
                
                stateMachine.transitionTo(ConnectionState.PEER_FOUND)
            }
        } catch (e: Exception) {
            println("DISCOVERY: Malformed payload")
        }
    }
    
    fun getDiscoveredPeers(): List<DiscoveredPeer> {
        val now = System.currentTimeMillis()
        // Remove stale peers (not seen for 30 seconds)
        val staleIds = discoveredPeers.filter { (now - it.value.lastSeenTimeMs) > 30000 }.keys
        staleIds.forEach { discoveredPeers.remove(it) }
        return discoveredPeers.values.toList()
    }
    
    fun handleIncomingRequest(endpointId: String, peerTempId: String): Boolean {
        // Rate limiting
        val lastRequest = incomingRequestTimestamps[peerTempId] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastRequest < REQUEST_COOLDOWN_MS) {
            println("DISCOVERY: Rate limited incoming request from $peerTempId")
            return false
        }
        incomingRequestTimestamps[peerTempId] = now
        
        stateMachine.transitionTo(ConnectionState.INCOMING_REQUEST)
        if (autoAccept) {
            acceptConnection()
        }
        return true
    }

    fun requestConnection(endpointId: String) {
        if (discoveredPeers.containsKey(endpointId)) {
            stateMachine.transitionTo(ConnectionState.CONNECTION_REQUESTED)
            // Trigger transport connection
        }
    }

    fun acceptConnection() {
        if (stateMachine.currentState == ConnectionState.INCOMING_REQUEST) {
            stateMachine.transitionTo(ConnectionState.CONNECTION_ACCEPTED)
            stateMachine.transitionTo(ConnectionState.CONNECTING)
        }
    }

    fun declineConnection() {
        if (stateMachine.currentState == ConnectionState.INCOMING_REQUEST) {
            stateMachine.transitionTo(ConnectionState.DISCOVERING)
        }
    }
    
    fun onConnected() {
        if (stateMachine.currentState == ConnectionState.CONNECTING) {
            stateMachine.transitionTo(ConnectionState.CONNECTED)
            stateMachine.transitionTo(ConnectionState.SESSION_ESTABLISHING)
        }
    }
    
    fun generateDiscoveryPayload(): String {
        return "WOKIE|1.0|$currentTemporaryId"
    }
    
    fun checkDurationTimeout() {
        if (discoveryDurationMinutes > 0 && stateMachine.currentState != ConnectionState.IDLE) {
            val elapsed = System.currentTimeMillis() - discoveryStartTimeMs
            if (elapsed > discoveryDurationMinutes * 60 * 1000L) {
                stopDiscovery()
            }
        }
    }
}
