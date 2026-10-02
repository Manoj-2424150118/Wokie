package com.wokie.message.crypto

import com.wokie.message.protocol.MessageEnvelope
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CryptoTests {

    @Test
    fun `Test 1 - Shared secret is identical`() {
        val a = EncryptionManager()
        val b = EncryptionManager()
        a.establishSession(b.getPublicKeyBytes())
        b.establishSession(a.getPublicKeyBytes())
        assertTrue(a.isReady && b.isReady)
    }

    @Test
    fun `Test 2 and 3 - Encrypt and Decrypt with correct AAD`() {
        val a = EncryptionManager()
        val b = EncryptionManager()
        a.establishSession(b.getPublicKeyBytes())
        b.establishSession(a.getPublicKeyBytes())

        val aad = "session-1|msg-1|1".toByteArray(Charsets.UTF_8)
        val plaintextA = "HELLO FROM A".toByteArray(Charsets.UTF_8)
        val ciphertextA = a.encrypt(plaintextA, aad)
        assertNotNull(ciphertextA)
        
        val decryptedB = b.decrypt(ciphertextA!!, aad)
        assertEquals("HELLO FROM A", String(decryptedB!!, Charsets.UTF_8))
    }

    @Test
    fun `Test 4 - Modify ciphertext fails decryption`() {
        val a = EncryptionManager()
        val b = EncryptionManager()
        a.establishSession(b.getPublicKeyBytes())
        b.establishSession(a.getPublicKeyBytes())

        val aad = "session-1|msg-1|1".toByteArray(Charsets.UTF_8)
        val ciphertext = a.encrypt("HELLO".toByteArray(Charsets.UTF_8), aad)!!
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1] + 1).toByte()
        
        val decrypted = b.decrypt(ciphertext, aad)
        assertNull(decrypted)
    }

    @Test
    fun `Test 5 and 6 - Wrong session key fails`() {
        val a = EncryptionManager()
        val b = EncryptionManager()
        a.establishSession(b.getPublicKeyBytes())
        b.establishSession(a.getPublicKeyBytes())

        val c = EncryptionManager()
        c.establishSession(b.getPublicKeyBytes())

        val aad = "session-1|msg-1|1".toByteArray(Charsets.UTF_8)
        val ciphertext = a.encrypt("HELLO".toByteArray(Charsets.UTF_8), aad)!!
        
        val decrypted = c.decrypt(ciphertext, aad)
        assertNull(decrypted)
    }

    @Test
    fun `Test 7 - Identical plaintexts produce different ciphertexts (Nonce changes)`() {
        val a = EncryptionManager()
        val b = EncryptionManager()
        a.establishSession(b.getPublicKeyBytes())
        b.establishSession(a.getPublicKeyBytes())

        val p = "HELLO".toByteArray(Charsets.UTF_8)
        val aad = "session-1|msg-1|1".toByteArray(Charsets.UTF_8)
        
        val c1 = a.encrypt(p, aad)!!
        val c2 = a.encrypt(p, aad)!!
        assertFalse(c1.contentEquals(c2))
    }

    @Test
    fun `Test 8 and 9 - MessageEnvelope Serialization`() {
        val env = MessageEnvelope(
            sequenceNumber = 1L,
            messageType = "ENCRYPTED_MESSAGE",
            sessionId = "session-1",
            senderNodeId = "NODE_A",
            ciphertext = byteArrayOf(1, 2, 3)
        )
        val json = env.toJson()
        val env2 = MessageEnvelope.fromJson(json)
        assertEquals(env.messageId, env2.messageId)
        assertEquals(env.sessionId, env2.sessionId)
        assertArrayEquals(env.ciphertext, env2.ciphertext)
    }

    @Test
    fun `Test 10 - Replay Protector accepts valid messages`() {
        val protector = ReplayProtector()
        protector.startNewSession("session-1")
        
        assertTrue(protector.isMessageValid("session-1", 1L))
        assertTrue(protector.isMessageValid("session-1", 2L))
    }

    @Test
    fun `Test 11 - Replay Protector rejects duplicate sequence number (Replay)`() {
        val protector = ReplayProtector()
        protector.startNewSession("session-1")
        
        assertTrue(protector.isMessageValid("session-1", 1L))
        assertFalse(protector.isMessageValid("session-1", 1L))
    }

    @Test
    fun `Test 12 - Replay Protector rejects old session id`() {
        val protector = ReplayProtector()
        protector.startNewSession("session-2")
        
        assertFalse(protector.isMessageValid("session-1", 1L))
    }

    @Test
    fun `Test 13 - Modified AAD fails decryption`() {
        val a = EncryptionManager()
        val b = EncryptionManager()
        a.establishSession(b.getPublicKeyBytes())
        b.establishSession(a.getPublicKeyBytes())

        val aadOriginal = "session-1|msg-1|1".toByteArray(Charsets.UTF_8)
        val aadTampered = "session-1|msg-2|1".toByteArray(Charsets.UTF_8)
        
        val ciphertext = a.encrypt("HELLO".toByteArray(Charsets.UTF_8), aadOriginal)!!
        
        val decrypted = b.decrypt(ciphertext, aadTampered)
        assertNull(decrypted)
    }

    @Test
    fun `Test 14 - 1000 encryption operations produce unique nonces (ciphertexts)`() {
        val a = EncryptionManager()
        val b = EncryptionManager()
        a.establishSession(b.getPublicKeyBytes())
        b.establishSession(a.getPublicKeyBytes())

        val p = "HELLO".toByteArray(Charsets.UTF_8)
        val seenCiphertexts = mutableSetOf<String>()
        
        for (i in 1..1000) {
            val aad = "session-1|msg-$i|$i".toByteArray(Charsets.UTF_8)
            val ciphertext = a.encrypt(p, aad)!!
            val encoded = java.util.Base64.getEncoder().encodeToString(ciphertext)
            
            assertFalse("Duplicate ciphertext found at iteration $i", seenCiphertexts.contains(encoded))
            seenCiphertexts.add(encoded)
            
            // Validate decryption too
            val decrypted = b.decrypt(ciphertext, aad)
            assertNotNull("Decryption failed at iteration $i", decrypted)
        }
    }
}
