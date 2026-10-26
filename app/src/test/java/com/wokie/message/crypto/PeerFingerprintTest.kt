package com.wokie.message.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the fingerprint-verification bug: the UI displayed
 * this device's OWN public-key fingerprint as if it were the peer's, so two
 * honest devices could never show matching fingerprints.
 */
class PeerFingerprintTest {

    @Test
    fun peerFingerprint_nullBeforeKeyExchange() {
        val em = EncryptionManager()
        assertNull(em.getPeerPublicKeyFingerprint())
    }

    @Test
    fun peerFingerprint_matchesPeersOwnFingerprintAfterExchange() {
        val alice = EncryptionManager()
        val bob = EncryptionManager()

        assertTrue(alice.establishSession(bob.getPublicKeyBytes()))
        assertTrue(bob.establishSession(alice.getPublicKeyBytes()))

        // What Alice displays as "Bob's fingerprint" must equal what Bob
        // displays as his own fingerprint — and vice versa.
        assertEquals(bob.getPublicKeyFingerprint(), alice.getPeerPublicKeyFingerprint())
        assertEquals(alice.getPublicKeyFingerprint(), bob.getPeerPublicKeyFingerprint())
    }

    @Test
    fun ownAndPeerFingerprints_differ() {
        val alice = EncryptionManager()
        val bob = EncryptionManager()
        assertTrue(alice.establishSession(bob.getPublicKeyBytes()))
        assertNotEquals(alice.getPublicKeyFingerprint(), alice.getPeerPublicKeyFingerprint())
    }
}
