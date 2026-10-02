package com.wokie.message.transport

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class NearbyTransport(
    private val context: Context,
    val localNodeId: String = "WOKIE_NODE_${(1000..9999).random()}"
) : WokieTransport {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.wokie.message.secure_mesh"
    
    var connectedEndpointId: String? = null
        private set

    private var onMessageReceived: ((ByteArray) -> Unit)? = null
    private var onConnectedCallback: (() -> Unit)? = null
    private var onDisconnectedCallback: (() -> Unit)? = null

    override fun setReceiver(onMessageReceived: (ByteArray) -> Unit) {
        this.onMessageReceived = onMessageReceived
    }

    override fun setConnectionCallback(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        this.onConnectedCallback = onConnected
        this.onDisconnectedCallback = onDisconnected
    }

    override fun getTransportType(): String = "NEARBY"
    
    override fun isAvailable(): Boolean = true

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    if (bytes.isNotEmpty()) {
                        android.util.Log.d("WOKIE_E2EE", "PACKET_RECEIVED type=NEARBY size=${bytes.size}")
                        onMessageReceived?.invoke(bytes)
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
                android.util.Log.d("WOKIE_E2EE", "TRANSPORT_CONNECTED type=NEARBY endpoint=$endpointId")
                connectedEndpointId = endpointId
                onConnectedCallback?.invoke()
            } else {
                android.util.Log.d("WOKIE_E2EE", "connectionResult endpoint=$endpointId status=FAILED")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d("Mesh", "Disconnected from endpoint: $endpointId")
            connectedEndpointId = null
            onDisconnectedCallback?.invoke()
        }
    }

    private var onPeerFoundCallback: ((String, String) -> Unit)? = null
    private var onPeerLostCallback: ((String) -> Unit)? = null

    override fun startAdvertising(localNodeId: String) {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(localNodeId, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
    }

    override fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit) {
        this.onPeerFoundCallback = onPeerFound
        this.onPeerLostCallback = onPeerLost
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                onPeerFoundCallback?.invoke(endpointId, info.endpointName)
            }
            override fun onEndpointLost(endpointId: String) {
                onPeerLostCallback?.invoke(endpointId)
            }
        }, discoveryOptions)
    }

    override fun stopDiscovery() {
        connectionsClient.stopDiscovery()
    }

    override fun requestConnection(endpointId: String) {
        connectionsClient.requestConnection(localNodeId, endpointId, connectionLifecycleCallback)
    }

    override fun acceptConnection(endpointId: String) {
        connectionsClient.acceptConnection(endpointId, payloadCallback)
    }

    override fun rejectConnection(endpointId: String) {
        connectionsClient.rejectConnection(endpointId)
    }

    override fun disconnect() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpointId = null
        onDisconnectedCallback?.invoke()
    }

    override fun send(packet: ByteArray) {
        connectedEndpointId?.let {
            android.util.Log.d("WOKIE_E2EE", "sendPayload endpoint=$it bytes=${packet.size}")
            connectionsClient.sendPayload(it, Payload.fromBytes(packet))
                .addOnSuccessListener {
                    android.util.Log.d("WOKIE_E2EE", "sendPayload success")
                    android.util.Log.d("WOKIE_E2EE", "PACKET_SENT type=NEARBY")
                }
                .addOnFailureListener { e ->
                    android.util.Log.d("WOKIE_E2EE", "sendPayload failure error=${e.message}")
                    android.util.Log.d("WOKIE_E2EE", "PACKET_REJECTED type=NEARBY")
                }
        } ?: run {
            android.util.Log.d("WOKIE_E2EE", "sendPayload failure error=No connected endpoint")
        }
    }

    override fun capabilities(): List<TransportCapability> = listOf(
        TransportCapability.DISCOVERY,
        TransportCapability.CONNECTION,
        TransportCapability.BIDIRECTIONAL_DATA,
        TransportCapability.STREAM_DATA,
        TransportCapability.VOICE,
        TransportCapability.OFFLINE
    )
}
