package com.wokie.message

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wokie.message.crypto.EncryptionManager
import com.wokie.message.crypto.ReplayProtector
import com.wokie.message.mesh.MeshRouter
import com.wokie.message.mesh.NearbyDiscoveryManager
import com.wokie.message.mesh.VoiceManager
import com.wokie.message.mesh.FallbackPolicy
import com.wokie.message.protocol.MessageEnvelope
import com.wokie.message.protocol.WokiePacket
import com.wokie.message.protocol.WokiePacketParser
import com.wokie.message.protocol.WokiePacketType
import com.wokie.message.security.AppLockManager
import com.wokie.message.security.PrivacyManager
import com.wokie.message.security.LockState
import com.wokie.message.transport.TransportCapability
import com.wokie.message.transport.TransportCoordinator
import com.wokie.message.transport.PeerNode
import com.wokie.message.transport.HotspotCapabilityStatus
import com.wokie.message.ui.AppLockScreen
import com.wokie.message.ui.SettingsScreen
import java.util.UUID
import androidx.fragment.app.FragmentActivity

enum class SessionState {
    NO_SESSION,
    ACTIVE_SESSION,
    SESSION_EXPIRED,
    SESSION_INVALID,
    TRANSPORT_LOST,
    SESSION_RECONNECTING
}

data class Message(val text: String, val isSent: Boolean)

class MainActivity : FragmentActivity() {
    private lateinit var appLockManager: AppLockManager
    private lateinit var privacyManager: PrivacyManager
    private var lastBackgroundTime: Long = 0

    // Hoisted lock state: owned by the activity so onResume can re-lock by
    // simply flipping this state. The previous implementation called
    // setContent() again from onResume, which destroyed the entire
    // composition — every remember{} in WokieApp (EncryptionManager,
    // TransportCoordinator, chat history, session) was recreated and the
    // old coordinator's transports/coroutines leaked.
    private val lockState = mutableStateOf(LockState.UNLOCKED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        appLockManager = AppLockManager(this)
        privacyManager = PrivacyManager(this)
        lockState.value = if (appLockManager.isAppLockEnabled) LockState.LOCKED else LockState.UNLOCKED

        if (privacyManager.blockScreenshots) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Start the background persistence service (foreground notification +
        // wake lock) so the process — and with it the mesh stack owned by the
        // composition — is not killed while the app is backgrounded. It was
        // previously declared in the manifest but never started anywhere.
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                android.content.Intent(this, com.wokie.message.service.WokieBackgroundService::class.java)
            )
        } catch (e: Exception) {
            android.util.Log.e("WOKIE", "Could not start background service", e)
        }

        setContent {
            WokieTheme {
                WokieApp(this, appLockManager, privacyManager, lockState)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        lastBackgroundTime = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        if (appLockManager.isAppLockEnabled && lastBackgroundTime > 0) {
            val elapsed = System.currentTimeMillis() - lastBackgroundTime
            val timeout = appLockManager.lockTimeout.delayMs
            if (timeout != -1L && elapsed >= timeout) {
                // Re-lock via state only — the composition (and session) survives;
                // WokieApp's lock effect destroys the session only when the
                // user's destroySessionOnLock setting says so.
                lockState.value = LockState.LOCKED
                return
            }
        }
    }
}

@Composable
fun WokieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF050505),
            surface = Color(0xFF111113),
            primary = Color(0xFF00F3FF),
            error = Color(0xFFFF3366)
        ),
        content = content
    )
}

@Composable
fun WokieApp(
    activity: FragmentActivity,
    appLockManager: AppLockManager,
    privacyManager: PrivacyManager,
    lockStateState: MutableState<LockState>
) {
    val context = LocalContext.current
    val encryptionManager = remember { EncryptionManager() }
    val replayProtector = remember { ReplayProtector() }
    val messages = remember { mutableStateListOf<Message>() }
    var inputText by remember { mutableStateOf("") }
    
    var timeRemaining by remember { mutableStateOf(120) }
    var sessionStarted by remember { mutableStateOf(false) }
    
    var connectionState by remember { mutableStateOf("IDLE") }
    var sessionState by remember { mutableStateOf(SessionState.NO_SESSION) }
    var peerFingerprint by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf("") }
    var sendSequenceNumber by remember { mutableStateOf(0L) }
    var connectedPeerName by remember { mutableStateOf("") }
    
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }
    var lockState by lockStateState
    
    var isRecordingVoice by remember { mutableStateOf(false) }
    var isReceivingVoice by remember { mutableStateOf(false) }

    val discoveryPrefs = remember { context.getSharedPreferences("wokie_discovery_prefs", android.content.Context.MODE_PRIVATE) }
    val sharedPrefs = remember { context.getSharedPreferences("wokie_prefs", android.content.Context.MODE_PRIVATE) }
    
    var smartFallback by remember { mutableStateOf(sharedPrefs.getBoolean("pref_smart_fallback", true)) }
    var autoConnect by remember { mutableStateOf(sharedPrefs.getBoolean("pref_auto_connect", false)) }

    val discoveryManager = remember {
        NearbyDiscoveryManager(
            saveConfig = { key, value ->
                when (value) {
                    is Boolean -> discoveryPrefs.edit().putBoolean(key, value).apply()
                    is Int -> discoveryPrefs.edit().putInt(key, value).apply()
                    is String -> discoveryPrefs.edit().putString(key, value).apply()
                }
            },
            getConfig = { key, def ->
                when (def) {
                    is Boolean -> discoveryPrefs.getBoolean(key, def)
                    is Int -> discoveryPrefs.getInt(key, def)
                    is String -> discoveryPrefs.getString(key, def) ?: def
                    else -> def
                }
            }
        )
    }

    val initialTransport = remember {
        discoveryPrefs.getString("pref_transport", null)
            ?: discoveryPrefs.getString("preferred_transport", null)
            ?: sharedPrefs.getString("preferred_transport", "NEARBY")
            ?: "NEARBY"
    }
    var preferredTransport by remember { mutableStateOf(initialTransport) }

    val voiceManager = remember { VoiceManager(context) }
    DisposableEffect(Unit) {
        onDispose { voiceManager.release() }
    }

    val localNodeId = remember { "WOKIE_NODE_${(1000..9999).random()}" }

    val coordinator = remember {
        var coordinatorInstance: TransportCoordinator? = null
        val created = TransportCoordinator(context, localNodeId) { payload ->
            if (payload.isEmpty()) return@TransportCoordinator
            
            // 1. Centralized protocol validation using WokiePacketParser
            val packet = WokiePacketParser.parse(payload)
            if (packet != null) {
                when (packet.packetType) {
                    WokiePacketType.KEY_EXCHANGE -> {
                        val success = encryptionManager.establishSession(packet.payload)
                        if (success) {
                            // Must be the PEER's fingerprint: the old code displayed this
                            // device's OWN key fingerprint, so verification could never match
                            peerFingerprint = encryptionManager.getPeerPublicKeyFingerprint() ?: ""
                            connectionState = "FINGERPRINT_VERIFICATION"
                        } else {
                            // KEY EXCHANGE FAILED: safe error recovery without crashing
                            encryptionManager.resetEphemeralKeys()
                            connectionState = "IDLE"
                            coordinatorInstance?.markPeerAvailable(packet.senderNodeId)
                        }
                    }
                    WokiePacketType.ENCRYPTED_MESSAGE -> {
                        try {
                            val envelope = MessageEnvelope.fromJson(String(packet.payload, Charsets.UTF_8))
                            if (envelope.messageType == "ENCRYPTED_MESSAGE" && envelope.sessionId == sessionId) {
                                if (replayProtector.isMessageValid(envelope.sessionId, envelope.sequenceNumber)) {
                                    val aad = (envelope.sessionId + envelope.messageId + envelope.sequenceNumber).toByteArray(Charsets.UTF_8)
                                    val decrypted = encryptionManager.decrypt(envelope.ciphertext, aad)
                                    if (decrypted != null) {
                                        messages.add(Message(String(decrypted, Charsets.UTF_8), isSent = false))
                                        scope.launch { listState.animateScrollToItem(messages.size - 1) }
                                    } else {
                                        connectionState = "INVALID_SESSION"
                                        sessionState = SessionState.SESSION_INVALID
                                        sessionStarted = false
                                        encryptionManager.resetEphemeralKeys()
                                    }
                                } else {
                                    connectionState = "INVALID_SESSION"
                                    sessionState = SessionState.SESSION_INVALID
                                    sessionStarted = false
                                    encryptionManager.resetEphemeralKeys()
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.d("WOKIE_E2EE", "ENVELOPE_PARSE_FAILED error=${e.message}")
                        }
                    }
                    WokiePacketType.VOICE_FRAME -> {
                        if (connectionState == "SECURE_SESSION") {
                            val aad = (sessionId + "VOICE").toByteArray(Charsets.UTF_8)
                            val decrypted = encryptionManager.decrypt(packet.payload, aad)
                            if (decrypted != null) {
                                isReceivingVoice = true
                                voiceManager.playFrame(decrypted)
                                scope.launch {
                                    delay(300)
                                    isReceivingVoice = false
                                }
                            }
                        }
                    }
                    WokiePacketType.DISCOVERY_BEACON,
                    WokiePacketType.MESH_RELAY -> {
                        // Handled at transport layer
                    }
                }
                return@TransportCoordinator
            }

            // 2. Safe fallback for legacy packet format
            val type = payload[0]
            val data = payload.copyOfRange(1, payload.size)
            
            when (type) {
                0x01.toByte() -> {
                    // Strictly validate key bytes before attempting session establishment
                    if (WokiePacketParser.validateX509EcPublicKey(data)) {
                        val success = encryptionManager.establishSession(data)
                        if (success) {
                            // Must be the PEER's fingerprint: the old code displayed this
                            // device's OWN key fingerprint, so verification could never match
                            peerFingerprint = encryptionManager.getPeerPublicKeyFingerprint() ?: ""
                            connectionState = "FINGERPRINT_VERIFICATION"
                        } else {
                            encryptionManager.resetEphemeralKeys()
                            connectionState = "IDLE"
                        }
                    } else {
                        // Malformed key: ignore safely without crashing
                        android.util.Log.d("WOKIE_E2EE", "IGNORED_MALFORMED_KEY_PACKET")
                    }
                }
                0x02.toByte() -> {
                    try {
                        val envelope = MessageEnvelope.fromJson(String(data, Charsets.UTF_8))
                        if (envelope.messageType == "ENCRYPTED_MESSAGE" && envelope.sessionId == sessionId) {
                            if (replayProtector.isMessageValid(envelope.sessionId, envelope.sequenceNumber)) {
                                val aad = (envelope.sessionId + envelope.messageId + envelope.sequenceNumber).toByteArray(Charsets.UTF_8)
                                val decrypted = encryptionManager.decrypt(envelope.ciphertext, aad)
                                if (decrypted != null) {
                                    messages.add(Message(String(decrypted, Charsets.UTF_8), isSent = false))
                                    scope.launch { listState.animateScrollToItem(messages.size - 1) }
                                } else {
                                    connectionState = "INVALID_SESSION"
                                    sessionState = SessionState.SESSION_INVALID
                                    sessionStarted = false
                                    encryptionManager.resetEphemeralKeys()
                                }
                            } else {
                                connectionState = "INVALID_SESSION"
                                sessionState = SessionState.SESSION_INVALID
                                sessionStarted = false
                                encryptionManager.resetEphemeralKeys()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("WOKIE_E2EE", "ENVELOPE_PARSE_FAILED error=${e.message}")
                    }
                }
                0x03.toByte() -> {
                    if (connectionState == "SECURE_SESSION") {
                        val aad = (sessionId + "VOICE").toByteArray(Charsets.UTF_8)
                        val decrypted = encryptionManager.decrypt(data, aad)
                        if (decrypted != null) {
                            isReceivingVoice = true
                            voiceManager.playFrame(decrypted)
                            scope.launch {
                                delay(300)
                                isReceivingVoice = false
                            }
                        }
                    }
                }
            }
        }
        coordinatorInstance = created
        created
    }

    LaunchedEffect(smartFallback) {
        coordinator.setFallbackPolicy(if (smartFallback) FallbackPolicy.PREFERRED_WITH_SMART_FALLBACK else FallbackPolicy.PREFERRED_ONLY)
    }

    LaunchedEffect(autoConnect) {
        coordinator.setAutoConnect(autoConnect)
    }

    val discoveredPeers by coordinator.discoveredPeers.collectAsState()
    val activeRoute by coordinator.activeRoute.collectAsState()
    val hotspotStatus by coordinator.hotspotStatus.collectAsState()

    fun endCurrentSession(markExpired: Boolean = true) {
        encryptionManager.resetEphemeralKeys()
        sessionId = ""
        peerFingerprint = ""
        sessionStarted = false
        connectionState = "IDLE"
        sessionState = if (markExpired) SessionState.SESSION_EXPIRED else SessionState.NO_SESSION
        coordinator.disconnect()
        if (markExpired && connectedPeerName.isNotEmpty()) {
            coordinator.markPeerSessionExpired(connectedPeerName)
        }
    }

    fun startConnectionToPeer(peer: PeerNode) {
        connectedPeerName = peer.name
        encryptionManager.resetEphemeralKeys()
        sendSequenceNumber = 0L
        connectionState = "CONNECTING"
        sessionState = SessionState.SESSION_RECONNECTING
        coordinator.connectTo(peer)
    }

    LaunchedEffect(lockState) {
        if (lockState == LockState.LOCKED && appLockManager.destroySessionOnLock) {
            messages.clear()
            endCurrentSession(markExpired = false)
        }
    }

    if (lockState == LockState.LOCKED) {
        AppLockScreen(
            activity = activity,
            appLockManager = appLockManager,
            onUnlockSuccess = { lockState = LockState.UNLOCKED }
        )
        return
    }

    // Permission handling
    var hasPermissions by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermissions = results.values.all { it }
        coordinator.setPreferredTransport(preferredTransport)
    }

    fun requestPermissionsForTransport(transport: String) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (transport.equals("HOTSPOT", ignoreCase = true) || transport.equals("WIFI DIRECT", ignoreCase = true)) {
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            (preferredTransport.equals("HOTSPOT", ignoreCase = true) || preferredTransport.equals("WIFI DIRECT", ignoreCase = true))) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            hasPermissions = true
            coordinator.setPreferredTransport(preferredTransport)
        }
    }

    if (showSettings) {
        SettingsScreen(
            appLockManager = appLockManager,
            privacyManager = privacyManager,
            discoveryManager = discoveryManager,
            activeTransportType = preferredTransport,
            hotspotStatus = hotspotStatus,
            smartFallback = smartFallback,
            onSmartFallbackChanged = {
                smartFallback = it
                sharedPrefs.edit().putBoolean("pref_smart_fallback", it).apply()
                coordinator.setFallbackPolicy(if (it) FallbackPolicy.PREFERRED_WITH_SMART_FALLBACK else FallbackPolicy.PREFERRED_ONLY)
            },
            autoConnect = autoConnect,
            onAutoConnectChanged = {
                autoConnect = it
                sharedPrefs.edit().putBoolean("pref_auto_connect", it).apply()
                coordinator.setAutoConnect(it)
            },
            activeRoute = activeRoute ?: "NONE",
            activePeer = if (connectedPeerName.isNotEmpty()) connectedPeerName else "NONE",
            sessionStateText = sessionState.name,
            onTransportChanged = { newTransport ->
                preferredTransport = newTransport
                discoveryPrefs.edit()
                    .putString("pref_transport", newTransport)
                    .putString("preferred_transport", newTransport)
                    .apply()
                sharedPrefs.edit()
                    .putString("preferred_transport", newTransport)
                    .apply()
                requestPermissionsForTransport(newTransport)
                coordinator.setPreferredTransport(newTransport) 
            },
            onExport = {},
            onSelfDestruct = {
                messages.clear()
                endCurrentSession(markExpired = false)
                android.os.Process.killProcess(android.os.Process.myPid())
            },
            onBack = { showSettings = false }
        )
        return
    }

    LaunchedEffect(activeRoute) {
        if (activeRoute != null && activeRoute != "NONE" && (connectionState == "IDLE" || connectionState == "CONNECTING")) {
            connectionState = "KEY_EXCHANGE"
            sessionState = SessionState.ACTIVE_SESSION
        } else if (activeRoute == "NONE") {
            if (sessionState == SessionState.ACTIVE_SESSION) {
                sessionState = SessionState.TRANSPORT_LOST
            }
            connectionState = "IDLE"
            sessionStarted = false
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState == "KEY_EXCHANGE") {
            val keyBytes = encryptionManager.getPublicKeyBytes()
            val wokiePacket = WokiePacket(
                packetType = WokiePacketType.KEY_EXCHANGE,
                senderNodeId = localNodeId,
                packetId = UUID.randomUUID().toString(),
                payload = keyBytes
            )
            coordinator.send(wokiePacket.toByteArray())
        } else if (connectionState == "SECURE_SESSION") {
            timeRemaining = 120
            sessionStarted = true
            sessionState = SessionState.ACTIVE_SESSION
            sessionId = encryptionManager.sessionId
            replayProtector.startNewSession(sessionId)
            while (timeRemaining > 0 && activeRoute != "NONE" && sessionStarted) {
                delay(1000)
                timeRemaining--
            }
            if (activeRoute != "NONE" && sessionStarted) {
                endCurrentSession(markExpired = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("WOKIE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                Text("TRANSPORT: $preferredTransport", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = { showSettings = true }) {
                Text("⚙️", fontSize = 20.sp)
            }
        }

        // Concise Network Status Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111113)),
            border = BorderStroke(1.dp, Color(0xFF222225))
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            connectionState == "SECURE_SESSION" -> {
                                Text("✓ SECURE", color = Color(0xFF00FF66), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            connectionState == "CONNECTING" || connectionState == "KEY_EXCHANGE" -> {
                                Text("↻ CONNECTING", color = Color.Yellow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            activeRoute != null && activeRoute != "NONE" -> {
                                Text("● NETWORK READY", color = Color(0xFF00F3FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            else -> {
                                Text("○ SEARCHING", color = Color.Gray, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    val statusSubtext = when {
                        connectionState == "SECURE_SESSION" -> connectedPeerName
                        connectionState == "CONNECTING" || connectionState == "KEY_EXCHANGE" -> "Finding best route & securing…"
                        activeRoute != null && activeRoute != "NONE" -> "$activeRoute • Preferred ($preferredTransport)"
                        else -> "$preferredTransport • ${discoveredPeers.count { it.status == "AVAILABLE" }} peers available"
                    }
                    Text(statusSubtext, color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (sessionStarted) {
                    Text("TTL: ${timeRemaining}s", color = if (timeRemaining < 30) Color.Red else Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))

        val isPeerListVisible = (connectionState == "IDLE" || connectionState == "DISCONNECTED" || connectionState == "SESSION_EXPIRED" || connectionState == "NONE")

        if (isPeerListVisible) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("AVAILABLE PEERS", color = Color.Gray, fontSize = 14.sp)
                if (sessionState == SessionState.SESSION_EXPIRED) {
                    Text("PREVIOUS SESSION ENDED", color = Color.Yellow, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(discoveredPeers) { peer ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, Color.DarkGray, RoundedCornerShape(8.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color.Black)
                    ) {
                        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(peer.name, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text("Route: ${peer.route}", color = Color.Gray, fontSize = 12.sp)
                                if (peer.status == "SESSION_EXPIRED") {
                                    Text("SESSION EXPIRED", color = Color.Yellow, fontSize = 10.sp)
                                }
                            }
                            Button(
                                onClick = { startConnectionToPeer(peer) },
                                enabled = peer.status == "AVAILABLE" || peer.status == "SESSION_EXPIRED",
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                            ) {
                                Text(
                                    if (peer.status == "SESSION_EXPIRED") "RECONNECT" else "CONNECT",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        } else if (connectionState == "CONNECTING" || connectionState == "KEY_EXCHANGE") {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("ESTABLISHING SECURE SESSION", color = Color.White, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Performing Ephemeral ECDH Key Exchange…", color = Color.Gray, fontSize = 12.sp)
                }
            }
        } else if (connectionState == "FINGERPRINT_VERIFICATION") {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("VERIFY PEER", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text("ECDH_PUB_FP", color = Color.White)
                Text(peerFingerprint, color = MaterialTheme.colorScheme.primary, fontSize = 24.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Compare this fingerprint with the other device\nthrough a trusted channel.", color = Color.Gray, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(modifier = Modifier.height(32.dp))
                
                Row {
                    Button(onClick = { endCurrentSession(markExpired = true) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                        Text("CANCEL")
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(onClick = { 
                        connectionState = "SECURE_SESSION"
                        sessionStarted = true 
                        coordinator.engine.verifiedPeers.add(connectedPeerName)
                    }) {
                        Text("MATCH")
                    }
                }
            }
        } else if (connectionState == "SECURE_SESSION") {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("SECURE SESSION", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("ROUTE: $activeRoute • $connectedPeerName", color = Color.White, fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("● ENCRYPTED", color = Color.Green, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { endCurrentSession(markExpired = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("DISCONNECT", color = Color.Red, fontSize = 11.sp)
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    val caps = coordinator.getActiveTransportCapabilities()
                    if (caps.contains(TransportCapability.VOICE)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            Box(modifier = Modifier
                                .background(Color.DarkGray.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                                .padding(horizontal = 32.dp, vertical = 16.dp)
                            ) {
                                Text(
                                    "VOICE EXPERIMENTAL",
                                    color = Color.Gray,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                items(messages) { msg ->
                    MessageBubble(msg)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Encrypted message...") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.DarkGray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (inputText.isNotEmpty() && encryptionManager.isReady) {
                            val text = inputText
                            inputText = ""
                            
                            val envId = UUID.randomUUID().toString()
                            val currentSeq = ++sendSequenceNumber
                            val aad = (sessionId + envId + currentSeq).toByteArray(Charsets.UTF_8)
                            val encrypted = encryptionManager.encrypt(text.toByteArray(Charsets.UTF_8), aad)
                            if (encrypted != null) {
                                val env = MessageEnvelope(
                                    messageId = envId,
                                    sequenceNumber = currentSeq,
                                    messageType = "ENCRYPTED_MESSAGE",
                                    sessionId = sessionId,
                                    senderNodeId = localNodeId,
                                    ciphertext = encrypted
                                )
                                val serialized = env.toJson().toByteArray(Charsets.UTF_8)
                                val wokiePacket = WokiePacket(
                                    packetType = WokiePacketType.ENCRYPTED_MESSAGE,
                                    senderNodeId = localNodeId,
                                    packetId = envId,
                                    payload = serialized
                                )
                                coordinator.send(wokiePacket.toByteArray())
                                messages.add(Message(text, isSent = true))
                                scope.launch { listState.animateScrollToItem(messages.size) }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("SEND", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    var displayText by remember { mutableStateOf(if (message.isSent) message.text else generateRandomHex(message.text.length + 5)) }
    var isDecrypting by remember { mutableStateOf(!message.isSent) }
    
    if (isDecrypting) {
        LaunchedEffect(message.text) {
            val target = message.text
            val totalIterations = 20
            for (i in 0..totalIterations) {
                delay(30)
                val progress = i.toFloat() / totalIterations
                var currentStr = ""
                for (j in target.indices) {
                    val charProgress = j.toFloat() / target.length
                    if (progress > charProgress + 0.1f) currentStr += target[j]
                    else currentStr += generateRandomHex(1)
                }
                displayText = currentStr
            }
            displayText = target
            isDecrypting = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = if (message.isSent) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isSent) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color(0xFF1E1E24)
            ),
            border = if (message.isSent) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = displayText,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = if (isDecrypting) FontFamily.Monospace else FontFamily.Default
                )
            }
        }
    }
}

fun generateRandomHex(length: Int): String {
    val chars = "0123456789ABCDEF"
    return (1..length).map { chars.random() }.joinToString("")
}
