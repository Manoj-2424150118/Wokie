package com.wokie.message.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class HotspotCapabilityStatus {
    READY,
    PERMISSION_REQUIRED,
    UNAVAILABLE_ON_DEVICE,
    WIFI_DISABLED,
    ALREADY_ACTIVE
}

class HotspotCapabilityChecker(
    private val context: Context?,
    private val wifiManagerOverride: WifiManager? = null,
    private val sdkIntOverride: Int? = null,
    private val permissionCheckerOverride: ((String) -> Boolean)? = null
) {
    private val currentSdkInt: Int
        get() = sdkIntOverride ?: Build.VERSION.SDK_INT

    private val wifiManager: WifiManager? by lazy {
        wifiManagerOverride ?: context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as WifiManager?
    }

    fun getRequiredPermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (currentSdkInt >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms
    }

    fun getMissingPermissions(): List<String> {
        val perms = getRequiredPermissions()
        if (permissionCheckerOverride != null) {
            return perms.filter { !permissionCheckerOverride.invoke(it) }
        }
        val ctx = context?.applicationContext ?: return emptyList()
        return perms.filter { perm ->
            ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun checkCapability(isReservationActive: Boolean = false): HotspotCapabilityStatus {
        // 1. Android version check: LocalOnlyHotspot requires Android 8.0+ (O)
        if (currentSdkInt < Build.VERSION_CODES.O) {
            return HotspotCapabilityStatus.UNAVAILABLE_ON_DEVICE
        }

        // 2. Reservation check: only one active reservation allowed at a time
        if (isReservationActive) {
            return HotspotCapabilityStatus.ALREADY_ACTIVE
        }

        // 3. Permission check
        val missing = getMissingPermissions()
        if (missing.isNotEmpty()) {
            return HotspotCapabilityStatus.PERMISSION_REQUIRED
        }

        // 4. Hardware / WifiManager check
        val wm = wifiManager ?: return HotspotCapabilityStatus.UNAVAILABLE_ON_DEVICE
        if (context != null) {
            val pm = context.packageManager
            if (pm != null && !pm.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
                return HotspotCapabilityStatus.UNAVAILABLE_ON_DEVICE
            }
        }

        // 5. Wi-Fi state check
        return try {
            if (!wm.isWifiEnabled) {
                HotspotCapabilityStatus.WIFI_DISABLED
            } else {
                HotspotCapabilityStatus.READY
            }
        } catch (e: Exception) {
            HotspotCapabilityStatus.UNAVAILABLE_ON_DEVICE
        }
    }
}
