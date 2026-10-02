package com.wokie.message.mesh

import org.junit.Assert.*
import org.junit.Test

class DiscoveryStateMachineTests {

    @Test
    fun `Test valid transitions`() {
        val sm = DiscoveryStateMachine()
        assertEquals(ConnectionState.IDLE, sm.currentState)

        sm.transitionTo(ConnectionState.DISCOVERING)
        assertEquals(ConnectionState.DISCOVERING, sm.currentState)

        sm.transitionTo(ConnectionState.PEER_FOUND)
        assertEquals(ConnectionState.PEER_FOUND, sm.currentState)

        sm.transitionTo(ConnectionState.CONNECTION_REQUESTED)
        assertEquals(ConnectionState.CONNECTION_REQUESTED, sm.currentState)

        sm.transitionTo(ConnectionState.CONNECTING)
        assertEquals(ConnectionState.CONNECTING, sm.currentState)

        sm.transitionTo(ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, sm.currentState)

        sm.transitionTo(ConnectionState.SESSION_ESTABLISHING)
        assertEquals(ConnectionState.SESSION_ESTABLISHING, sm.currentState)

        sm.transitionTo(ConnectionState.FINGERPRINT_VERIFICATION)
        assertEquals(ConnectionState.FINGERPRINT_VERIFICATION, sm.currentState)

        sm.transitionTo(ConnectionState.CHAT_READY)
        assertEquals(ConnectionState.CHAT_READY, sm.currentState)

        sm.transitionTo(ConnectionState.DISCONNECTED)
        assertEquals(ConnectionState.DISCONNECTED, sm.currentState)
        
        sm.transitionTo(ConnectionState.IDLE)
        assertEquals(ConnectionState.IDLE, sm.currentState)
    }

    @Test
    fun `Test incoming request path`() {
        val sm = DiscoveryStateMachine()
        sm.transitionTo(ConnectionState.DISCOVERING)
        sm.transitionTo(ConnectionState.PEER_FOUND)
        sm.transitionTo(ConnectionState.INCOMING_REQUEST)
        assertEquals(ConnectionState.INCOMING_REQUEST, sm.currentState)

        sm.transitionTo(ConnectionState.CONNECTION_ACCEPTED)
        assertEquals(ConnectionState.CONNECTION_ACCEPTED, sm.currentState)
        
        sm.transitionTo(ConnectionState.CONNECTING)
        assertEquals(ConnectionState.CONNECTING, sm.currentState)
    }

    @Test
    fun `Test incoming request declined`() {
        val sm = DiscoveryStateMachine()
        sm.transitionTo(ConnectionState.DISCOVERING)
        sm.transitionTo(ConnectionState.PEER_FOUND)
        sm.transitionTo(ConnectionState.INCOMING_REQUEST)
        assertEquals(ConnectionState.INCOMING_REQUEST, sm.currentState)

        // Declining takes it back to discovering
        sm.transitionTo(ConnectionState.DISCOVERING)
        assertEquals(ConnectionState.DISCOVERING, sm.currentState)
    }

    @Test
    fun `Test invalid transitions are rejected`() {
        val sm = DiscoveryStateMachine()
        assertEquals(ConnectionState.IDLE, sm.currentState)

        // Cannot go from IDLE directly to CONNECTED
        sm.transitionTo(ConnectionState.CONNECTED)
        assertEquals(ConnectionState.IDLE, sm.currentState)
        
        sm.transitionTo(ConnectionState.DISCOVERING)
        // Cannot go from DISCOVERING directly to CHAT_READY
        sm.transitionTo(ConnectionState.CHAT_READY)
        assertEquals(ConnectionState.DISCOVERING, sm.currentState)
    }
}
