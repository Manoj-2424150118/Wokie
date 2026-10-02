package com.wokie.message.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class RedundantTransportTests {

    class MockTransport : WokieTransport {
        var connectCalls = 0
        var disconnectCalls = 0
        var sendPayloads = mutableListOf<ByteArray>()
        var activeReceiver: ((ByteArray) -> Unit)? = null

        override fun startAdvertising(localNodeId: String) {
            connectCalls++
        }

        override fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit) {
            connectCalls++
        }

        override fun stopDiscovery() {}

        override fun requestConnection(endpointId: String) {
            connectCalls++
        }

        override fun acceptConnection(endpointId: String) {}

        override fun rejectConnection(endpointId: String) {}

        override fun disconnect() {
            disconnectCalls++
        }

        override fun send(payload: ByteArray) {
            sendPayloads.add(payload)
        }

        override fun setReceiver(onReceive: (ByteArray) -> Unit) {
            activeReceiver = onReceive
        }

        override fun setConnectionCallback(onConnected: () -> Unit, onDisconnected: () -> Unit) {}

        override fun getTransportType(): String = "MOCK"

        override fun isAvailable(): Boolean = true

        override fun capabilities(): List<TransportCapability> = emptyList()
    }

    @Test
    fun `send invokes both primary and secondary transports`() {
        val primary = MockTransport()
        val secondary = MockTransport()
        val redundant = RedundantTransport(primary, secondary)

        val payload = "Test Data".toByteArray()
        redundant.send(payload)

        assertEquals(1, primary.sendPayloads.size)
        assertEquals(payload, primary.sendPayloads[0])
        
        assertEquals(1, secondary.sendPayloads.size)
        assertEquals(payload, secondary.sendPayloads[0])
    }
    
    @Test
    fun `connect invokes both primary and secondary transports`() {
        val primary = MockTransport()
        val secondary = MockTransport()
        val redundant = RedundantTransport(primary, secondary)

        redundant.startAdvertising("LOCAL_NODE")

        assertEquals(1, primary.connectCalls)
        assertEquals(1, secondary.connectCalls)
    }
    
    @Test
    fun `receiver callback deduplicates gracefully because of shared dispatch`() {
        val primary = MockTransport()
        val secondary = MockTransport()
        val redundant = RedundantTransport(primary, secondary)

        var receiveCount = 0
        redundant.setReceiver { 
            receiveCount++
        }
        
        // Simulating the same message arriving over both transports
        primary.activeReceiver?.invoke("Data".toByteArray())
        secondary.activeReceiver?.invoke("Data".toByteArray())
        
        // RedundantTransport does not deduplicate; the E2EE layer (ReplayProtector) handles it.
        // Therefore we expect 2 dispatches.
        assertEquals(2, receiveCount)
    }
}
