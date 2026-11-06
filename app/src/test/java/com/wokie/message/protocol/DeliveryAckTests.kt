package com.wokie.message.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Delivery receipts (v1.2.0): a DELIVERY_ACK packet quotes the
 * messageId it acknowledges. These tests pin the wire behaviour —
 * the packet must round-trip through the real serializer/parser so
 * sender and receiver agree on type and payload.
 */
class DeliveryAckTests {

    @Test
    fun deliveryAck_roundTripsThroughParser() {
        val ackedMessageId = "3f6d1c2e-9a4b-4c8d-b5e6-7a8b9c0d1e2f"
        val packet = WokiePacket(
            packetType = WokiePacketType.DELIVERY_ACK,
            senderNodeId = "NODE_TEST",
            packetId = "ack-packet-1",
            payload = ackedMessageId.toByteArray(Charsets.UTF_8)
        )
        val parsed = WokiePacketParser.parse(packet.toByteArray())
        assertNotNull(parsed)
        assertEquals(WokiePacketType.DELIVERY_ACK, parsed!!.packetType)
        assertEquals(ackedMessageId, String(parsed.payload, Charsets.UTF_8))
        assertEquals("NODE_TEST", parsed.senderNodeId)
    }

    @Test
    fun deliveryAck_hasStableWireCode() {
        // 0x06 is the on-the-wire contract; renumbering would silently
        // break receipts between app versions.
        assertEquals(0x06.toByte(), WokiePacketType.DELIVERY_ACK.code)
        assertEquals(WokiePacketType.DELIVERY_ACK, WokiePacketType.fromCode(0x06.toByte()))
    }
}
