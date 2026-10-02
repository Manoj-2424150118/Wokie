package com.wokie.message.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class LockState {
    LOCK_DISABLED,
    LOCKED,
    AUTHENTICATING,
    UNLOCKED,
    LOCK_PENDING,
    LOCKING
}

enum class AuthMethod {
    WOKIE_PIN,
    DEVICE_CREDENTIAL_OR_BIOMETRIC
}

enum class LockTimeout(val delayMs: Long) {
    IMMEDIATELY(0L),
    THIRTY_SECONDS(30_000L),
    ONE_MINUTE(60_000L),
    FIVE_MINUTES(300_000L),
    NEVER(-1L)
}

class AppLockManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("wokie_security_prefs", Context.MODE_PRIVATE)

    var pinAttempts = 0

    var isAppLockEnabled: Boolean
        get() = prefs.getBoolean("app_lock_enabled", false)
        set(value) = prefs.edit().putBoolean("app_lock_enabled", value).apply()

    var authMethod: AuthMethod
        get() = AuthMethod.valueOf(prefs.getString("auth_method", AuthMethod.DEVICE_CREDENTIAL_OR_BIOMETRIC.name)!!)
        set(value) = prefs.edit().putString("auth_method", value.name).apply()

    var lockTimeout: LockTimeout
        get() = LockTimeout.valueOf(prefs.getString("lock_timeout", LockTimeout.IMMEDIATELY.name)!!)
        set(value) = prefs.edit().putString("lock_timeout", value.name).apply()

    var destroySessionOnLock: Boolean
        get() = prefs.getBoolean("destroy_session", false)
        set(value) = prefs.edit().putBoolean("destroy_session", value).apply()

    // PIN Hashing
    fun setPin(pin: String) {
        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)
        val saltStr = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        val hashStr = derivePin(pin, saltBytes)
        prefs.edit()
            .putString("pin_salt", saltStr)
            .putString("pin_hash", hashStr)
            .apply()
        pinAttempts = 0
    }

    fun verifyPin(pin: String): Boolean {
        if (pinAttempts >= 5) {
            return false // Lockout simulation
        }
        val saltStr = prefs.getString("pin_salt", null) ?: return false
        val expectedHash = prefs.getString("pin_hash", null) ?: return false
        val saltBytes = Base64.decode(saltStr, Base64.NO_WRAP)
        val isValid = derivePin(pin, saltBytes) == expectedHash
        if (isValid) {
            pinAttempts = 0
        } else {
            pinAttempts++
        }
        return isValid
    }

    fun hasPinSet(): Boolean = prefs.contains("pin_hash")

    private fun derivePin(pin: String, saltBytes: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), saltBytes, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    // Biometric Check
    fun canUseBiometricsOrDeviceCredential(): Boolean {
        val bm = BiometricManager.from(context)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return bm.canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
