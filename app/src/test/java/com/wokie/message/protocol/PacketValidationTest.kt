package com.wokie.message.protocol

import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Writer-side validation: toByteArray() must refuse anything the parser
 * would reject on the receiving side. Before this validation existed, the
 * length fields were truncated with toByte()/toShort() and such packets
 * failed silently at the peer.
 */
class PacketValidationTest {

    private fun packet(
        sender: String = "WOKIE_NODE_1234",
        packetId: String = "pkt-1",
        payload: ByteArray = byteArrayOf(1, 2, 3)
    ) = WokiePacket(
        packetType = WokiePacketType.ENCRYPTED_MESSAGE,
        senderNodeId = sender,
        packetId = packetId,
        payload = payload
    )

    @Test
    fun validPacket_roundTrips() {
        val bytes = packet().toByteArray()
        assertNotNull(WokiePacketParser.parse(bytes))
    }

    @Test
    fun illegalCharactersInNodeId_rejected() {
        try {
            packet(sender = "bad node!").toByteArray()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun nodeIdTooLong_rejected() {
        try {
            packet(sender = "A".repeat(65)).toByteArray()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun packetIdTooLong_rejected() {
        try {
            packet(packetId = "p".repeat(65)).toByteArray()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun oversizedPayload_rejected() {
        try {
            packet(payload = ByteArray(70_000)).toByteArray()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
