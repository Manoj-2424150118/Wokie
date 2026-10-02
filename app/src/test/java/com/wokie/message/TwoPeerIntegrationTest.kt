package com.wokie.message

import com.wokie.message.crypto.EncryptionManager
import com.wokie.message.crypto.ReplayProtector
import com.wokie.message.mesh.MeshRouter
import com.wokie.message.protocol.MessageEnvelope
import com.wokie.message.transport.FakeTransport
import com.wokie.message.transport.TransportRole
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TwoPeerIntegrationTest {

    class Peer(val name: String) {
        val transport = FakeTransport()
        var receivedMessages = mutableListOf<String>()
        val encryptionManager = EncryptionManager()
        val replayProtector = ReplayProtector()
        var sendSequenceNumber = 0L

        val router = MeshRouter(
            localNodeId = "NODE_$name",
            onMessageForLocalNode = { payload ->
                if (payload.isEmpty()) return@MeshRouter
                val type = payload[0]
                val data = payload.copyOfRange(1, payload.size)

                when (type) {
                    0x01.toByte() -> {
                        encryptionManager.establishSession(data)
                    }
                    0x02.toByte() -> {
                        try {
                            val envelope = MessageEnvelope.fromJson(String(data, Charsets.UTF_8))
                            if (envelope.messageType == "ENCRYPTED_MESSAGE" && envelope.sessionId == encryptionManager.sessionId) {
                                if (replayProtector.isMessageValid(envelope.sessionId, envelope.sequenceNumber)) {
                                    val aad = (envelope.sessionId + envelope.messageId + envelope.sequenceNumber).toByteArray(Charsets.UTF_8)
                                    val decrypted = encryptionManager.decrypt(envelope.ciphertext, aad)
                                    if (decrypted != null) {
                                        receivedMessages.add(String(decrypted, Charsets.UTF_8))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        )

        init {
            transport.setConnectionCallback(
                onConnected = {
                    val keyBytes = encryptionManager.getPublicKeyBytes()
                    val payload = ByteArray(1 + keyBytes.size)
                    payload[0] = 0x01
                    System.arraycopy(keyBytes, 0, payload, 1, keyBytes.size)
                    transport.send(payload)
                },
                onDisconnected = {
                }
            )
            router.setTransport(transport)
        }

        fun send(text: String) {
            val envId = UUID.randomUUID().toString()
            val currentSeq = ++sendSequenceNumber
            val aad = (encryptionManager.sessionId + envId + currentSeq).toByteArray(Charsets.UTF_8)
            val encrypted = encryptionManager.encrypt(text.toByteArray(Charsets.UTF_8), aad)
            if (encrypted != null) {
                val env = MessageEnvelope(
                    messageId = envId,
                    sequenceNumber = currentSeq,
                    messageType = "ENCRYPTED_MESSAGE",
                    sessionId = encryptionManager.sessionId,
                    senderNodeId = router.localNodeId,
                    ciphertext = encrypted
                )
                val serialized = env.toJson().toByteArray(Charsets.UTF_8)
                val payload = ByteArray(1 + serialized.size)
                payload[0] = 0x02
                System.arraycopy(serialized, 0, payload, 1, serialized.size)
                transport.send(payload)
            }
        }
    }

    @Test
    fun testTwoPeerCommunicationAndReplayProtection() {
        val peerA = Peer("A")
        val peerB = Peer("B")

        // Link transports
        peerA.transport.peer = peerB.transport
        peerB.transport.peer = peerA.transport

        // Connect
        peerA.transport.requestConnection("B")
        assertTrue(peerA.transport.isConnected)
        assertTrue(peerB.transport.isConnected)

        // Give it a moment to exchange keys
        Thread.sleep(100)

        assertNotNull(peerA.encryptionManager.sessionId)
        assertEquals(peerA.encryptionManager.sessionId, peerB.encryptionManager.sessionId)

        // Start session on replay protectors
        peerA.replayProtector.startNewSession(peerA.encryptionManager.sessionId)
        peerB.replayProtector.startNewSession(peerB.encryptionManager.sessionId)

        // A sends to B
        peerA.send("Hello B")
        Thread.sleep(100)
        assertEquals(1, peerB.receivedMessages.size)
        assertEquals("Hello B", peerB.receivedMessages[0])

        // B sends to A
        peerB.send("Hello A")
        Thread.sleep(100)
        assertEquals(1, peerA.receivedMessages.size)
        assertEquals("Hello A", peerA.receivedMessages[0])
        
        // Disconnect
        peerA.transport.disconnect()
        assertFalse(peerA.transport.isConnected)
        assertFalse(peerB.transport.isConnected)
    }
}
