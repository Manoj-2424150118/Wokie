package com.wokie.message.protocol

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MessageEnvelopeTests {

    @Test
    fun `valid envelope serialize and deserialize`() {
        val env = MessageEnvelope(
            sequenceNumber = 42L,
            messageType = "ENCRYPTED_MESSAGE",
            sessionId = "SESSION-123",
            senderNodeId = "NODE-1",
            ciphertext = byteArrayOf(1, 2, 3, 4)
        )
        val json = env.toJson()
        val parsed = MessageEnvelope.fromJson(json)
        assertEquals(env.messageId, parsed.messageId)
        assertEquals(env.sequenceNumber, parsed.sequenceNumber)
        assertEquals(env.sessionId, parsed.sessionId)
        assertEquals(env.senderNodeId, parsed.senderNodeId)
        assertArrayEquals(env.ciphertext, parsed.ciphertext)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fuzz test - oversized payload`() {
        val largeString = "a".repeat(50001)
        MessageEnvelope.fromJson(largeString)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fuzz test - invalid json`() {
        MessageEnvelope.fromJson("{ malformed json ]")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fuzz test - missing ciphertext`() {
        MessageEnvelope.fromJson("""{"protocolVersion":"1.0","messageId":"123","sequenceNumber":1,"messageType":"ENCRYPTED_MESSAGE","sessionId":"S1","senderNodeId":"N1"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fuzz test - oversized messageId`() {
        val largeId = "a".repeat(101)
        MessageEnvelope.fromJson("""{"protocolVersion":"1.0","messageId":"$largeId","sequenceNumber":1,"messageType":"ENCRYPTED_MESSAGE","sessionId":"S1","senderNodeId":"N1","ciphertext":"AQIDBA=="}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fuzz test - invalid Base64 ciphertext`() {
        MessageEnvelope.fromJson("""{"protocolVersion":"1.0","messageId":"123","sequenceNumber":1,"messageType":"ENCRYPTED_MESSAGE","sessionId":"S1","senderNodeId":"N1","ciphertext":"NOT_BASE_64!@#$"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fuzz test - invalid protocol version`() {
        MessageEnvelope.fromJson("""{"protocolVersion":"2.0","messageId":"123","sequenceNumber":1,"messageType":"ENCRYPTED_MESSAGE","sessionId":"S1","senderNodeId":"N1","ciphertext":"AQIDBA=="}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fuzz test - missing messageType`() {
        MessageEnvelope.fromJson("""{"protocolVersion":"1.0","messageId":"123","sequenceNumber":1,"sessionId":"S1","senderNodeId":"N1","ciphertext":"AQIDBA=="}""")
    }
}
