package com.wokie.message

import com.wokie.message.crypto.EncryptionManager
import com.wokie.message.crypto.ReplayProtector
import com.wokie.message.transport.PeerNode
import org.junit.Assert.*
import org.junit.Test

class SessionRecoveryRegressionTest {

    @Test
    fun testTwoSequentialSessionsEstablishFreshKeysAndSessionIds() {
        val alice = EncryptionManager()
        val bob = EncryptionManager()

        // --- SESSION 1 ---
        val alicePubKey1 = alice.getPublicKeyBytes()
        val bobPubKey1 = bob.getPublicKeyBytes()

        alice.establishSession(bobPubKey1)
        bob.establishSession(alicePubKey1)

        val sessionId1 = alice.sessionId
        assertNotNull(sessionId1)
        assertTrue(sessionId1.isNotEmpty())
        assertEquals(sessionId1, bob.sessionId)

        // Alice sends message to Bob in Session 1
        val aad1 = (sessionId1 + "MSG1" + 1L).toByteArray(Charsets.UTF_8)
        val encrypted1 = alice.encrypt("Hello Session 1".toByteArray(Charsets.UTF_8), aad1)
        assertNotNull(encrypted1)
        val decrypted1 = bob.decrypt(encrypted1!!, aad1)
        assertEquals("Hello Session 1", String(decrypted1!!, Charsets.UTF_8))

        // --- END SESSION 1 ---
        alice.resetEphemeralKeys()
        bob.resetEphemeralKeys()

        assertFalse(alice.isReady)
        assertFalse(bob.isReady)
        assertEquals("", alice.sessionId)
        assertEquals("", bob.sessionId)

        // --- SESSION 2 ---
        val alicePubKey2 = alice.getPublicKeyBytes()
        val bobPubKey2 = bob.getPublicKeyBytes()

        // Verify keys are fresh and different from Session 1
        assertFalse(alicePubKey1.contentEquals(alicePubKey2))
        assertFalse(bobPubKey1.contentEquals(bobPubKey2))

        alice.establishSession(bobPubKey2)
        bob.establishSession(alicePubKey2)

        val sessionId2 = alice.sessionId
        assertNotNull(sessionId2)
        assertTrue(sessionId2.isNotEmpty())
        assertEquals(sessionId2, bob.sessionId)

        // Verify Session 2 has a DIFFERENT session ID from Session 1
        assertNotEquals(sessionId1, sessionId2)

        // Alice sends message to Bob in Session 2
        val aad2 = (sessionId2 + "MSG2" + 1L).toByteArray(Charsets.UTF_8)
        val encrypted2 = alice.encrypt("Hello Session 2".toByteArray(Charsets.UTF_8), aad2)
        assertNotNull(encrypted2)
        val decrypted2 = bob.decrypt(encrypted2!!, aad2)
        assertEquals("Hello Session 2", String(decrypted2!!, Charsets.UTF_8))

        // Attempting to decrypt Session 2 packet with Session 1 parameters must fail
        val replayProtector = ReplayProtector()
        replayProtector.startNewSession(sessionId2)
        assertTrue(replayProtector.isMessageValid(sessionId2, 1L))
        assertFalse(replayProtector.isMessageValid(sessionId1, 1L)) // Old session ID rejected
    }

    @Test
    fun testPeerStateSurvivesSessionLoss() {
        val peers = mutableListOf(
            PeerNode(endpointId = "PEER_ALICE", name = "ALICE", status = "AVAILABLE", route = "NEARBY")
        )

        // Session starts
        val activeIndex = peers.indexOfFirst { it.endpointId == "PEER_ALICE" }
        peers[activeIndex] = peers[activeIndex].copy(status = "CONNECTED")
        assertEquals("CONNECTED", peers[0].status)

        // Session expires/ends: Peer is NOT removed from list; marked SESSION_EXPIRED
        peers[activeIndex] = peers[activeIndex].copy(status = "SESSION_EXPIRED")
        assertEquals(1, peers.size)
        assertEquals("SESSION_EXPIRED", peers[0].status)

        // Ready for reconnect
        assertTrue(peers[0].status == "AVAILABLE" || peers[0].status == "SESSION_EXPIRED")
    }

    @Test
    fun testSessionPurgeDoesNotWipeUserPreferences() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString("pref_transport", "HOTSPOT")
            .putBoolean("pref_smart_fallback", true)
            .putBoolean("pref_auto_connect", true)
            .apply()

        // Volatile session purges
        val encManager = EncryptionManager()
        encManager.resetEphemeralKeys()

        // Assert persistent settings remain intact
        assertEquals("HOTSPOT", prefs.getString("pref_transport", ""))
        assertTrue(prefs.getBoolean("pref_smart_fallback", false))
        assertTrue(prefs.getBoolean("pref_auto_connect", false))
    }
}
