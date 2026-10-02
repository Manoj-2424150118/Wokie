package com.wokie.message.mesh

import org.junit.Assert.*
import org.junit.Test

class NearbyDiscoveryManagerTests {

    private fun createManager(nearbyMode: Boolean = true): NearbyDiscoveryManager {
        val prefs = mutableMapOf<String, Any>("nearby_mode" to nearbyMode)
        return NearbyDiscoveryManager(
            saveConfig = { key, value -> prefs[key] = value },
            getConfig = { key, def -> prefs[key] ?: def }
        )
    }

    @Test
    fun `Test rate limiting of incoming requests`() {
        val manager = createManager(true)
        manager.startDiscovery()
        
        // First request should succeed
        assertTrue(manager.handleIncomingRequest("endpoint-1", "TEMP-A1"))
        
        // Immediate second request from same ID should fail
        assertFalse(manager.handleIncomingRequest("endpoint-1", "TEMP-A1"))
        
        // Request from different ID should succeed
        assertTrue(manager.handleIncomingRequest("endpoint-2", "TEMP-B2"))
    }
    
    @Test
    fun `Test malformed payload rejection`() {
        val manager = createManager(true)
        manager.startDiscovery()
        
        // Valid payload
        manager.onPeerDiscovered("endpoint-1", "WOKIE|1.0|TEMP1234")
        assertEquals(1, manager.getDiscoveredPeers().size)
        
        // Malformed - no protocol
        manager.onPeerDiscovered("endpoint-2", "WOKIE|TEMP1234")
        assertEquals(1, manager.getDiscoveredPeers().size)
        
        // Malformed - wrong protocol
        manager.onPeerDiscovered("endpoint-3", "WOKIE|2.0|TEMP1234")
        assertEquals(1, manager.getDiscoveredPeers().size)
        
        // Malformed - bad format
        manager.onPeerDiscovered("endpoint-4", "GARBAGE_DATA")
        assertEquals(1, manager.getDiscoveredPeers().size)
    }

    @Test
    fun `Test stale peer removal`() {
        val manager = createManager(true)
        manager.startDiscovery()
        
        manager.onPeerDiscovered("endpoint-1", "WOKIE|1.0|TEMP1")
        assertEquals(1, manager.getDiscoveredPeers().size)
        
        // Force the peer to be old
        val peer = manager.getDiscoveredPeers()[0]
        peer.lastSeenTimeMs = System.currentTimeMillis() - 40000 // 40 seconds ago
        
        // Next get should be empty
        assertEquals(0, manager.getDiscoveredPeers().size)
    }
}
