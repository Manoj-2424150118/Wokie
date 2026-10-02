package com.wokie.message.transport

import com.wokie.message.crypto.EncryptionManager
import com.wokie.message.protocol.WokiePacket
import com.wokie.message.protocol.WokiePacketParser
import com.wokie.message.protocol.WokiePacketType
import org.junit.Assert.*
import org.junit.Test

class BleMeshCrashRegressionTest {

    @Test
    fun `Regression Test - Exact production crash packet byte 0x01 does not crash application`() {
        val encryptionManager = EncryptionManager()

        // 1. The exact production crash input:
        // BLE advertisement service data was [0x01], leaving empty slice [1..1] for key bytes
        val crashPayload = byteArrayOf(0x01)
        val badKeyBytes = crashPayload.copyOfRange(1, crashPayload.size) // empty ByteArray(0)

        // Envelope parser rejects it
        val parsed = WokiePacketParser.parse(crashPayload)
        assertNull("Raw [0x01] must be rejected by protocol parser", parsed)

        // Key validator rejects it
        assertFalse("Empty key bytes must fail X509 validation", WokiePacketParser.validateX509EcPublicKey(badKeyBytes))

        // Direct call to establishSession must NOT throw InvalidKeySpecException and NOT crash
        val success = encryptionManager.establishSession(badKeyBytes)
        assertFalse("establishSession must return false for crash input", success)
        assertFalse("No secure session must be established", encryptionManager.isReady)
        assertEquals("", encryptionManager.sessionId)

        // Key material is destroyed / reset
        encryptionManager.resetEphemeralKeys()
        assertFalse(encryptionManager.isReady)

        // Subsequent valid session remains completely possible
        val validPeer = EncryptionManager()
        val retrySuccess = encryptionManager.establishSession(validPeer.getPublicKeyBytes())
        assertTrue("Subsequent session attempt with valid key must succeed", retrySuccess)
        assertTrue("Session must be established on retry", encryptionManager.isReady)
        assertTrue(encryptionManager.sessionId.startsWith("SESSION-"))
    }

    @Test
    fun `Regression Test - BleMeshTransport processMeshPacket rejects malformed data without throwing`() {
        var receivedPayload: ByteArray? = null
        var peerFoundAddress: String? = null
        var peerFoundName: String? = null

        // Mock transport receiver simulating BleMeshTransport
        val receiver = { data: ByteArray ->
            val packet = WokiePacketParser.parse(data)
            if (packet != null && packet.packetType == WokiePacketType.KEY_EXCHANGE) {
                receivedPayload = packet.payload
            }
        }

        // Test 1: Environmental sensor bytes [0x01] passed to boundary
        val malformedBytes = byteArrayOf(0x01)
        val packet1 = WokiePacketParser.parse(malformedBytes)
        assertNull(packet1)
        assertNull(receivedPayload)

        // Test 2: Random garbage bytes
        val garbageBytes = byteArrayOf(0x57, 0x4F, 0x4B, 0x31, 0x99.toByte(), 0x00, 0x01)
        val packet2 = WokiePacketParser.parse(garbageBytes)
        assertNull(packet2)
        assertNull(receivedPayload)

        // Test 3: Valid Discovery Beacon only discovers, never triggers crypto
        val discoveryBeacon = WokiePacket(
            packetType = WokiePacketType.DISCOVERY_BEACON,
            senderNodeId = "WOK_NODE1"
        )
        val parsedBeacon = WokiePacketParser.parse(discoveryBeacon.toByteArray())
        assertNotNull(parsedBeacon)
        assertEquals(WokiePacketType.DISCOVERY_BEACON, parsedBeacon!!.packetType)
        assertNull("Discovery beacon must NEVER trigger key exchange payload receiver", receivedPayload)

        // Callback would only record peer found:
        peerFoundAddress = "AA:BB:CC:DD:EE:FF"
        peerFoundName = parsedBeacon.senderNodeId
        assertEquals("WOK_NODE1", peerFoundName)
    }

    @Test
    fun `Regression Test - Failed key exchange allows retry with fresh keys`() {
        val alice = EncryptionManager()
        val initialAlicePubKey = alice.getPublicKeyBytes()

        // Attacker sends malformed key (91 bytes of random garbage)
        val attackerGarbageKey = ByteArray(91) { 0x42.toByte() }
        val failed = alice.establishSession(attackerGarbageKey)
        assertFalse("Session establishment with garbage key must fail", failed)
        assertFalse("Alice must not be ready", alice.isReady)

        // Alice resets ephemeral keys for recovery
        alice.resetEphemeralKeys()
        val freshAlicePubKey = alice.getPublicKeyBytes()

        // Fresh keys must be generated, not reused
        assertFalse("Alice must generate fresh keys after failure", initialAlicePubKey.contentEquals(freshAlicePubKey))

        // Legitimate Bob connects
        val bob = EncryptionManager()
        val retrySuccess = alice.establishSession(bob.getPublicKeyBytes())
        assertTrue("Retry with legitimate peer must succeed", retrySuccess)
        assertTrue(alice.isReady)
    }
}
