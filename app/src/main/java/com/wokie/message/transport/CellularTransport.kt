package com.wokie.message.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class CellularTransport(
    private val context: Context,
    localNodeId: String
) : WokieTransport {

    private val internetTransport = InternetTransport(context, localNodeId)

    override fun setReceiver(onMessageReceived: (ByteArray) -> Unit) {
        internetTransport.setReceiver(onMessageReceived)
    }

    override fun setConnectionCallback(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        internetTransport.setConnectionCallback(onConnected, onDisconnected)
    }

    override fun getTransportType(): String = "CELLULAR → INTERNET RELAY"

    override fun isAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    override fun capabilities(): List<TransportCapability> = listOf(
        TransportCapability.CONNECTION,
        TransportCapability.BIDIRECTIONAL_DATA,
        TransportCapability.STREAM_DATA,
        TransportCapability.VOICE,
        TransportCapability.INTERNET
    )

    override fun startAdvertising(localNodeId: String) = internetTransport.startAdvertising(localNodeId)
    override fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit) = internetTransport.startDiscovery(onPeerFound, onPeerLost)
    override fun stopDiscovery() = internetTransport.stopDiscovery()
    override fun requestConnection(endpointId: String) = internetTransport.requestConnection(endpointId)
    override fun acceptConnection(endpointId: String) = internetTransport.acceptConnection(endpointId)
    override fun rejectConnection(endpointId: String) = internetTransport.rejectConnection(endpointId)
    override fun disconnect() = internetTransport.disconnect()
    override fun send(packet: ByteArray) = internetTransport.send(packet)
}
