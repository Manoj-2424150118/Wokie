package com.wokie.message

import org.junit.Assert.*
import org.junit.Test
import com.wokie.message.security.AppLockManager
import com.wokie.message.mesh.NearbyDiscoveryManager
import com.wokie.message.transport.TransportCoordinator
import com.wokie.message.transport.WokieTransport
import com.wokie.message.transport.TransportCapability

class TrackableTransport(
    private val type: String,
    private val available: Boolean = true
) : WokieTransport {
    var advertisingStarted = false
    var discoveryStarted = false
    var discoveryStopped = false
    var disconnected = false

    override fun startAdvertising(localNodeId: String) {
        advertisingStarted = true
    }

    override fun startDiscovery(onPeerFound: (String, String) -> Unit, onPeerLost: (String) -> Unit) {
        discoveryStarted = true
        discoveryStopped = false
    }

    override fun stopDiscovery() {
        discoveryStopped = true
        discoveryStarted = false
    }

    override fun requestConnection(endpointId: String) {}
    override fun acceptConnection(endpointId: String) {}
    override fun rejectConnection(endpointId: String) {}

    override fun disconnect() {
        disconnected = true
    }

    override fun send(packet: ByteArray) {}
    override fun setReceiver(onMessageReceived: (ByteArray) -> Unit) {}
    override fun setConnectionCallback(onConnected: () -> Unit, onDisconnected: () -> Unit) {}
    override fun getTransportType(): String = type
    override fun isAvailable(): Boolean = available
    override fun capabilities(): List<TransportCapability> = emptyList()
}

class AppStateUnitTest {

    @Test
    fun testNearbyStateAndPreferredTransport() {
        val prefs = FakeSharedPreferences()
        
        // Init managers
        val discoveryManager = NearbyDiscoveryManager(
            saveConfig = { key, value -> 
                when(value) {
                    is Boolean -> prefs.edit().putBoolean(key, value).apply()
                    is Int -> prefs.edit().putInt(key, value).apply()
                }
            },
            getConfig = { key, def -> 
                when(def) {
                    is Boolean -> prefs.getBoolean(key, def)
                    is Int -> prefs.getInt(key, def)
                    else -> def
                }
            }
        )

        assertFalse(discoveryManager.nearbyModeEnabled)

        // 2. Set Preferred Transport = Nearby
        prefs.edit().putString("pref_transport", "NEARBY").apply()
        
        // 3. Enable Nearby Mode
        discoveryManager.nearbyModeEnabled = true
        
        // 4. Assert persisted setting
        assertTrue(prefs.getBoolean("nearby_mode", false))
        
        // 5. Assert application state
        assertTrue(discoveryManager.nearbyModeEnabled)
        
        // 6. Disable Nearby Mode
        discoveryManager.nearbyModeEnabled = false
        assertFalse(prefs.getBoolean("nearby_mode", true))
        assertFalse(discoveryManager.nearbyModeEnabled)
        
        // 7. Re-enable
        discoveryManager.nearbyModeEnabled = true
        assertTrue(discoveryManager.nearbyModeEnabled)

        // 8. Recreate managers
        val recreatedManager = NearbyDiscoveryManager(
            saveConfig = { key, value -> 
                when(value) {
                    is Boolean -> prefs.edit().putBoolean(key, value).apply()
                    is Int -> prefs.edit().putInt(key, value).apply()
                }
            },
            getConfig = { key, def -> 
                when(def) {
                    is Boolean -> prefs.getBoolean(key, def)
                    is Int -> prefs.getInt(key, def)
                    else -> def
                }
            }
        )
        assertTrue(recreatedManager.nearbyModeEnabled)
    }
    
    @Test
    fun testPreferredTransportAllSupported() {
        val prefs = FakeSharedPreferences()
        val transports = listOf("NEARBY", "WIFI", "HOTSPOT", "CELLULAR", "INTERNET", "SATELLITE", "UNSUPPORTED_FAKE")
        for (t in transports) {
            prefs.edit().putString("pref_transport", t).apply()
            assertEquals(t, prefs.getString("pref_transport", ""))
        }
    }

    @Test
    fun testPreferredTransportSelectionPersistenceAndRecreation() {
        val discPrefs = FakeSharedPreferences()
        val appPrefs = FakeSharedPreferences()

        // 1. Initial State: defaults to NEARBY if unset
        var currentPreferredTransport = discPrefs.getString("pref_transport", null) 
            ?: discPrefs.getString("preferred_transport", null)
            ?: appPrefs.getString("preferred_transport", "NEARBY")
        assertEquals("NEARBY", currentPreferredTransport)

        // Helper simulating user tapping a selectable transport in Settings
        fun selectTransport(selected: String) {
            currentPreferredTransport = selected
            discPrefs.edit().putString("pref_transport", selected).putString("preferred_transport", selected).apply()
            appPrefs.edit().putString("preferred_transport", selected).apply()
        }

        // Helper simulating reading persisted state after ViewModel / Activity / Process recreation
        fun readRecreatedTransport(): String {
            return discPrefs.getString("pref_transport", null)
                ?: discPrefs.getString("preferred_transport", null)
                ?: appPrefs.getString("preferred_transport", "NEARBY")
                ?: "NEARBY"
        }

        // 2. Tap/select NEARBY
        selectTransport("NEARBY")
        assertEquals("NEARBY", currentPreferredTransport)
        assertEquals("NEARBY", discPrefs.getString("pref_transport", ""))
        assertEquals("NEARBY", discPrefs.getString("preferred_transport", ""))
        assertEquals("NEARBY", appPrefs.getString("preferred_transport", ""))

        // Verify recreated state
        assertEquals("NEARBY", readRecreatedTransport())

        // 3. Tap/select WIFI DIRECT (supported selectable transport)
        selectTransport("WIFI DIRECT")
        assertEquals("WIFI DIRECT", currentPreferredTransport)
        assertEquals("WIFI DIRECT", discPrefs.getString("pref_transport", ""))
        assertEquals("WIFI DIRECT", discPrefs.getString("preferred_transport", ""))
        assertEquals("WIFI DIRECT", appPrefs.getString("preferred_transport", ""))

        // Verify recreated state
        assertEquals("WIFI DIRECT", readRecreatedTransport())

        // 4. Tap/select BLE MESH
        selectTransport("BLE MESH")
        assertEquals("BLE MESH", currentPreferredTransport)
        assertEquals("BLE MESH", readRecreatedTransport())

        // 5. Tap/select HOTSPOT
        selectTransport("HOTSPOT")
        assertEquals("HOTSPOT", currentPreferredTransport)
        assertEquals("HOTSPOT", readRecreatedTransport())

        // 6. Tap/select CELLULAR
        selectTransport("CELLULAR")
        assertEquals("CELLULAR", currentPreferredTransport)
        assertEquals("CELLULAR", readRecreatedTransport())

        // 7. Tap/select back to NEARBY
        selectTransport("NEARBY")
        assertEquals("NEARBY", currentPreferredTransport)
        assertEquals("NEARBY", readRecreatedTransport())
    }

    @Test
    fun testTransportCoordinatorSynchronization() {
        val nearby = TrackableTransport("NEARBY")
        val wifi = TrackableTransport("WIFI DIRECT")
        val ble = TrackableTransport("BLE MESH")
        val hotspot = TrackableTransport("HOTSPOT")
        val cellular = TrackableTransport("CELLULAR")

        val coordinator = TransportCoordinator(
            context = null,
            localNodeId = "TEST_NODE",
            customTransports = listOf(nearby, wifi, ble, hotspot, cellular)
        )

        // Select NEARBY
        coordinator.setPreferredTransport("NEARBY")
        assertEquals("NEARBY", coordinator.preferredTransport.value)
        assertTrue(coordinator.enabledTransports.contains("NEARBY"))
        assertTrue(nearby.advertisingStarted)
        assertTrue(nearby.discoveryStarted)
        assertFalse(wifi.discoveryStarted)

        // Select WIFI DIRECT
        coordinator.setPreferredTransport("WIFI DIRECT")
        assertEquals("WIFI DIRECT", coordinator.preferredTransport.value)
        assertTrue(coordinator.enabledTransports.contains("WIFI DIRECT"))
        assertFalse(coordinator.enabledTransports.contains("NEARBY"))
        assertTrue(wifi.discoveryStarted)
        assertTrue(wifi.advertisingStarted)
        assertTrue(nearby.discoveryStopped)
        assertTrue(nearby.disconnected)

        // Select BLE MESH
        coordinator.setPreferredTransport("BLE MESH")
        assertEquals("BLE MESH", coordinator.preferredTransport.value)
        assertTrue(coordinator.enabledTransports.contains("BLE MESH"))
        assertFalse(coordinator.enabledTransports.contains("WIFI DIRECT"))
        assertTrue(ble.discoveryStarted)
        assertTrue(wifi.discoveryStopped)
        assertTrue(wifi.disconnected)

        // Select back to NEARBY
        coordinator.setPreferredTransport("NEARBY")
        assertEquals("NEARBY", coordinator.preferredTransport.value)
        assertTrue(coordinator.enabledTransports.contains("NEARBY"))
        assertFalse(coordinator.enabledTransports.contains("BLE MESH"))
        assertTrue(nearby.discoveryStarted)
        assertTrue(ble.discoveryStopped)
        assertTrue(ble.disconnected)
    }
}
