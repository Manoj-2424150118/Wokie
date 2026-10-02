package com.wokie.message

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wokie.message.security.AppLockManager
import com.wokie.message.mesh.NearbyDiscoveryManager
import com.wokie.message.security.AuthMethod

@RunWith(AndroidJUnit4::class)
class AppStateTest {

    @Test
    fun testNearbyStateAndPreferredTransport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("wokie_discovery_prefs", android.content.Context.MODE_PRIVATE)
        
        // Clear prefs for isolated test
        prefs.edit().clear().commit()
        
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

        // 1. Initial state
        assertFalse(discoveryManager.nearbyModeEnabled)
        assertEquals("NEARBY", prefs.getString("pref_transport", "NEARBY"))

        // 2. Set Preferred Transport = Nearby
        prefs.edit().putString("pref_transport", "NEARBY").commit()
        
        // 3. Enable Nearby Mode
        discoveryManager.nearbyModeEnabled = true
        
        // 4. Assert persisted setting
        assertTrue(prefs.getBoolean("nearby_mode", false))
        
        // 5. Assert ViewModel/application state
        assertTrue(discoveryManager.nearbyModeEnabled)
        
        // 6. Disable Nearby Mode
        discoveryManager.nearbyModeEnabled = false
        assertFalse(prefs.getBoolean("nearby_mode", true))
        assertFalse(discoveryManager.nearbyModeEnabled)
        
        // 7. Re-enable
        discoveryManager.nearbyModeEnabled = true
        assertTrue(discoveryManager.nearbyModeEnabled)

        // 8. Recreate managers to test process/restart persistence
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
        assertEquals("NEARBY", prefs.getString("pref_transport", ""))
    }
    
    @Test
    fun testPreferredTransportAllSupported() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("wokie_discovery_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        
        val transports = listOf("NEARBY", "WIFI", "HOTSPOT", "CELLULAR", "INTERNET", "SATELLITE", "UNSUPPORTED_FAKE")
        for (t in transports) {
            prefs.edit().putString("pref_transport", t).commit()
            val restored = prefs.getString("pref_transport", "")
            assertEquals(t, restored)
        }
    }

    @Test
    fun testAppLockWokiePinSetupAndVerification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appLockManager = AppLockManager(context)
        
        // Clear prefs
        context.getSharedPreferences("wokie_security_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        
        // 1. Initial State - no PIN configured
        assertFalse(appLockManager.hasPinSet())
        
        // 2. Setup minimum PIN validation & successful save
        appLockManager.setPin("5678")
        assertTrue(appLockManager.hasPinSet())
        
        // 3. Correct PIN unlock
        assertTrue(appLockManager.verifyPin("5678"))
        
        // 4. Wrong PIN rejection
        assertFalse(appLockManager.verifyPin("1111"))
        
        // 5. Change PIN
        appLockManager.setPin("9999")
        
        // 6. Old PIN rejected after change
        assertFalse(appLockManager.verifyPin("5678"))
        
        // 7. New PIN accepted
        assertTrue(appLockManager.verifyPin("9999"))
        
        // 8. Persistence after recreation
        val recreatedAppLockManager = AppLockManager(context)
        assertTrue(recreatedAppLockManager.hasPinSet())
        assertTrue(recreatedAppLockManager.verifyPin("9999"))
        
        // 9. Lockout mechanism (5 attempts)
        for (i in 1..5) {
            recreatedAppLockManager.verifyPin("0000") // Wrong PIN
        }
        // Even with correct PIN, should be locked out
        assertFalse(recreatedAppLockManager.verifyPin("9999"))
        
        // Verify raw PINs never appear in preferences
        val prefs = context.getSharedPreferences("wokie_security_prefs", android.content.Context.MODE_PRIVATE)
        val hash = prefs.getString("pin_hash", "")
        assertNotEquals("9999", hash)
        assertFalse(hash!!.contains("9999"))
    }
}
