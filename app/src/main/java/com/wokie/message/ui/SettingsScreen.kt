package com.wokie.message.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.wokie.message.ui.theme.LocalWokieColors

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
    var showSelfDestructConfirm by remember { mutableStateOf(false) }
    val extras = LocalWokieColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("SECURITY", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
            
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Protect Wokie when opening", color = MaterialTheme.colorScheme.onSurface)
                        Text(if (isAppLockEnabled) "App Lock ON" else "App Lock OFF", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(
                        checked = isAppLockEnabled,
                        onCheckedChange = { 
                            isAppLockEnabled = it
                            appLockManager.isAppLockEnabled = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    )
                }
            }

            if (isAppLockEnabled) {
                item {
                    Text("Authentication method", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp))
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(
                            onClick = { 
                                authMethod = AuthMethod.DEVICE_CREDENTIAL_OR_BIOMETRIC
                                appLockManager.authMethod = AuthMethod.DEVICE_CREDENTIAL_OR_BIOMETRIC
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (authMethod == AuthMethod.DEVICE_CREDENTIAL_OR_BIOMETRIC) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text("Biometric / Device", color = if (authMethod == AuthMethod.DEVICE_CREDENTIAL_OR_BIOMETRIC) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
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
                            colors = ButtonDefaults.buttonColors(containerColor = if (authMethod == AuthMethod.WOKIE_PIN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text("Wokie PIN", color = if (authMethod == AuthMethod.WOKIE_PIN) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (authMethod == AuthMethod.WOKIE_PIN && appLockManager.hasPinSet()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                            Text(
                                "Change PIN",
                                color = MaterialTheme.colorScheme.primary,
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
                    Text("Lock when", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp))
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
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                                Text(timeout.name.replace("_", " "), color = MaterialTheme.colorScheme.onSurface)
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
                            Text("Destroy active E2EE session", color = MaterialTheme.colorScheme.onSurface)
                            Text("Purge keys when locked", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Switch(
                            checked = destroySession,
                            onCheckedChange = { 
                                destroySession = it
                                appLockManager.destroySessionOnLock = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.error, checkedTrackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("NETWORK", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
            
            item {
                Text("Preferred Transport", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp))
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
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = if (t.isSelectable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                                    disabledSelectedColor = MaterialTheme.colorScheme.outlineVariant,
                                    disabledUnselectedColor = MaterialTheme.colorScheme.outlineVariant
                                ),
                                enabled = t.isSelectable
                            )
                            Column {
                                Text(
                                    t.label, 
                                    color = if (t.isSelectable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    t.status, 
                                    color = when (t.status) {
                                        "READY" -> extras.success
                                        "EXPERIMENTAL" -> extras.warning
                                        else -> MaterialTheme.colorScheme.error
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
                        Text("Smart Fallback", color = MaterialTheme.colorScheme.onSurface)
                        Text("Auto-fallback to best available transport if preferred fails", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(
                        checked = smartFallback,
                        onCheckedChange = { onSmartFallbackChanged(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
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
                        Text("Auto Connect", color = MaterialTheme.colorScheme.onSurface)
                        Text("Automatically attempt connection to available peers", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(
                        checked = autoConnect,
                        onCheckedChange = { onAutoConnectChanged(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
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
                        Text("Nearby Mode", color = MaterialTheme.colorScheme.onSurface)
                        Text("Discovery does not verify identity.", color = extras.warning, fontSize = 12.sp)
                    }
                    Switch(
                        checked = nearbyMode, 
                        onCheckedChange = { 
                            nearbyMode = it
                            discoveryManager.nearbyModeEnabled = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
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
                            Text("Visible to Nearby Users", color = MaterialTheme.colorScheme.onSurface)
                        }
                        Switch(
                            checked = visibleToOthers,
                            onCheckedChange = { 
                                visibleToOthers = it
                                discoveryManager.visibleToOthers = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
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
                            Text("Auto-Accept Requests", color = MaterialTheme.colorScheme.onSurface)
                            Text("OFF by default", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Switch(
                            checked = autoAccept, 
                            onCheckedChange = { 
                                autoAccept = it
                                discoveryManager.autoAccept = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        )
                    }
                }
                
                item {
                    Text("Discovery Duration", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp))
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
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                                Text(d.second, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showDiagnostics = !showDiagnostics },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Connection Diagnostics", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(if (showDiagnostics) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        if (showDiagnostics) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Preferred: $activeTransportType", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                            Text("Active Route: $activeRoute", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                            Text("Target Peer: $activePeer", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                            Text("Session State: $sessionStateText", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                            Text("Route Health: GOOD", color = extras.success, fontSize = 12.sp)
                            Text("Fallback Policy: ${if (smartFallback) "PREFERRED + SMART FALLBACK" else "PREFERRED ONLY"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            Text("Auto Connect: ${if (autoConnect) "ENABLED" else "DISABLED"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            Text("Crypto: ECDH P-256 + AES-GCM-256 (Tink)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("PRIVACY", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hide notification content", color = MaterialTheme.colorScheme.onSurface)
                    }
                    Switch(
                        checked = hideNotifications,
                        onCheckedChange = { 
                            hideNotifications = it
                            privacyManager.hideNotificationContent = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
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
                        Text("Block screenshots", color = MaterialTheme.colorScheme.onSurface)
                        Text("Requires app restart", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(
                        checked = blockScreenshots,
                        onCheckedChange = { 
                            blockScreenshots = it
                            privacyManager.blockScreenshots = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
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
                        Text("Clipboard protection", color = MaterialTheme.colorScheme.onSurface)
                    }
                    Switch(
                        checked = clipboardProtection,
                        onCheckedChange = { 
                            clipboardProtection = it
                            privacyManager.clipboardProtection = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("EMERGENCY", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                Button(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("Export Keys & Logs to Logcat")
                }
            }

            item {
                Button(
                    onClick = { showSelfDestructConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("SELF-DESTRUCT (Wipe & Kill)", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showSelfDestructConfirm) {
        AlertDialog(
            onDismissRequest = { showSelfDestructConfirm = false },
            title = { Text("Self-destruct?") },
            text = {
                Text("This wipes the current session and closes Wokie immediately. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSelfDestructConfirm = false
                    onSelfDestruct()
                }) {
                    Text("Wipe & close", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSelfDestructConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
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
                    Text(errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
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
