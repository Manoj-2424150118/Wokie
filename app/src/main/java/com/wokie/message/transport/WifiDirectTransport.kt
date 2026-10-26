package com.wokie.message.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
class WifiDirectTransport(
    private val context: Context,
    val localNodeId: String
) : WokieTransport {

    private val manager: WifiP2pManager? by lazy { context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager? }
    private val channel: WifiP2pManager.Channel? by lazy { manager?.initialize(context, Looper.getMainLooper(), null) }
    private var receiver: BroadcastReceiver? = null
    
    private var onMessageReceived: ((ByteArray) -> Unit)? = null
    private var onConnectedCallback: (() -> Unit)? = null
    private var onDisconnectedCallback: (() -> Unit)? = null
    private var onPeerFoundCallback: ((String, String) -> Unit)? = null
    private var onPeerLostCallback: ((String) -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    @Volatile private var isRunning = false

    override fun setReceiver(onMessageReceived: (ByteArray) -> Unit) {
        this.onMessageReceived = onMessageReceived
    }

    override fun setConnectionCallback(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        this.onConnectedCallback = onConnected
        this.onDisconnectedCallback = onDisconnected
    }

    override fun getTransportType(): String = "WIFI DIRECT"

    override fun isAvailable(): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)
    }

    override fun capabilities(): List<TransportCapability> = listOf(
        TransportCapability.DISCOVERY,
        TransportCapability.CONNECTION,
        TransportCapability.BIDIRECTIONAL_DATA,
        TransportCapability.STREAM_DATA,
        TransportCapability.VOICE,
        TransportCapability.OFFLINE
    )

    override fun startAdvertising(localNodeId: String) {
        // In Wi-Fi Direct, devices are always discoverable if P2P is enabled.
        try {
            val method = manager?.javaClass?.getMethod("setDeviceName", WifiP2pManager.Channel::class.java, String::class.java, WifiP2pManager.ActionListener::class.java)
            method?.invoke(manager, channel, localNodeId, null)
        } catch (e: Exception) {
            Log.e("WifiDirect", "Could not set device name", e)
        }
    }

    override fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit) {
        this.onPeerFoundCallback = onPeerFound
        this.onPeerLostCallback = onPeerLost

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager?.requestPeers(channel) { peers ->
                            peers.deviceList.forEach { device ->
                                if (device.deviceName.startsWith("WOKIE_NODE_")) {
                                    onPeerFoundCallback?.invoke(device.deviceAddress, device.deviceName)
                                }
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            manager?.requestConnectionInfo(channel) { info ->
                                if (info.groupFormed && info.isGroupOwner) {
                                    startServer()
                                } else if (info.groupFormed) {
                                    startClient(info.groupOwnerAddress.hostAddress)
                                }
                            }
                        }
                    }
                }
            }
        }
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)
        manager?.discoverPeers(channel, null)
    }

    override fun stopDiscovery() {
        manager?.stopPeerDiscovery(channel, null)
        try {
            receiver?.let { context.unregisterReceiver(it) }
            receiver = null
        } catch (e: Exception) {}
    }

    override fun requestConnection(endpointId: String) {
        val config = WifiP2pConfig().apply {
            deviceAddress = endpointId
        }
        manager?.connect(channel, config, null)
    }

    override fun acceptConnection(endpointId: String) {
        // Automatic in standard Wifi P2P unless configured otherwise
    }

    override fun rejectConnection(endpointId: String) {
        manager?.cancelConnect(channel, null)
    }

    override fun disconnect() {
        isRunning = false
        manager?.removeGroup(channel, null)
        clientSocket?.close()
        serverSocket?.close()
        clientSocket = null
        serverSocket = null
        onDisconnectedCallback?.invoke()
    }

    override fun send(packet: ByteArray) {
        thread {
            try {
                // 4-byte length-prefixed framing (see PacketFraming). The old
                // single-byte size corrupted every packet over 255 bytes.
                outputStream?.let { PacketFraming.writePacket(it, packet) }
            } catch (e: Exception) {
                Log.e("WifiDirect", "Send failed", e)
            }
        }
    }

    private fun startServer() {
        thread {
            try {
                serverSocket = ServerSocket(8888)
                clientSocket = serverSocket?.accept()
                setupStreams()
            } catch (e: Exception) {
                Log.e("WifiDirect", "Server error", e)
            }
        }
    }

    private fun startClient(hostAddress: String?) {
        if (hostAddress == null) return
        thread {
            try {
                clientSocket = Socket()
                clientSocket?.connect(InetSocketAddress(hostAddress, 8888), 5000)
                setupStreams()
            } catch (e: Exception) {
                Log.e("WifiDirect", "Client error", e)
            }
        }
    }

    private fun setupStreams() {
        inputStream = clientSocket?.getInputStream()
        outputStream = clientSocket?.getOutputStream()
        isRunning = true
        onConnectedCallback?.invoke()
        
        // Read loop (PacketFraming loops until the full packet has arrived;
        // a single read() is not guaranteed to fill the buffer over TCP)
        while (isRunning) {
            try {
                val packet = inputStream?.let { PacketFraming.readPacket(it) } ?: break
                onMessageReceived?.invoke(packet)
            } catch (e: Exception) {
                break
            }
        }
        disconnect()
    }
}
