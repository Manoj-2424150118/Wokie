package com.wokie.message.crypto

import com.wokie.message.protocol.MessageEnvelope
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PhysicalMessageTests {

    @Test
    fun `Test End-to-End messaging and duplicate rejection`() {
        val alice = EncryptionManager()
        val bob = EncryptionManager()
        alice.establishSession(bob.getPublicKeyBytes())
        bob.establishSession(alice.getPublicKeyBytes())

        val sessionId = "shared-session-id"
        val bobReplayProtector = ReplayProtector()
        bobReplayProtector.startNewSession(sessionId)

        // ALICE SENDS TO BOB
        val text = "Hello Bob!"
        val envId = UUID.randomUUID().toString()
        val currentSeq = 1L
        val aad = (sessionId + envId + currentSeq).toByteArray(Charsets.UTF_8)
        
        val encrypted = alice.encrypt(text.toByteArray(Charsets.UTF_8), aad)
        assertNotNull(encrypted)
        
        val env = MessageEnvelope(
            messageId = envId,
            sequenceNumber = currentSeq,
            messageType = "ENCRYPTED_MESSAGE",
            sessionId = sessionId,
            senderNodeId = "ALICE",
            ciphertext = encrypted!!
        )
        
        val serialized = env.toJson()
        
        // BOB RECEIVES
        val receivedEnv = MessageEnvelope.fromJson(serialized)
        
        // BOB VALIDATES REPLAY
        assertTrue(bobReplayProtector.isMessageValid(receivedEnv.sessionId, receivedEnv.sequenceNumber))
        
        // BOB DECRYPTS
        val bobAad = (receivedEnv.sessionId + receivedEnv.messageId + receivedEnv.sequenceNumber).toByteArray(Charsets.UTF_8)
        val decrypted = bob.decrypt(receivedEnv.ciphertext, bobAad)
        assertNotNull(decrypted)
        assertEquals("Hello Bob!", String(decrypted!!, Charsets.UTF_8))
        
        // BOB RECEIVES DUPLICATE PHYSICAL PACKET
        assertFalse(bobReplayProtector.isMessageValid(receivedEnv.sessionId, receivedEnv.sequenceNumber))
        
        // BOB RECEIVES DIFFERENT SESSION IDENTIFIER WITH SAME PACKET
        val maliciousSessionId = "fake-session-id"
        assertFalse(bobReplayProtector.isMessageValid(maliciousSessionId, receivedEnv.sequenceNumber))
    }
}
