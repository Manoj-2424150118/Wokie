package com.wokie.message.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.subtle.AesGcmJce
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import com.wokie.message.protocol.WokiePacketParser
import javax.crypto.KeyAgreement

/**
 * Handles Ephemeral ECDH key agreement and AES-256-GCM encryption using Tink primitives.
 */
class EncryptionManager {

    private var keyPair: KeyPair
    private var aead: Aead? = null
    var isReady = false
        private set

    init {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        keyPair = kpg.generateKeyPair()
    }

    fun getPublicKeyBytes(): ByteArray {
        return keyPair.public.encoded
    }

    fun getPublicKeyFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(keyPair.public.encoded)
        return hash.take(8).joinToString("") { "%02X".format(it) } // 16 hex chars
    }

    /**
     * Fingerprint of the PEER's public key, for out-of-band comparison.
     *
     * The verification UI previously displayed [getPublicKeyFingerprint]
     * (this device's OWN key) labelled as the peer's fingerprint, so two
     * honest devices always showed different strings and the MITM check
     * could never actually pass.
     *
     * @return 16 hex chars of SHA-256(peer public key), or null if no peer
     *         key has been received yet.
     */
    fun getPeerPublicKeyFingerprint(): String? {
        val peerBytes = peerPublicKeyBytes ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(peerBytes)
        return hash.take(8).joinToString("") { "%02X".format(it) }
    }

    private var _sessionId: String = ""
    val sessionId: String get() = _sessionId
    
    private var peerPublicKeyBytes: ByteArray? = null

    fun establishSession(peerPublicKeyBytes: ByteArray): Boolean {
        // Defensive format validation prior to KeyFactory parsing
        if (!WokiePacketParser.validateX509EcPublicKey(peerPublicKeyBytes)) {
            destroyKeys()
            return false
        }

        return try {
            val kf = KeyFactory.getInstance("EC")
            val peerPublicKey = kf.generatePublic(X509EncodedKeySpec(peerPublicKeyBytes))
            
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(keyPair.private)
            ka.doPhase(peerPublicKey, true)
            val sharedSecret = ka.generateSecret()
            
            // KDF: HKDF-SHA256 to derive a 256-bit AES key
            val info = "WOKIE-MESSAGE-V1".toByteArray(Charsets.UTF_8)
            val aesKeyBytes = com.google.crypto.tink.subtle.Hkdf.computeHkdf(
                "HMACSHA256", 
                sharedSecret, 
                null, // No salt for MVP
                info, 
                32 // 256 bits = 32 bytes
            )
            
            // Generate a deterministic Session ID from the shared key material
            val digest = MessageDigest.getInstance("SHA-256")
            val sessionHash = digest.digest(aesKeyBytes)
            _sessionId = "SESSION-" + sessionHash.take(8).joinToString("") { "%02X".format(it) }
            
            // Use Google Tink's JCE implementation of AES-GCM
            aead = AesGcmJce(aesKeyBytes)
            this.peerPublicKeyBytes = peerPublicKeyBytes
            isReady = true
            true
        } catch (e: Exception) {
            destroyKeys()
            false
        }
    }

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray? {
        return try {
            aead?.encrypt(plaintext, associatedData)
        } catch (e: Exception) {
            null
        }
    }

    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray? {
        return try {
            aead?.decrypt(ciphertext, associatedData)
        } catch (e: Exception) {
            null
        }
    }

    fun resetEphemeralKeys() {
        destroyKeys()
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        keyPair = kpg.generateKeyPair()
    }

    fun destroyKeys() {
        aead = null
        isReady = false
        peerPublicKeyBytes = null
        _sessionId = ""
        // Request JVM GC as a best-effort memory wipe, as requested by the audit
        System.gc()
    }
}
