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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import com.wokie.message.ui.theme.LocalWokieColors
import com.wokie.message.ui.theme.WokieSpacing
import com.wokie.message.ui.theme.WokieTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

data class Message(
    val text: String,
    val isSent: Boolean,
    /** Wall-clock time the message was sent/received; shown in the bubble. */
    val timestamp: Long = System.currentTimeMillis()
)

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

@OptIn(ExperimentalMaterial3Api::class)
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

    // ------------------------------------------------------------------
    // Material 3 UI (design-system overhaul). All session/transport logic
    // above is unchanged — this section only renders state and calls the
    // same handlers as before (startConnectionToPeer / endCurrentSession /
    // coordinator.send with the identical envelope construction).
    // ------------------------------------------------------------------
    val snackbarHostState = remember { SnackbarHostState() }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    // Snackbar feedback for the key session transitions.
    LaunchedEffect(connectionState, sessionState) {
        val message = when {
            connectionState == "SECURE_SESSION" ->
                "Secure session established — messages are end-to-end encrypted"
            connectionState == "INVALID_SESSION" ->
                "Session became invalid. Encryption keys were reset."
            sessionState == SessionState.TRANSPORT_LOST ->
                "Connection to the peer was lost"
            sessionState == SessionState.SESSION_EXPIRED && connectionState == "IDLE" ->
                "Session expired — reconnect to keep chatting"
            else -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
        }
    }

    // Keep the newest message in view as the conversation grows.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    fun sendCurrentMessage() {
        if (inputText.isBlank() || !encryptionManager.isReady) return
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

    val isPeerListVisible = connectionState == "IDLE" || connectionState == "DISCONNECTED" ||
        connectionState == "SESSION_EXPIRED" || connectionState == "NONE" ||
        connectionState == "INVALID_SESSION"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Wokie", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Offline mesh · ${transportDisplayName(preferredTransport)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (connectionState == "SECURE_SESSION") {
                MessageInputBar(
                    text = inputText,
                    onTextChange = { inputText = it },
                    onSend = { sendCurrentMessage() },
                    canSend = inputText.isNotBlank()
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = WokieSpacing.ScreenHorizontal)
        ) {
            Spacer(modifier = Modifier.height(WokieSpacing.Small))
            ConnectionStatusBanner(
                connectionState = connectionState,
                activeRoute = activeRoute,
                preferredTransport = preferredTransport,
                connectedPeerName = connectedPeerName,
                availablePeerCount = discoveredPeers.count { it.status == "AVAILABLE" },
                sessionStarted = sessionStarted,
                timeRemaining = timeRemaining,
                onDisconnect = { showDisconnectConfirm = true }
            )
            Spacer(modifier = Modifier.height(WokieSpacing.Medium))

            Box(modifier = Modifier.weight(1f)) {
                when {
                    isPeerListVisible -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (sessionState == SessionState.SESSION_EXPIRED) {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(WokieSpacing.Medium),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Filled.Info,
                                            contentDescription = null,
                                            tint = LocalWokieColors.current.warning
                                        )
                                        Spacer(modifier = Modifier.width(WokieSpacing.Small))
                                        Text(
                                            "Your previous session ended. Reconnect to start a new encrypted session.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(WokieSpacing.Medium))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Nearby peers", style = MaterialTheme.typography.titleMedium)
                                if (discoveredPeers.isNotEmpty()) {
                                    Text(
                                        "${discoveredPeers.size} found",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(WokieSpacing.Small))

                            if (discoveredPeers.isEmpty()) {
                                EmptyPeersState(
                                    transportName = transportDisplayName(preferredTransport),
                                    isExperimental = isExperimentalTransport(preferredTransport),
                                    onOpenSettings = { showSettings = true },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(WokieSpacing.Small)
                                ) {
                                    items(discoveredPeers) { peer ->
                                        PeerRow(peer = peer, onConnect = { startConnectionToPeer(peer) })
                                    }
                                }
                            }
                        }
                    }

                    connectionState == "CONNECTING" || connectionState == "KEY_EXCHANGE" -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(WokieSpacing.Medium))
                            Text("Establishing secure session", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(WokieSpacing.Small))
                            Text(
                                text = if (connectionState == "CONNECTING") {
                                    "Connecting to $connectedPeerName…"
                                } else {
                                    "Exchanging encryption keys (ECDH)…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(WokieSpacing.Large))
                            TextButton(onClick = { endCurrentSession(markExpired = false) }) {
                                Text("Cancel")
                            }
                        }
                    }

                    connectionState == "FINGERPRINT_VERIFICATION" -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(WokieSpacing.Medium))
                            Text("Verify your peer", style = MaterialTheme.typography.headlineSmall)
                            Spacer(modifier = Modifier.height(WokieSpacing.Small))
                            Text(
                                "Compare this code with the one on $connectedPeerName's phone. " +
                                    "Only continue if they match exactly — this is what stops a man-in-the-middle.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(WokieSpacing.Large))
                            Card(
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(WokieSpacing.Medium),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "PEER FINGERPRINT",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(WokieSpacing.Small))
                                    Text(
                                        peerFingerprint.chunked(4).joinToString(" "),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontFamily = FontFamily.Monospace,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(WokieSpacing.ExtraLarge))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(WokieSpacing.Small)
                            ) {
                                OutlinedButton(
                                    onClick = { endCurrentSession(markExpired = true) },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Don't match")
                                }
                                Button(
                                    onClick = {
                                        connectionState = "SECURE_SESSION"
                                        sessionStarted = true
                                        coordinator.engine.verifiedPeers.add(connectedPeerName)
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                    Spacer(modifier = Modifier.width(WokieSpacing.ExtraSmall))
                                    Text("They match")
                                }
                            }
                        }
                    }

                    connectionState == "SECURE_SESSION" -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(vertical = WokieSpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(WokieSpacing.ExtraSmall)
                        ) {
                            item {
                                val caps = coordinator.getActiveTransportCapabilities()
                                if (caps.contains(TransportCapability.VOICE)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(24.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(
                                                    horizontal = WokieSpacing.Medium,
                                                    vertical = WokieSpacing.Small
                                                ),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Filled.Call,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = LocalWokieColors.current.warning
                                                )
                                                Spacer(modifier = Modifier.width(WokieSpacing.Small))
                                                Text(
                                                    "Voice push-to-talk is experimental",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (messages.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(WokieSpacing.Large),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "Say hello — everything you send is end-to-end encrypted.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                            items(messages) { msg ->
                                MessageBubble(msg)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("End secure session?") },
            text = {
                Text(
                    "You'll disconnect from $connectedPeerName. To chat again you'll need to " +
                        "reconnect and verify the fingerprint."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    endCurrentSession(markExpired = true)
                }) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Honest connection status banner: never claims more than the state machine
 * knows. Shows the live state, the transport actually in use, and — during a
 * session — the peer, remaining session time and a disconnect action.
 */
@Composable
fun ConnectionStatusBanner(
    connectionState: String,
    activeRoute: String?,
    preferredTransport: String,
    connectedPeerName: String,
    availablePeerCount: Int,
    sessionStarted: Boolean,
    timeRemaining: Int,
    onDisconnect: () -> Unit
) {
    val extras = LocalWokieColors.current
    val isSecure = connectionState == "SECURE_SESSION"
    val isConnecting = connectionState == "CONNECTING" || connectionState == "KEY_EXCHANGE"
    val routeReady = activeRoute != null && activeRoute != "NONE"

    val icon = when {
        isSecure -> Icons.Filled.Lock
        isConnecting -> Icons.Filled.Refresh
        routeReady -> Icons.Filled.Check
        else -> Icons.Filled.Search
    }
    val iconTint = when {
        isSecure -> extras.success
        isConnecting -> extras.warning
        else -> MaterialTheme.colorScheme.primary
    }
    val title = when {
        isSecure -> "Secure session"
        isConnecting -> "Connecting…"
        routeReady -> "Network ready"
        else -> "Searching for peers"
    }
    val subtitle = when {
        isSecure -> "$connectedPeerName · via ${transportDisplayName(activeRoute ?: "")}"
        isConnecting -> "Finding the best route and securing the session…"
        routeReady -> "via ${transportDisplayName(activeRoute ?: "")} · preferred: ${transportDisplayName(preferredTransport)}"
        else -> "${transportDisplayName(preferredTransport)} · $availablePeerCount peers available"
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = WokieSpacing.Medium, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = iconTint)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSecure && sessionStarted) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = "${timeRemaining / 60}:${(timeRemaining % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (timeRemaining < 30) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(WokieSpacing.Small))
                TextButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}

/** Empty state for the peer hub: explains what to do instead of a blank list. */
@Composable
fun EmptyPeersState(
    transportName: String,
    isExperimental: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(WokieSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(WokieSpacing.Medium))
        Text("No peers found yet", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(WokieSpacing.Small))
        Text(
            "Open Wokie on the other phone, keep both phones close, and make sure both use " +
                "the same transport ($transportName). Discovery can take a few seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (isExperimental) {
            Spacer(modifier = Modifier.height(WokieSpacing.Small))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalWokieColors.current.warning
                )
                Spacer(modifier = Modifier.width(WokieSpacing.ExtraSmall))
                Text(
                    "$transportName is experimental — Nearby is the reliable path.",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalWokieColors.current.warning,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.height(WokieSpacing.Medium))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(WokieSpacing.Small))
            Text(
                "Scanning…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(WokieSpacing.Medium))
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text("Transport settings")
        }
    }
}

/** One discovered peer: avatar initials, honest status, connect action. */
@Composable
fun PeerRow(peer: PeerNode, onConnect: () -> Unit) {
    val extras = LocalWokieColors.current
    val canConnect = peer.status == "AVAILABLE" || peer.status == "SESSION_EXPIRED"
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PeerAvatar(name = peer.name)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "via ${transportDisplayName(peer.route)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (peer.status) {
                    "SESSION_EXPIRED" -> Text(
                        "Session expired",
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.warning
                    )
                    "CONNECTING" -> Text(
                        "Connecting…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    "LOST" -> Text(
                        "Connection lost",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            FilledTonalButton(
                onClick = onConnect,
                enabled = canConnect,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (peer.status == "SESSION_EXPIRED") "Reconnect" else "Connect")
            }
        }
    }
}

@Composable
fun PeerAvatar(name: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                peerInitials(name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** Messenger-style input bar: rounded field + round send button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = WokieSpacing.Small),
            verticalAlignment = Alignment.Bottom
        ) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(WokieSpacing.Small))
            FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send message")
            }
        }
    }
}

/** Friendly display name for a transport id used across settings/coordinator. */
fun transportDisplayName(transport: String): String = when {
    transport.equals("NEARBY", ignoreCase = true) -> "Nearby"
    transport.contains("BLE", ignoreCase = true) -> "BLE Mesh"
    transport.contains("WIFI", ignoreCase = true) -> "Wi-Fi Direct"
    transport.contains("HOTSPOT", ignoreCase = true) -> "Local Hotspot"
    transport.contains("CELLULAR", ignoreCase = true) -> "Cellular"
    transport.contains("INTERNET", ignoreCase = true) -> "Internet relay"
    transport.contains("SATELLITE", ignoreCase = true) -> "Satellite"
    transport.isBlank() -> "—"
    else -> transport
}

/**
 * Honest capability labelling (mirrors the Settings transport list): only
 * Nearby is a verified path; every other transport is experimental.
 */
fun isExperimentalTransport(transport: String): Boolean =
    !transport.equals("NEARBY", ignoreCase = true)

private fun peerInitials(name: String): String {
    val parts = name.trim().split(Regex("[\\s_\\-]+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

private fun formatMessageTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

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

    val isSent = message.isSent
    // Messenger-style tail: the small corner sits on the sender's side, bottom.
    val bubbleShape = if (isSent) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }
    val containerColor = if (isSent) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isSent) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = WokieSpacing.ExtraSmall),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(containerColor, bubbleShape)
                .padding(horizontal = 14.dp, vertical = WokieSpacing.Small),
            horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
        ) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                fontFamily = if (isDecrypting) FontFamily.Monospace else FontFamily.Default
            )
            Text(
                text = formatMessageTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

fun generateRandomHex(length: Int): String {
    val chars = "0123456789ABCDEF"
    return (1..length).map { chars.random() }.joinToString("")
}
