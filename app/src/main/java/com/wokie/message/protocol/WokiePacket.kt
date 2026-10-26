package com.wokie.message.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class WokiePacketType(val code: Byte) {
    DISCOVERY_BEACON(0x01.toByte()),
    KEY_EXCHANGE(0x02.toByte()),
    ENCRYPTED_MESSAGE(0x03.toByte()),
    MESH_RELAY(0x04.toByte()),
    VOICE_FRAME(0x05.toByte());

    companion object {
        fun fromCode(code: Byte): WokiePacketType? = values().firstOrNull { it.code == code }
    }
}

data class WokiePacket(
    val magic: ByteArray = WOKIE_MAGIC.clone(),
    val protocolVersion: Byte = PROTOCOL_VERSION_1,
    val packetType: WokiePacketType,
    val hopLimit: Byte = 3,
    val expirationTimestamp: Long = 0L, // Unix epoch seconds
    val senderNodeId: String,
    val packetId: String = "",
    val payload: ByteArray = ByteArray(0)
) {
    fun toByteArray(): ByteArray {
        // Validate against the same rules WokiePacketParser enforces on
        // receive. Previously these were unchecked and the length fields were
        // truncated via toByte()/toShort(), producing packets the parser on
        // the other side then rejected — silent round-trip failures.
        require(senderNodeId.matches(NODE_ID_REGEX)) {
            "senderNodeId must match ${NODE_ID_REGEX.pattern}: '$senderNodeId'"
        }
        require(packetId.toByteArray(Charsets.UTF_8).size <= MAX_PACKET_ID_LENGTH) {
            "packetId exceeds $MAX_PACKET_ID_LENGTH bytes"
        }
        // The parser rejects any packet whose TOTAL size exceeds MAX_PACKET_SIZE.
        val totalSizeEstimate = 15 + senderNodeId.toByteArray(Charsets.UTF_8).size +
            packetId.toByteArray(Charsets.UTF_8).size + payload.size
        require(totalSizeEstimate <= MAX_PACKET_SIZE) {
            "packet exceeds $MAX_PACKET_SIZE bytes: $totalSizeEstimate"
        }
        require(hopLimit >= 0 && hopLimit <= MAX_HOP_LIMIT) { "hopLimit out of range: $hopLimit" }

        val nodeBytes = senderNodeId.toByteArray(Charsets.UTF_8)
        val packetIdBytes = packetId.toByteArray(Charsets.UTF_8)
        // 4 (magic) + 1 (version) + 1 (type) + 1 (hopLimit) + 4 (expiration timestamp seconds) + 1 (nodeIdLen) + N + 1 (packetIdLen) + M + 2 (payloadLen) + L
        val totalSize = 4 + 1 + 1 + 1 + 4 + 1 + nodeBytes.size + 1 + packetIdBytes.size + 2 + payload.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(magic)
        buffer.put(protocolVersion)
        buffer.put(packetType.code)
        buffer.put(hopLimit)
        buffer.putInt((expirationTimestamp and 0xFFFFFFFFL).toInt())
        buffer.put(nodeBytes.size.toByte())
        buffer.put(nodeBytes)
        buffer.put(packetIdBytes.size.toByte())
        buffer.put(packetIdBytes)
        buffer.putShort((payload.size and 0xFFFF).toShort())
        buffer.put(payload)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WokiePacket

        if (!magic.contentEquals(other.magic)) return false
        if (protocolVersion != other.protocolVersion) return false
        if (packetType != other.packetType) return false
        if (hopLimit != other.hopLimit) return false
        if (expirationTimestamp != other.expirationTimestamp) return false
        if (senderNodeId != other.senderNodeId) return false
        if (packetId != other.packetId) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = magic.contentHashCode()
        result = 31 * result + protocolVersion
        result = 31 * result + packetType.hashCode()
        result = 31 * result + hopLimit
        result = 31 * result + expirationTimestamp.hashCode()
        result = 31 * result + senderNodeId.hashCode()
        result = 31 * result + packetId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        val WOKIE_MAGIC = byteArrayOf('W'.code.toByte(), 'O'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
        const val PROTOCOL_VERSION_1: Byte = 0x01
        const val MIN_PACKET_SIZE = 16
        const val MAX_PACKET_SIZE = 65535
        const val MAX_NODE_ID_LENGTH = 64
        const val MAX_PACKET_ID_LENGTH = 64
        const val MAX_HOP_LIMIT: Byte = 15
        val NODE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    }
}
