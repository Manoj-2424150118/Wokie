package com.wokie.message.transport

enum class TransportRole {
    HOST,
    CLIENT,
    AUTO
}

enum class TransportCapability {
    DISCOVERY,
    CONNECTION,
    BIDIRECTIONAL_DATA,
    STREAM_DATA,
    VOICE,
    MULTI_HOP,
    INTERNET,
    OFFLINE
}

interface WokieTransport {
    fun startAdvertising(localNodeId: String)
    fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit)
    fun stopDiscovery()
    fun requestConnection(endpointId: String)
    fun acceptConnection(endpointId: String)
    fun rejectConnection(endpointId: String)
    fun disconnect()
    fun send(packet: ByteArray)
    fun setReceiver(onMessageReceived: (ByteArray) -> Unit)
    fun setConnectionCallback(onConnected: () -> Unit, onDisconnected: () -> Unit)
    fun getTransportType(): String
    fun isAvailable(): Boolean
    fun capabilities(): List<TransportCapability>
}
