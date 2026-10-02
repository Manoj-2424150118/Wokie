package com.wokie.message.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wokie.message.security.AppLockManager
import com.wokie.message.security.AuthMethod
import com.wokie.message.security.LockTimeout
import com.wokie.message.security.PrivacyManager
import com.wokie.message.mesh.NearbyDiscoveryManager
import com.wokie.message.transport.HotspotCapabilityStatus

@Composable
fun SettingsScreen(
    appLockManager: AppLockManager,
    privacyManager: PrivacyManager,
    discoveryManager: NearbyDiscoveryManager = remember { NearbyDiscoveryManager() },
    activeTransportType: String,
    hotspotStatus: HotspotCapabilityStatus = HotspotCapabilityStatus.READY,
    smartFallback: Boolean = true,
    onSmartFallbackChanged: (Boolean) -> Unit = {},
    autoConnect: Boolean = false,
    onAutoConnectChanged: (Boolean) -> Unit = {},
    activeRoute: String = "NONE",
    activePeer: String = "NONE",
    sessionStateText: String = "IDLE",
    onTransportChanged: (String) -> Unit,
    onExport: () -> Unit,
    onSelfDestruct: () -> Unit,
    onBack: () -> Unit
) {
    var isAppLockEnabled by remember { mutableStateOf(appLockManager.isAppLockEnabled) }
    var authMethod by remember { mutableStateOf(appLockManager.authMethod) }
    var lockTimeout by remember { mutableStateOf(appLockManager.lockTimeout) }
    var destroySession by remember { mutableStateOf(appLockManager.destroySessionOnLock) }
    
    var hideNotifications by remember { mutableStateOf(privacyManager.hideNotificationContent) }
    var blockScreenshots by remember { mutableStateOf(privacyManager.blockScreenshots) }
    var clipboardProtection by remember { mutableStateOf(privacyManager.clipboardProtection) }

    var nearbyMode by remember { mutableStateOf(discoveryManager.nearbyModeEnabled) }
    var visibleToOthers by remember { mutableStateOf(discoveryManager.visibleToOthers) }
    var discoveryDuration by remember { mutableStateOf(discoveryManager.discoveryDurationMinutes) }
    var autoAccept by remember { mutableStateOf(discoveryManager.autoAccept) }
    
    var showPinDialog by remember { mutableStateOf(false) }
    var pinDialogMode by remember { mutableStateOf("CREATE") } // "CREATE", "CHANGE"
    var showDiagnostics by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                Text("BACK")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text("WOKIE SETTINGS", color = Color(0xFF00F3FF), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("SECURITY", color = Color.Gray, fontSize = 14.sp)
                Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))
            }
            
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Protect Wokie when opening", color = Color.White)
                        Text(if (isAppLockEnabled) "App Lock ON" else "App Lock OFF", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = isAppLockEnabled,
                        onCheckedChange = { 
                            isAppLockEnabled = it
                            appLockManager.isAppLockEnabled = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F3FF), checkedTrackColor = Color(0xFF00F3FF).copy(alpha = 0.5f))
                    )
                }
            }

            if (isAppLockEnabled) {
                item {
                    Text("Authentication method", color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(
                            onClick = { 
                                authMethod = AuthMethod.DEVICE_CREDENTIAL_OR_BIOMETRIC
                                appLockManager.authMethod = AuthMethod.DEVICE_CREDENTIAL_OR_BIOMETRIC
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (authMethod == AuthMethod.DEVICE_CREDENTIAL_OR_BIOMETRIC) Color(0xFF00F3FF) else Color.DarkGray)
                        ) {
                            Text("Biometric / Device", color = if (authMethod == AuthMethod.DEVICE_CREDENTIAL_OR_BIOMETRIC) Color.Black else Color.White)
                        }
                        Button(
                            onClick = { 
                                if (!appLockManager.hasPinSet()) {
                                    pinDialogMode = "CREATE"
                                    showPinDialog = true
                                } else {
                                    authMethod = AuthMethod.WOKIE_PIN
                                    appLockManager.authMethod = AuthMethod.WOKIE_PIN
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (authMethod == AuthMethod.WOKIE_PIN) Color(0xFF00F3FF) else Color.DarkGray)
                        ) {
                            Text("Wokie PIN", color = if (authMethod == AuthMethod.WOKIE_PIN) Color.Black else Color.White)
                        }
                    }
                    if (authMethod == AuthMethod.WOKIE_PIN && appLockManager.hasPinSet()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                            Text(
                                "Change PIN",
                                color = Color(0xFF00F3FF),
                                fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    pinDialogMode = "CHANGE"
                                    showPinDialog = true
                                }
                            )
                        }
                    }
                }

                item {
                    Text("Lock when", color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    val timeouts = LockTimeout.values()
                    Column {
                        timeouts.forEach { timeout ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    lockTimeout = timeout
                                    appLockManager.lockTimeout = timeout
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = lockTimeout == timeout,
                                    onClick = {
                                        lockTimeout = timeout
                                        appLockManager.lockTimeout = timeout
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00F3FF))
                                )
                                Text(timeout.name.replace("_", " "), color = Color.White)
                            }
                        }
                    }
                }
                
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(), 
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Destroy active E2EE session", color = Color.White)
                            Text("Purge keys when locked", color = Color.Gray, fontSize = 12.sp)
                        }
                        Switch(
                            checked = destroySession,
                            onCheckedChange = { 
                                destroySession = it
                                appLockManager.destroySessionOnLock = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.Red, checkedTrackColor = Color.Red.copy(alpha = 0.5f))
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("NETWORK (PHASE 19)", color = Color.Gray, fontSize = 14.sp)
                Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))
            }
            
            item {
                Text("Preferred Transport", color = Color.White, modifier = Modifier.padding(top = 8.dp))
                data class TransportItem(val id: String, val label: String, val status: String, val isSelectable: Boolean)
                val hotspotStatusText = when (hotspotStatus) {
                    HotspotCapabilityStatus.PERMISSION_REQUIRED -> "PERMISSION REQUIRED"
                    HotspotCapabilityStatus.UNAVAILABLE_ON_DEVICE -> "UNAVAILABLE ON THIS DEVICE"
                    HotspotCapabilityStatus.WIFI_DISABLED -> "WIFI DISABLED"
                    else -> "EXPERIMENTAL"
                }
                val hotspotSelectable = (hotspotStatus != HotspotCapabilityStatus.UNAVAILABLE_ON_DEVICE)
                val transports = listOf(
                    TransportItem("NEARBY", "NEARBY", "READY", true),
                    TransportItem("WIFI DIRECT", "WIFI DIRECT", "EXPERIMENTAL", true),
                    TransportItem("BLE MESH", "BLE MESH", "EXPERIMENTAL", true),
                    TransportItem("HOTSPOT", "HOTSPOT", hotspotStatusText, hotspotSelectable),
                    TransportItem("CELLULAR", "CELLULAR", "EXPERIMENTAL", true),
                    TransportItem("INTERNET", "INTERNET", "NOT CONFIGURED", false),
                    TransportItem("SATELLITE", "SATELLITE", "UNAVAILABLE ON THIS DEVICE", false)
                )
                Column {
                    transports.forEach { t ->
                        val isSelected = activeTransportType.equals(t.id, ignoreCase = true) ||
                                (t.id == "CELLULAR" && activeTransportType.startsWith("CELLULAR", ignoreCase = true)) ||
                                (t.id == "WIFI DIRECT" && (activeTransportType.equals("WIFI", ignoreCase = true) || activeTransportType.equals("WIFI DIRECT", ignoreCase = true)))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (t.isSelectable) {
                                        Modifier.clickable { onTransportChanged(t.id) }
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = if (t.isSelectable) { { onTransportChanged(t.id) } } else null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF00F3FF),
                                    unselectedColor = if (t.isSelectable) Color.Gray else Color.DarkGray,
                                    disabledSelectedColor = Color.DarkGray,
                                    disabledUnselectedColor = Color.DarkGray
                                ),
                                enabled = t.isSelectable
                            )
                            Column {
                                Text(
                                    t.label, 
                                    color = if (t.isSelectable) Color.White else Color.Gray,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    t.status, 
                                    color = when (t.status) {
                                        "READY" -> Color.Green
                                        "EXPERIMENTAL" -> Color.Yellow
                                        else -> Color.Red
                                    }, 
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Smart Fallback", color = Color.White)
                        Text("Auto-fallback to best available transport if preferred fails", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = smartFallback,
                        onCheckedChange = { onSmartFallbackChanged(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F3FF), checkedTrackColor = Color(0xFF00F3FF).copy(alpha = 0.5f))
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto Connect", color = Color.White)
                        Text("Automatically attempt connection to available peers", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = autoConnect,
                        onCheckedChange = { onAutoConnectChanged(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F3FF), checkedTrackColor = Color(0xFF00F3FF).copy(alpha = 0.5f))
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Nearby Mode", color = Color.White)
                        Text("Discovery does not verify identity.", color = Color.Yellow, fontSize = 12.sp)
                    }
                    Switch(
                        checked = nearbyMode, 
                        onCheckedChange = { 
                            nearbyMode = it
                            discoveryManager.nearbyModeEnabled = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F3FF), checkedTrackColor = Color(0xFF00F3FF).copy(alpha = 0.5f))
                    )
                }
            }
            
            if (nearbyMode) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(), 
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Visible to Nearby Users", color = Color.White)
                        }
                        Switch(
                            checked = visibleToOthers,
                            onCheckedChange = { 
                                visibleToOthers = it
                                discoveryManager.visibleToOthers = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F3FF), checkedTrackColor = Color(0xFF00F3FF).copy(alpha = 0.5f))
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(), 
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Accept Requests", color = Color.White)
                            Text("OFF by default", color = Color.Gray, fontSize = 12.sp)
                        }
                        Switch(
                            checked = autoAccept, 
                            onCheckedChange = { 
                                autoAccept = it
                                discoveryManager.autoAccept = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F3FF), checkedTrackColor = Color(0xFF00F3FF).copy(alpha = 0.5f))
                        )
                    }
                }
                
                item {
                    Text("Discovery Duration", color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    val durations = listOf(
                        Pair(10, "10 minutes"), 
                        Pair(30, "30 minutes"), 
                        Pair(0, "Until stopped")
                    )
                    Column {
                        durations.forEach { d ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    discoveryDuration = d.first
                                    discoveryManager.discoveryDurationMinutes = d.first
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = discoveryDuration == d.first,
                                    onClick = {
                                        discoveryDuration = d.first
                                        discoveryManager.discoveryDurationMinutes = d.first
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00F3FF))
                                )
                                Text(d.second, color = Color.White)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showDiagnostics = !showDiagnostics },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141416))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Connection Diagnostics", color = Color(0xFF00F3FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(if (showDiagnostics) "▲" else "▼", color = Color.Gray, fontSize = 12.sp)
                        }
                        if (showDiagnostics) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Preferred: $activeTransportType", color = Color.White, fontSize = 12.sp)
                            Text("Active Route: $activeRoute", color = Color.White, fontSize = 12.sp)
                            Text("Target Peer: $activePeer", color = Color.White, fontSize = 12.sp)
                            Text("Session State: $sessionStateText", color = Color.White, fontSize = 12.sp)
                            Text("Route Health: GOOD", color = Color.Green, fontSize = 12.sp)
                            Text("Fallback Policy: ${if (smartFallback) "PREFERRED + SMART FALLBACK" else "PREFERRED ONLY"}", color = Color.Gray, fontSize = 12.sp)
                            Text("Auto Connect: ${if (autoConnect) "ENABLED" else "DISABLED"}", color = Color.Gray, fontSize = 12.sp)
                            Text("Crypto: ECDH P-256 + AES-GCM-256 (Tink)", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("PRIVACY", color = Color.Gray, fontSize = 14.sp)
                Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hide notification content", color = Color.White)
                    }
                    Switch(
                        checked = hideNotifications,
                        onCheckedChange = { 
                            hideNotifications = it
                            privacyManager.hideNotificationContent = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F3FF), checkedTrackColor = Color(0xFF00F3FF).copy(alpha = 0.5f))
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Block screenshots", color = Color.White)
                        Text("Requires app restart", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = blockScreenshots,
                        onCheckedChange = { 
                            blockScreenshots = it
                            privacyManager.blockScreenshots = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F3FF), checkedTrackColor = Color(0xFF00F3FF).copy(alpha = 0.5f))
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Clipboard protection", color = Color.White)
                    }
                    Switch(
                        checked = clipboardProtection,
                        onCheckedChange = { 
                            clipboardProtection = it
                            privacyManager.clipboardProtection = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F3FF), checkedTrackColor = Color(0xFF00F3FF).copy(alpha = 0.5f))
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("EMERGENCY (PHASE 23)", color = Color.Red, fontSize = 14.sp)
                Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                Button(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("Export Keys & Logs to Logcat", color = Color.White)
                }
            }

            item {
                Button(
                    onClick = onSelfDestruct,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("SELF-DESTRUCT (Wipe & Kill)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showPinDialog) {
        PinDialog(
            mode = pinDialogMode,
            appLockManager = appLockManager,
            onDismiss = { showPinDialog = false },
            onSuccess = {
                authMethod = AuthMethod.WOKIE_PIN
                appLockManager.authMethod = AuthMethod.WOKIE_PIN
                showPinDialog = false
            }
        )
    }
}

@Composable
fun PinDialog(
    mode: String,
    appLockManager: AppLockManager,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var step by remember { mutableStateOf(if (mode == "CHANGE") "VERIFY_CURRENT" else "ENTER_NEW") }
    var pinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == "CHANGE" && step == "VERIFY_CURRENT") "Verify Current PIN" else if (step == "ENTER_NEW") "Create PIN" else "Confirm PIN") },
        text = {
            Column {
                if (step == "VERIFY_CURRENT") {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it },
                        label = { Text("Current PIN") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true
                    )
                } else if (step == "ENTER_NEW") {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it },
                        label = { Text("New PIN") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true
                    )
                } else if (step == "CONFIRM_NEW") {
                    OutlinedTextField(
                        value = confirmPinInput,
                        onValueChange = { confirmPinInput = it },
                        label = { Text("Confirm New PIN") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
                if (errorMessage.isNotEmpty()) {
                    Text(errorMessage, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                errorMessage = ""
                when (step) {
                    "VERIFY_CURRENT" -> {
                        if (appLockManager.verifyPin(pinInput)) {
                            step = "ENTER_NEW"
                            pinInput = ""
                        } else {
                            errorMessage = "Incorrect PIN"
                        }
                    }
                    "ENTER_NEW" -> {
                        if (pinInput.length >= 4) {
                            step = "CONFIRM_NEW"
                        } else {
                            errorMessage = "PIN must be at least 4 characters"
                        }
                    }
                    "CONFIRM_NEW" -> {
                        if (pinInput == confirmPinInput) {
                            appLockManager.setPin(pinInput)
                            onSuccess()
                        } else {
                            errorMessage = "PINs do not match"
                            confirmPinInput = ""
                        }
                    }
                }
            }) {
                Text(if (step == "CONFIRM_NEW") "Save" else "Next")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
