package com.wokie.message.transport

class RedundantTransport(
    private val primary: WokieTransport,
    private val secondary: WokieTransport? = null
) : WokieTransport {

    override fun startAdvertising(localNodeId: String) {
        primary.startAdvertising(localNodeId)
        secondary?.startAdvertising(localNodeId)
    }

    override fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit) {
        primary.startDiscovery(onPeerFound, onPeerLost)
        secondary?.startDiscovery(onPeerFound, onPeerLost)
    }

    override fun stopDiscovery() {
        primary.stopDiscovery()
        secondary?.stopDiscovery()
    }

    override fun requestConnection(endpointId: String) {
        primary.requestConnection(endpointId)
        secondary?.requestConnection(endpointId)
    }

    override fun acceptConnection(endpointId: String) {
        primary.acceptConnection(endpointId)
        secondary?.acceptConnection(endpointId)
    }

    override fun rejectConnection(endpointId: String) {
        primary.rejectConnection(endpointId)
        secondary?.rejectConnection(endpointId)
    }

    override fun disconnect() {
        primary.disconnect()
        secondary?.disconnect()
    }

    override fun send(payload: ByteArray) {
        primary.send(payload)
        secondary?.send(payload)
    }

    override fun setReceiver(onReceive: (ByteArray) -> Unit) {
        // Both transports will fire the same callback.
        // The ReplayProtector/Duplicate Message handler at the E2EE layer
        // will automatically deduplicate identical payloads arriving from both.
        primary.setReceiver(onReceive)
        secondary?.setReceiver(onReceive)
    }

    override fun setConnectionCallback(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        // For MVP redundancy, we fire connected if primary connects.
        // In a real robust system, we would track states of both separately.
        primary.setConnectionCallback(onConnected, onDisconnected)
        
        secondary?.setConnectionCallback(
            onConnected = { 
                // Could notify redundant connection established
            },
            onDisconnected = {
                // Could notify redundant connection lost
            }
        )
    }

    override fun getTransportType(): String {
        return "REDUNDANT(${primary.getTransportType()}" + (if (secondary != null) ", ${secondary.getTransportType()})" else ")")
    }

    override fun isAvailable(): Boolean {
        return primary.isAvailable() || (secondary?.isAvailable() ?: false)
    }

    override fun capabilities(): List<TransportCapability> {
        val caps = mutableSetOf<TransportCapability>()
        caps.addAll(primary.capabilities())
        secondary?.let { caps.addAll(it.capabilities()) }
        return caps.toList()
    }
}
