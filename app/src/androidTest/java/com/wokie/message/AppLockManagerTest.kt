package com.wokie.message

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wokie.message.security.AppLockManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLockManagerTest {

    @Test
    fun testPinSetupAndPersistence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("wokie_security_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        
        val appLockManager = AppLockManager(context)
        
        // 1. No pin initially
        assertFalse(appLockManager.hasPinSet())
        
        // 2. Setup pin
        appLockManager.setPin("4321")
        assertTrue(appLockManager.hasPinSet())
        
        // 3. Verify PIN
        assertTrue(appLockManager.verifyPin("4321"))
        assertFalse(appLockManager.verifyPin("9999"))
        assertFalse(appLockManager.verifyPin("12")) // Short PIN shouldn't match
        
        // 4. Change PIN
        appLockManager.setPin("9876")
        assertFalse(appLockManager.verifyPin("4321")) // Old PIN fails
        assertTrue(appLockManager.verifyPin("9876")) // New PIN works
        
        // 5. Check Persistence
        val prefs = context.getSharedPreferences("wokie_security_prefs", android.content.Context.MODE_PRIVATE)
        val hash = prefs.getString("pin_hash", null)
        val salt = prefs.getString("pin_salt", null)
        
        assertNotNull("Verifier hash must be stored", hash)
        assertNotNull("Random salt must be stored", salt)
        assertFalse("Plaintext PIN must not be stored", hash!!.contains("9876"))
        assertFalse("Plaintext PIN must not be stored", salt!!.contains("9876"))
    }
}
