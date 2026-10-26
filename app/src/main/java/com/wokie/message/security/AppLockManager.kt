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

    companion object {
        /** Legacy PBKDF2 iteration count (hashes stored before the upgrade). */
        const val LEGACY_PIN_ITERATIONS = 10_000

        /** Current PBKDF2 iteration count for newly stored PIN hashes. */
        const val PIN_ITERATIONS = 210_000

        const val MAX_PIN_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 60_000L
    }

    /**
     * Failed-attempt counter, PERSISTED (the previous in-memory `var` reset
     * on every process restart, making the 5-attempt lockout trivially
     * bypassable, and within one process it locked the legitimate user out
     * forever with no timed release).
     */
    var pinAttempts: Int
        get() = prefs.getInt("pin_attempts", 0)
        set(value) = prefs.edit().putInt("pin_attempts", value).apply()

    private var lockoutUntilMs: Long
        get() = prefs.getLong("pin_lockout_until", 0L)
        set(value) = prefs.edit().putLong("pin_lockout_until", value).apply()

    /** True while a timed lockout is in effect. */
    fun isLockedOut(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lockoutUntilMs == 0L) return false
        if (nowMs >= lockoutUntilMs) {
            // Lockout expired: clear it so the user can try again
            lockoutUntilMs = 0L
            pinAttempts = 0
            return false
        }
        return true
    }

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
        val hashStr = derivePin(pin, saltBytes, PIN_ITERATIONS)
        prefs.edit()
            .putString("pin_salt", saltStr)
            .putString("pin_hash", hashStr)
            .putInt("pin_iterations", PIN_ITERATIONS)
            .apply()
        pinAttempts = 0
        lockoutUntilMs = 0L
    }

    fun verifyPin(pin: String): Boolean {
        if (isLockedOut()) {
            return false
        }
        val saltStr = prefs.getString("pin_salt", null) ?: return false
        val expectedHash = prefs.getString("pin_hash", null) ?: return false
        val saltBytes = Base64.decode(saltStr, Base64.NO_WRAP)
        // Hashes stored by older builds used fewer iterations; the count is
        // stored alongside the hash so old PINs keep verifying.
        val iterations = prefs.getInt("pin_iterations", LEGACY_PIN_ITERATIONS)
        val candidate = derivePin(pin, saltBytes, iterations)
        // Constant-time comparison (the old code used == on the Base64 strings)
        val isValid = java.security.MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8)
        )
        if (isValid) {
            pinAttempts = 0
            lockoutUntilMs = 0L
            if (iterations < PIN_ITERATIONS) {
                // Transparently upgrade the stored hash to the current cost
                setPin(pin)
            }
        } else {
            val attempts = pinAttempts + 1
            pinAttempts = attempts
            if (attempts >= MAX_PIN_ATTEMPTS) {
                lockoutUntilMs = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            }
        }
        return isValid
    }

    fun hasPinSet(): Boolean = prefs.contains("pin_hash")

    private fun derivePin(pin: String, saltBytes: ByteArray, iterations: Int): String {
        val spec = PBEKeySpec(pin.toCharArray(), saltBytes, iterations, 256)
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
