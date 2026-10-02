package com.wokie.message.transport

import android.content.Context
import android.os.Build

class SatelliteTransport(
    private val context: Context,
    val localNodeId: String
) : WokieTransport {

    override fun setReceiver(onMessageReceived: (ByteArray) -> Unit) {}
    override fun setConnectionCallback(onConnected: () -> Unit, onDisconnected: () -> Unit) {}

    override fun getTransportType(): String = "SATELLITE"

    override fun isAvailable(): Boolean {
        // SatelliteManager is available starting API 34, but FEATURE_TELEPHONY_SATELLITE is the key
        val pm = context.packageManager
        // "android.hardware.telephony.satellite" is the feature flag string in newer APIs
        // For MVP, we check a known constant or fallback to false if not found.
        return if (Build.VERSION.SDK_INT >= 34) {
             pm.hasSystemFeature("android.hardware.telephony.satellite")
        } else {
             false
        }
    }

    override fun capabilities(): List<TransportCapability> = listOf(
        TransportCapability.CONNECTION,
        TransportCapability.BIDIRECTIONAL_DATA,
        TransportCapability.INTERNET
    ) // Low bandwidth, no stream/voice.

    override fun startAdvertising(localNodeId: String) {}
    override fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit) {}
    override fun stopDiscovery() {}
    override fun requestConnection(endpointId: String) {}
    override fun acceptConnection(endpointId: String) {}
    override fun rejectConnection(endpointId: String) {}
    override fun disconnect() {}
    override fun send(packet: ByteArray) {}
}
