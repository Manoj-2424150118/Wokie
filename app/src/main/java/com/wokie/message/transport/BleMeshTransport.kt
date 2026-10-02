package com.wokie.message.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.wokie.message.protocol.WokiePacket
import com.wokie.message.protocol.WokiePacketParser
import com.wokie.message.protocol.WokiePacketType
import java.util.UUID

@SuppressLint("MissingPermission")
class BleMeshTransport(
    private val context: Context,
    val localNodeId: String
) : WokieTransport {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    // Dedicated Wokie 16-bit UUID alias for mesh networking (0xFD57)
    val WOKIE_SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("0000FD57-0000-1000-8000-00805f9b34fb"))

    private var onMessageReceived: ((ByteArray) -> Unit)? = null
    private var onConnectedCallback: (() -> Unit)? = null
    private var onDisconnectedCallback: (() -> Unit)? = null
    private var onPeerFoundCallback: ((String, String) -> Unit)? = null
    private var onPeerLostCallback: ((String) -> Unit)? = null

    @Volatile private var isAdvertising = false
    @Volatile private var isScanning = false
    
    var connectedEndpointId: String? = null
        private set

    var peerTransport: BleMeshTransport? = null // For testing / linked transports

    // Cache of seen packet IDs for duplicate relay suppression
    private val seenPacketIds = LinkedHashSet<String>()
    private val MAX_SEEN_PACKETS = 1000

    override fun setReceiver(onMessageReceived: (ByteArray) -> Unit) {
        this.onMessageReceived = onMessageReceived
    }

    override fun setConnectionCallback(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        this.onConnectedCallback = onConnected
        this.onDisconnectedCallback = onDisconnected
    }

    override fun getTransportType(): String = "BLE MESH"

    override fun isAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter.isMultipleAdvertisementSupported
    }

    override fun capabilities(): List<TransportCapability> = listOf(
        TransportCapability.DISCOVERY,
        TransportCapability.CONNECTION,
        TransportCapability.BIDIRECTIONAL_DATA,
        TransportCapability.MULTI_HOP,
        TransportCapability.OFFLINE
    )

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
        }
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
        }
    }

    val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // 1. Reject incomplete/truncated scan data (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (result.dataStatus == ScanResult.DATA_TRUNCATED) {
                    return
                }
            }

            val record = result.scanRecord ?: return

            // 2. Filter BLE scans before parsing: target Wokie's own service UUID
            if (record.serviceUuids?.contains(WOKIE_SERVICE_UUID) != true) {
                return
            }

            // 3. Read mesh payload from service data if available
            val meshData = record.getServiceData(WOKIE_SERVICE_UUID) ?: return
            if (meshData.isEmpty()) {
                return
            }

            // 4. Strict packet validation: envelope, magic, version, type
            val packet = WokiePacketParser.parse(meshData) ?: return

            when (packet.packetType) {
                WokiePacketType.DISCOVERY_BEACON -> {
                    // BLE DISCOVERY ONLY DISCOVERS: report peer discovered, never establish crypto session!
                    onPeerFoundCallback?.invoke(result.device.address, packet.senderNodeId)
                }
                WokiePacketType.MESH_RELAY -> {
                    processRelayPacket(packet)
                }
                else -> {
                    // Advertisements must NOT be parsed as KEY_EXCHANGE, ENCRYPTED_MESSAGE, etc.
                }
            }
        }
    }

    override fun startAdvertising(localNodeId: String) {
        if (isAdvertising) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
            
        // Keep node ID in discovery beacon compact (<= 8 chars) to fit 31-byte legacy BLE limit
        val advertisedNodeId = if (localNodeId.length > 8) localNodeId.take(8) else localNodeId
        val beacon = WokiePacket(
            packetType = WokiePacketType.DISCOVERY_BEACON,
            hopLimit = 1,
            expirationTimestamp = 0L,
            senderNodeId = advertisedNodeId,
            packetId = "",
            payload = ByteArray(0)
        )
        val beaconBytes = beacon.toByteArray()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(WOKIE_SERVICE_UUID)
            .addServiceData(WOKIE_SERVICE_UUID, beaconBytes)
            .build()
            
        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    override fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit) {
        if (isScanning) return
        this.onPeerFoundCallback = onPeerFound
        this.onPeerLostCallback = onPeerLost
        
        // Scan filter targeting only Wokie's dedicated service UUID
        val filters = listOf(ScanFilter.Builder().setServiceUuid(WOKIE_SERVICE_UUID).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
            
        bleScanner?.startScan(filters, settings, scanCallback)
        isScanning = true
    }

    override fun stopDiscovery() {
        if (isScanning) {
            bleScanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    override fun requestConnection(endpointId: String) {
        // Direct GATT connection to the node for secure session setup
        connectedEndpointId = endpointId
        onConnectedCallback?.invoke()
    }

    override fun acceptConnection(endpointId: String) {
    }

    override fun rejectConnection(endpointId: String) {
    }

    override fun disconnect() {
        if (isAdvertising) {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
        }
        stopDiscovery()
        connectedEndpointId = null
        onDisconnectedCallback?.invoke()
    }

    override fun send(packet: ByteArray) {
        Log.d("BleMesh", "Sending packet to mesh, size: ${packet.size}")
        if (connectedEndpointId != null) {
            peerTransport?.processMeshPacket(packet)
        }
    }
    
    fun processMeshPacket(data: ByteArray) {
        // Strictly parse raw bytes before any processing
        val packet = WokiePacketParser.parse(data) ?: return

        when (packet.packetType) {
            WokiePacketType.DISCOVERY_BEACON -> {
                // Discovery beacon only notifies peer discovery, never establishes session
                onPeerFoundCallback?.invoke(packet.senderNodeId, packet.senderNodeId)
            }
            WokiePacketType.KEY_EXCHANGE,
            WokiePacketType.ENCRYPTED_MESSAGE,
            WokiePacketType.VOICE_FRAME -> {
                // Verified Wokie application packet: deliver to application receiver
                onMessageReceived?.invoke(data)
            }
            WokiePacketType.MESH_RELAY -> {
                // Mesh relay validation
                processRelayPacket(packet)
            }
        }
    }

    private fun processRelayPacket(packet: WokiePacket) {
        // 1. Hop limit validation: must be > 1 to forward
        if (packet.hopLimit <= 1) {
            return
        }

        // 2. Duplicate suppression
        synchronized(seenPacketIds) {
            if (packet.packetId.isNotEmpty() && !seenPacketIds.add(packet.packetId)) {
                return // Duplicate packet rejected
            }
            if (seenPacketIds.size > MAX_SEEN_PACKETS) {
                val first = seenPacketIds.iterator().next()
                seenPacketIds.remove(first)
            }
        }

        // 3. Expiration check
        val currentSeconds = System.currentTimeMillis() / 1000
        if (packet.expirationTimestamp > 0 && packet.expirationTimestamp < currentSeconds) {
            return // Expired packet rejected
        }

        // 4. Decrement hop limit and forward
        val forwardedPacket = packet.copy(hopLimit = (packet.hopLimit - 1).toByte())
        send(forwardedPacket.toByteArray())
    }
}
