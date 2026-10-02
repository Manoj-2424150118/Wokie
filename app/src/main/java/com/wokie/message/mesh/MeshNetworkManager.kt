package com.wokie.message.mesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/**
 * Handles off-grid peer-to-peer mesh networking using Nearby Connections API.
 */
class MeshNetworkManager(
    private val context: Context,
    private val onPeerConnected: () -> Unit,
    private val onPeerDisconnected: () -> Unit,
    private val onKeyReceived: (ByteArray) -> Unit,
    private val onMessageReceived: (ByteArray) -> Unit
) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.wokie.message.secure_mesh"
    
    val localNodeId = "WOKIE_NODE_${(1000..9999).random()}"
    var connectedEndpointId: String? = null
        private set

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    if (bytes.isEmpty()) return
                    val packet = com.wokie.message.protocol.WokiePacketParser.parse(bytes)
                    if (packet != null) {
                        when (packet.packetType) {
                            com.wokie.message.protocol.WokiePacketType.KEY_EXCHANGE -> onKeyReceived(packet.payload)
                            com.wokie.message.protocol.WokiePacketType.ENCRYPTED_MESSAGE -> onMessageReceived(packet.payload)
                            else -> {}
                        }
                        return
                    }
                    val type = bytes[0]
                    val data = bytes.copyOfRange(1, bytes.size)
                    when (type) {
                        0x01.toByte() -> {
                            if (com.wokie.message.protocol.WokiePacketParser.validateX509EcPublicKey(data)) {
                                onKeyReceived(data)
                            }
                        }
                        0x02.toByte() -> onMessageReceived(data)
                    }
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                android.util.Log.d("WOKIE_E2EE", "connectionResult endpoint=$endpointId status=OK")
                Log.d("Mesh", "Connected to endpoint: $endpointId")
                connectedEndpointId = endpointId
                onPeerConnected()
            } else {
                android.util.Log.d("WOKIE_E2EE", "connectionResult endpoint=$endpointId status=FAILED")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d("Mesh", "Disconnected from endpoint: $endpointId")
            connectedEndpointId = null
            onPeerDisconnected()
        }
    }

    fun startAdvertising() {
        val advertisingOptions = com.google.android.gms.nearby.connection.AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(localNodeId, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                connectionsClient.requestConnection(localNodeId, endpointId, connectionLifecycleCallback)
            }
            override fun onEndpointLost(endpointId: String) {}
        }, discoveryOptions)
    }

    fun sendPublicKey(keyBytes: ByteArray) {
        val payload = ByteArray(1 + keyBytes.size)
        payload[0] = 0x01
        System.arraycopy(keyBytes, 0, payload, 1, keyBytes.size)
        connectedEndpointId?.let {
            connectionsClient.sendPayload(it, Payload.fromBytes(payload))
        }
    }

    fun sendEncryptedMessage(data: ByteArray) {
        val payload = ByteArray(1 + data.size)
        payload[0] = 0x02
        System.arraycopy(data, 0, payload, 1, data.size)
        connectedEndpointId?.let {
            android.util.Log.d("WOKIE_E2EE", "sendPayload endpoint=$it bytes=${payload.size}")
            connectionsClient.sendPayload(it, Payload.fromBytes(payload))
                .addOnSuccessListener {
                    android.util.Log.d("WOKIE_E2EE", "sendPayload success")
                }
                .addOnFailureListener { e ->
                    android.util.Log.d("WOKIE_E2EE", "sendPayload failure error=${e.message}")
                }
        } ?: run {
            android.util.Log.d("WOKIE_E2EE", "sendPayload failure error=No connected endpoint")
        }
    }

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpointId = null
        onPeerDisconnected()
    }
}
