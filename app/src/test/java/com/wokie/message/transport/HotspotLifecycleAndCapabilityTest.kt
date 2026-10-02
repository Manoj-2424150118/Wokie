package com.wokie.message.transport

import android.os.Build
import org.junit.Assert.*
import org.junit.Test

class HotspotLifecycleAndCapabilityTest {

    @Test
    fun testHotspotCapabilityPermissionGranted() {
        val checker = HotspotCapabilityChecker(
            context = null,
            sdkIntOverride = Build.VERSION_CODES.TIRAMISU,
            permissionCheckerOverride = { true }
        )
        val missing = checker.getMissingPermissions()
        assertTrue(missing.isEmpty())
    }

    @Test
    fun testHotspotCapabilityPermissionDenied() {
        val checker = HotspotCapabilityChecker(
            context = null,
            sdkIntOverride = Build.VERSION_CODES.TIRAMISU,
            permissionCheckerOverride = { false }
        )
        val missing = checker.getMissingPermissions()
        assertTrue(missing.contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue(missing.contains("android.permission.ACCESS_FINE_LOCATION"))

        val status = checker.checkCapability()
        assertEquals(HotspotCapabilityStatus.PERMISSION_REQUIRED, status)
    }

    @Test
    fun testHotspotCapabilityLegacyAndroidUnavailable() {
        val checker = HotspotCapabilityChecker(
            context = null,
            sdkIntOverride = Build.VERSION_CODES.N,
            permissionCheckerOverride = { true }
        )
        val status = checker.checkCapability()
        assertEquals(HotspotCapabilityStatus.UNAVAILABLE_ON_DEVICE, status)
    }

    @Test
    fun testHotspotLifecycleSafeTransitions() {
        val checker = HotspotCapabilityChecker(
            context = null,
            sdkIntOverride = Build.VERSION_CODES.TIRAMISU,
            permissionCheckerOverride = { false }
        )
        val transport = LocalHotspotTransport(
            context = null,
            localNodeId = "TEST_HOTSPOT_NODE",
            capabilityCheckerOverride = checker
        )

        assertEquals(HotspotState.IDLE, transport.currentState)

        // Attempt to start when permission is missing -> MUST NOT CRASH
        transport.startAdvertising("TEST_HOTSPOT_NODE")

        // State remains safe IDLE with status updated
        assertEquals(HotspotState.IDLE, transport.currentState)
        assertEquals(HotspotCapabilityStatus.PERMISSION_REQUIRED, transport.currentStatus)

        // Disconnect from IDLE -> MUST NOT CRASH
        transport.disconnect()
        assertEquals(HotspotState.IDLE, transport.currentState)
    }

    @Test
    fun testDuplicateStartIgnoredSafely() {
        val checker = HotspotCapabilityChecker(
            context = null,
            sdkIntOverride = Build.VERSION_CODES.TIRAMISU,
            permissionCheckerOverride = { false }
        )
        val transport = LocalHotspotTransport(
            context = null,
            localNodeId = "TEST_HOTSPOT_NODE",
            capabilityCheckerOverride = checker
        )

        // Call start twice in rapid succession
        transport.startAdvertising("NODE_A")
        transport.startAdvertising("NODE_A")

        // No crash, state remains clean IDLE
        assertEquals(HotspotState.IDLE, transport.currentState)
    }

    @Test
    fun testRecreatedTransportStartsInCleanIdle() {
        // Simulates relaunch/recreation after prior failed attempt
        val checker = HotspotCapabilityChecker(
            context = null,
            sdkIntOverride = Build.VERSION_CODES.TIRAMISU,
            permissionCheckerOverride = { false }
        )
        val transport1 = LocalHotspotTransport(
            context = null,
            localNodeId = "NODE_1",
            capabilityCheckerOverride = checker
        )
        transport1.startAdvertising("NODE_1")
        assertEquals(HotspotCapabilityStatus.PERMISSION_REQUIRED, transport1.currentStatus)

        // Relaunch / Recreate
        val transport2 = LocalHotspotTransport(
            context = null,
            localNodeId = "NODE_1",
            capabilityCheckerOverride = checker
        )
        assertEquals(HotspotState.IDLE, transport2.currentState)
        assertFalse(transport2.isAvailable())
    }
}
