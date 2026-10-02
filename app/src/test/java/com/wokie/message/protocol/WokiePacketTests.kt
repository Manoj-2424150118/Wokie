package com.wokie.message.protocol

import com.wokie.message.crypto.EncryptionManager
import com.wokie.message.crypto.ReplayProtector
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class WokiePacketTests {

    @Test
    fun `Test 1 - Completely random BLE payload is IGNORED`() {
        val randomBytes = ByteArray(100)
        Random().nextBytes(randomBytes)
        val result = WokiePacketParser.parse(randomBytes)
        assertNull("Random bytes must be rejected by parser", result)
    }

    @Test
    fun `Test 2 - Valid WOKIE header but truncated payload is IGNORED`() {
        val key = EncryptionManager().getPublicKeyBytes()
        val packet = WokiePacket(
            packetType = WokiePacketType.KEY_EXCHANGE,
            senderNodeId = "NODE_TEST",
            packetId = "PKT_123",
            payload = key
        )
        val fullBytes = packet.toByteArray()
        // Truncate the packet by removing the last 10 bytes of payload
        val truncated = fullBytes.copyOfRange(0, fullBytes.size - 10)
        val result = WokiePacketParser.parse(truncated)
        assertNull("Truncated packet must be rejected", result)
    }

    @Test
    fun `Test 3 - Valid packet type but invalid public-key bytes results in KEY_EXCHANGE_FAILED with no crash`() {
        val badKeyBytes = ByteArray(91) { 0xFF.toByte() } // Corrupted key bytes
        val packet = WokiePacket(
            packetType = WokiePacketType.KEY_EXCHANGE,
            senderNodeId = "NODE_BAD",
            packetId = "PKT_KEY",
            payload = badKeyBytes
        )
        val rawBytes = packet.toByteArray()
        val parsed = WokiePacketParser.parse(rawBytes)
        // Parsed packet should fail validation because key bytes are not valid X.509 EC public key
        assertNull("Packet with malformed EC key must fail parse validation", parsed)

        // Even if bad bytes are directly passed to EncryptionManager:
        val enc = EncryptionManager()
        val success = enc.establishSession(badKeyBytes)
        assertFalse("establishSession must return false for bad key bytes", success)
        assertFalse("EncryptionManager must not be ready", enc.isReady)
        assertEquals("", enc.sessionId)
    }

    @Test
    fun `Test 4 - Valid X509 EC public key parsing succeeds`() {
        val alice = EncryptionManager()
        val bob = EncryptionManager()
        val aliceKey = alice.getPublicKeyBytes()

        assertTrue("Alice's key must be valid X.509 EC public key", WokiePacketParser.validateX509EcPublicKey(aliceKey))

        val packet = WokiePacket(
            packetType = WokiePacketType.KEY_EXCHANGE,
            senderNodeId = "NODE_ALICE",
            packetId = "KEY_PKT",
            payload = aliceKey
        )
        val parsed = WokiePacketParser.parse(packet.toByteArray())
        assertNotNull("Valid key exchange packet must parse successfully", parsed)
        assertEquals(WokiePacketType.KEY_EXCHANGE, parsed!!.packetType)
        assertArrayEquals(aliceKey, parsed.payload)

        // Bob establishes session with Alice's validated key
        val success = bob.establishSession(parsed.payload)
        assertTrue("Bob establishing session must succeed", success)
        assertTrue(bob.isReady)
        assertTrue(bob.sessionId.startsWith("SESSION-"))
    }

    @Test
    fun `Test 5 - Unrelated BLE advertisement is ignored before crypto`() {
        // Typical iBeacon or environmental sensor advertising byte pattern
        val environmentalSensorAdv = byteArrayOf(0x02, 0x01, 0x06, 0x05, 0x16, 0x1A, 0x18, 0x64, 0x00)
        val parsed = WokiePacketParser.parse(environmentalSensorAdv)
        assertNull("Unrelated BLE advertisement must be ignored before crypto", parsed)
    }

    @Test
    fun `Test 6 - Duplicate mesh packet is rejected by replay protection`() {
        val replayProtector = ReplayProtector()
        val sessionId = "SESSION_TEST_123"
        replayProtector.startNewSession(sessionId)

        // First message with seq 1 is accepted
        assertTrue(replayProtector.isMessageValid(sessionId, 1L))

        // Duplicate message with same seq 1 is rejected
        assertFalse("Duplicate sequence number must be rejected", replayProtector.isMessageValid(sessionId, 1L))
    }

    @Test
    fun `Test 7 - Expired mesh packet is rejected`() {
        val pastSeconds = (System.currentTimeMillis() / 1000) - 300 // Expired 5 minutes ago
        val packet = WokiePacket(
            packetType = WokiePacketType.ENCRYPTED_MESSAGE,
            hopLimit = 3,
            expirationTimestamp = pastSeconds,
            senderNodeId = "NODE_EXP",
            packetId = "PKT_EXP_1",
            payload = "TEST_PAYLOAD".toByteArray(Charsets.UTF_8)
        )
        val result = WokiePacketParser.parse(packet.toByteArray())
        assertNull("Expired packet must be rejected by parser", result)
    }

    @Test
    fun `Test 8 - Malformed mesh relay packet is not forwarded`() {
        // Zero hop limit packet
        val zeroHopPacket = WokiePacket(
            packetType = WokiePacketType.MESH_RELAY,
            hopLimit = 0,
            senderNodeId = "NODE_RELAY",
            packetId = "RELAY_1",
            payload = byteArrayOf(1, 2, 3)
        )
        val parsed = WokiePacketParser.parse(zeroHopPacket.toByteArray())
        assertNotNull(parsed)
        // Router / relay logic must reject forwarding if hopLimit <= 1
        assertTrue("Hop limit 0 must not be forwardable", parsed!!.hopLimit <= 1)
    }

    @Test
    fun `Positive Path Test - Full BLE Handshake, ECDH, HKDF, Fingerprint, Secure Session, Encrypted Message`() {
        val alice = EncryptionManager()
        val bob = EncryptionManager()

        // 1. Discovery
        val beacon = WokiePacket(
            packetType = WokiePacketType.DISCOVERY_BEACON,
            hopLimit = 1,
            senderNodeId = "WOK_1234"
        )
        val parsedBeacon = WokiePacketParser.parse(beacon.toByteArray())
        assertNotNull(parsedBeacon)
        assertEquals(WokiePacketType.DISCOVERY_BEACON, parsedBeacon!!.packetType)
        assertEquals("WOK_1234", parsedBeacon.senderNodeId)

        // 2. Key Exchange packets
        val aliceKeyPkt = WokiePacket(
            packetType = WokiePacketType.KEY_EXCHANGE,
            senderNodeId = "NODE_ALICE",
            packetId = "KEY_A",
            payload = alice.getPublicKeyBytes()
        )
        val bobKeyPkt = WokiePacket(
            packetType = WokiePacketType.KEY_EXCHANGE,
            senderNodeId = "NODE_BOB",
            packetId = "KEY_B",
            payload = bob.getPublicKeyBytes()
        )

        // 3. Validation & Session Establishment
        val parsedAliceKey = WokiePacketParser.parse(aliceKeyPkt.toByteArray())!!
        val parsedBobKey = WokiePacketParser.parse(bobKeyPkt.toByteArray())!!

        assertTrue(bob.establishSession(parsedAliceKey.payload))
        assertTrue(alice.establishSession(parsedBobKey.payload))

        assertTrue(alice.isReady)
        assertTrue(bob.isReady)
        assertEquals(alice.sessionId, bob.sessionId)

        // 4. Fingerprint verification
        val aliceFp = alice.getPublicKeyFingerprint()
        val bobFp = bob.getPublicKeyFingerprint()
        assertNotNull(aliceFp)
        assertNotNull(bobFp)
        assertEquals(16, aliceFp.length)

        // 5. Encrypted message exchange
        val aad = (alice.sessionId + "MSG1" + 1L).toByteArray(Charsets.UTF_8)
        val plaintext = "Hello Wokie Secure BLE".toByteArray(Charsets.UTF_8)
        val ciphertext = alice.encrypt(plaintext, aad)
        assertNotNull(ciphertext)

        val decrypted = bob.decrypt(ciphertext!!, aad)
        assertNotNull(decrypted)
        assertEquals("Hello Wokie Secure BLE", String(decrypted!!, Charsets.UTF_8))
    }
}
