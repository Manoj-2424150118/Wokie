package com.wokie.message.transport

class FakeTransport : WokieTransport {
    var peer: FakeTransport? = null
    var isConnected = false
    
    private var onMessageReceived: ((ByteArray) -> Unit)? = null
    private var onConnectedCallback: (() -> Unit)? = null
    private var onDisconnectedCallback: (() -> Unit)? = null

    override fun startAdvertising(localNodeId: String) {}
    override fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit) {}
    override fun stopDiscovery() {}

    override fun requestConnection(endpointId: String) {
        if (peer != null && !isConnected) {
            isConnected = true
            peer?.isConnected = true
            onConnectedCallback?.invoke()
            peer?.onConnectedCallback?.invoke()
        }
    }
    
    override fun acceptConnection(endpointId: String) {}
    override fun rejectConnection(endpointId: String) {}

    override fun disconnect() {
        if (isConnected) {
            isConnected = false
            onDisconnectedCallback?.invoke()
            peer?.isConnected = false
            peer?.onDisconnectedCallback?.invoke()
        }
    }

    override fun send(packet: ByteArray) {
        if (isConnected) {
            peer?.onMessageReceived?.invoke(packet)
        }
    }

    override fun setReceiver(onMessageReceived: (ByteArray) -> Unit) {
        this.onMessageReceived = onMessageReceived
    }

    override fun setConnectionCallback(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        this.onConnectedCallback = onConnected
        this.onDisconnectedCallback = onDisconnected
    }

    override fun getTransportType(): String = "FAKE"

    override fun isAvailable(): Boolean = true

    override fun capabilities(): List<TransportCapability> = listOf(TransportCapability.DISCOVERY, TransportCapability.CONNECTION, TransportCapability.BIDIRECTIONAL_DATA)
}
