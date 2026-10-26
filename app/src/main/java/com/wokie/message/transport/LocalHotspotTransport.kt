package com.wokie.message.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

enum class HotspotState {
    IDLE,
    REQUESTING_PERMISSION,
    STARTING,
    ACTIVE,
    STOPPING
}

@SuppressLint("MissingPermission")
class LocalHotspotTransport(
    context: Context?,
    val localNodeId: String,
    private val capabilityCheckerOverride: HotspotCapabilityChecker? = null,
    private val wifiManagerOverride: WifiManager? = null
) : WokieTransport {

    private val appContext: Context? = context?.applicationContext
    val capabilityChecker: HotspotCapabilityChecker = capabilityCheckerOverride ?: HotspotCapabilityChecker(appContext)
    private val wifiManager: WifiManager? by lazy {
        wifiManagerOverride ?: appContext?.getSystemService(Context.WIFI_SERVICE) as WifiManager?
    }

    private val state = AtomicReference(HotspotState.IDLE)
    val currentState: HotspotState get() = state.get()

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var currentStatus: HotspotCapabilityStatus = HotspotCapabilityStatus.READY
        private set

    var onHotspotStatusChanged: ((HotspotCapabilityStatus) -> Unit)? = null

    private var onMessageReceived: ((ByteArray) -> Unit)? = null
    private var onConnectedCallback: (() -> Unit)? = null
    private var onDisconnectedCallback: (() -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    @Volatile
    private var isRunning = false

    override fun setReceiver(onMessageReceived: (ByteArray) -> Unit) {
        this.onMessageReceived = onMessageReceived
    }

    override fun setConnectionCallback(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        this.onConnectedCallback = onConnected
        this.onDisconnectedCallback = onDisconnected
    }

    override fun getTransportType(): String = "HOTSPOT"

    override fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val status = capabilityChecker.checkCapability(reservation != null)
        return status == HotspotCapabilityStatus.READY || status == HotspotCapabilityStatus.ALREADY_ACTIVE
    }

    override fun capabilities(): List<TransportCapability> = listOf(
        TransportCapability.DISCOVERY,
        TransportCapability.CONNECTION,
        TransportCapability.BIDIRECTIONAL_DATA,
        TransportCapability.STREAM_DATA,
        TransportCapability.VOICE,
        TransportCapability.OFFLINE
    )

    private fun logD(tag: String, msg: String) {
        try { Log.d(tag, msg) } catch (ignored: Throwable) {}
    }
    private fun logW(tag: String, msg: String) {
        try { Log.w(tag, msg) } catch (ignored: Throwable) {}
    }
    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        try { if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg) } catch (ignored: Throwable) {}
    }

    @Synchronized
    override fun startAdvertising(localNodeId: String) {
        // Enforce lifecycle: Never permit STARTING -> STARTING or STOPPING -> STARTING
        val current = state.get()
        if (current == HotspotState.STARTING || current == HotspotState.ACTIVE) {
            logD("Hotspot", "Hotspot already starting or active, ignoring duplicate start")
            return
        }
        if (current == HotspotState.STOPPING) {
            logD("Hotspot", "Hotspot currently stopping, waiting for IDLE")
            return
        }

        // Capability and permission pre-check
        val capStatus = capabilityChecker.checkCapability(reservation != null)
        currentStatus = capStatus
        if (capStatus != HotspotCapabilityStatus.READY && capStatus != HotspotCapabilityStatus.ALREADY_ACTIVE) {
            logW("Hotspot", "Cannot start hotspot: $capStatus")
            onHotspotStatusChanged?.invoke(capStatus)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            currentStatus = HotspotCapabilityStatus.UNAVAILABLE_ON_DEVICE
            onHotspotStatusChanged?.invoke(currentStatus)
            return
        }

        state.set(HotspotState.STARTING)
        try {
            val mainHandler = try { Handler(Looper.getMainLooper()) } catch (t: Throwable) { null }
            wifiManager?.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    super.onStarted(res)
                    synchronized(this@LocalHotspotTransport) {
                        if (state.get() == HotspotState.STOPPING || state.get() == HotspotState.IDLE) {
                            try { res.close() } catch (ignored: Exception) {}
                            return
                        }
                        reservation = res
                        state.set(HotspotState.ACTIVE)
                        currentStatus = HotspotCapabilityStatus.READY
                    }
                    startServer()
                    onHotspotStatusChanged?.invoke(HotspotCapabilityStatus.READY)
                }

                override fun onStopped() {
                    super.onStopped()
                    cleanup()
                    onHotspotStatusChanged?.invoke(HotspotCapabilityStatus.READY)
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    logE("Hotspot", "LocalOnlyHotspot failed with reason: $reason")
                    cleanup()
                    currentStatus = HotspotCapabilityStatus.UNAVAILABLE_ON_DEVICE
                    onHotspotStatusChanged?.invoke(currentStatus)
                }
            }, mainHandler)
        } catch (e: SecurityException) {
            logE("Hotspot", "SecurityException starting hotspot: ${e.message}")
            cleanup()
            currentStatus = HotspotCapabilityStatus.PERMISSION_REQUIRED
            onHotspotStatusChanged?.invoke(currentStatus)
        } catch (e: Throwable) {
            logE("Hotspot", "Exception starting hotspot: ${e.message}")
            cleanup()
            currentStatus = HotspotCapabilityStatus.UNAVAILABLE_ON_DEVICE
            onHotspotStatusChanged?.invoke(currentStatus)
        }
    }

    override fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit) {
        // Hotspot peers join network directly
    }

    override fun stopDiscovery() {
    }

    override fun requestConnection(endpointId: String) {
        startClient(endpointId)
    }

    override fun acceptConnection(endpointId: String) {
    }

    override fun rejectConnection(endpointId: String) {
    }

    @Synchronized
    override fun disconnect() {
        cleanup()
        onDisconnectedCallback?.invoke()
    }

    private fun cleanup() {
        val prev = state.getAndSet(HotspotState.STOPPING)
        if (prev == HotspotState.IDLE) {
            state.set(HotspotState.IDLE)
            return
        }

        isRunning = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reservation?.close()
            }
        } catch (e: Exception) {
            logW("Hotspot", "Error closing reservation: ${e.message}")
        }
        reservation = null

        try { clientSocket?.close() } catch (ignored: Exception) {}
        try { serverSocket?.close() } catch (ignored: Exception) {}
        clientSocket = null
        serverSocket = null
        inputStream = null
        outputStream = null

        state.set(HotspotState.IDLE)
    }

    override fun send(packet: ByteArray) {
        thread {
            try {
                // 4-byte length-prefixed framing (see PacketFraming). The old
                // single-byte size corrupted every packet over 255 bytes.
                outputStream?.let { PacketFraming.writePacket(it, packet) }
            } catch (e: Exception) {
                logE("Hotspot", "Send failed", e)
            }
        }
    }

    private fun startServer() {
        thread {
            try {
                serverSocket = ServerSocket(8889)
                clientSocket = serverSocket?.accept()
                setupStreams()
            } catch (e: Exception) {
                logE("Hotspot", "Server error", e)
            }
        }
    }

    private fun startClient(hostAddress: String) {
        thread {
            try {
                clientSocket = Socket(hostAddress, 8889)
                setupStreams()
            } catch (e: Exception) {
                logE("Hotspot", "Client error", e)
            }
        }
    }

    private fun setupStreams() {
        inputStream = clientSocket?.getInputStream()
        outputStream = clientSocket?.getOutputStream()
        isRunning = true
        onConnectedCallback?.invoke()

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
