package com.wokie.message.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

class InternetTransport(
    private val context: Context,
    val localNodeId: String,
    private val relayHost: String = "10.0.2.2",
    private val relayPort: Int = 9000
) : WokieTransport {

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    @Volatile private var isRunning = false

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

    override fun getTransportType(): String = "INTERNET"

    override fun isAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun capabilities(): List<TransportCapability> = listOf(
        TransportCapability.CONNECTION,
        TransportCapability.BIDIRECTIONAL_DATA,
        TransportCapability.STREAM_DATA,
        TransportCapability.VOICE,
        TransportCapability.INTERNET
    )

    override fun startAdvertising(localNodeId: String) {
        // Relay acts as discovery
        connectToRelay()
    }

    override fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit) {
        // Peers are discovered via the relay
        // A real relay would broadcast available nodes. 
        // For MVP, we simulate discovery if connected to relay.
        onPeerFound("INTERNET_PEER", "Remote Peer")
    }

    override fun stopDiscovery() {}

    override fun requestConnection(endpointId: String) {
        // Connection is implicitly through relay
        if (socket?.isConnected == true) {
            onConnectedCallback?.invoke()
        } else {
            connectToRelay()
        }
    }

    override fun acceptConnection(endpointId: String) {}

    override fun rejectConnection(endpointId: String) {}

    override fun disconnect() {
        isRunning = false
        socket?.close()
        socket = null
        onDisconnectedCallback?.invoke()
    }

    override fun send(packet: ByteArray) {
        thread {
            try {
                // Prepend packet size and dest node ID for relay (simplified)
                outputStream?.write(packet.size)
                outputStream?.write(packet)
                outputStream?.flush()
            } catch (e: Exception) {
                Log.e("InternetTransport", "Send failed", e)
            }
        }
    }

    private fun connectToRelay() {
        if (isRunning) return
        thread {
            try {
                socket = Socket(relayHost, relayPort)
                inputStream = socket?.getInputStream()
                outputStream = socket?.getOutputStream()
                isRunning = true
                onConnectedCallback?.invoke()
                
                // Identify self to relay
                val idBytes = localNodeId.toByteArray()
                outputStream?.write(idBytes.size)
                outputStream?.write(idBytes)
                outputStream?.flush()

                val buffer = ByteArray(8192)
                while (isRunning) {
                    try {
                        val size = inputStream?.read() ?: -1
                        if (size == -1) break
                        val bytesRead = inputStream?.read(buffer, 0, size) ?: -1
                        if (bytesRead > 0) {
                            val packet = buffer.copyOfRange(0, bytesRead)
                            onMessageReceived?.invoke(packet)
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("InternetTransport", "Relay connection failed", e)
            } finally {
                disconnect()
            }
        }
    }
}
