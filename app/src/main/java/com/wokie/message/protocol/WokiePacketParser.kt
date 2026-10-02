package com.wokie.message.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class ParseResult {
    data class Success(val packet: WokiePacket) : ParseResult()
    data class Invalid(val reason: String) : ParseResult()
}

object WokiePacketParser {

    fun parse(bytes: ByteArray?): WokiePacket? {
        val result = parseWithDetails(bytes)
        return if (result is ParseResult.Success) result.packet else null
    }

    fun parseWithDetails(bytes: ByteArray?): ParseResult {
        if (bytes == null || bytes.isEmpty()) {
            return ParseResult.Invalid("Empty or null packet bytes")
        }

        // 1. Minimum packet size check
        if (bytes.size < WokiePacket.MIN_PACKET_SIZE) {
            return ParseResult.Invalid("Packet size ${bytes.size} below minimum ${WokiePacket.MIN_PACKET_SIZE}")
        }
        if (bytes.size > WokiePacket.MAX_PACKET_SIZE) {
            return ParseResult.Invalid("Packet size ${bytes.size} exceeds maximum ${WokiePacket.MAX_PACKET_SIZE}")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // 2. Magic bytes check
        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(WokiePacket.WOKIE_MAGIC)) {
            return ParseResult.Invalid("Invalid magic bytes")
        }

        // 3. Protocol version check
        val version = buffer.get()
        if (version != WokiePacket.PROTOCOL_VERSION_1) {
            return ParseResult.Invalid("Unsupported protocol version: $version")
        }

        // 4. Packet type check
        val typeCode = buffer.get()
        val packetType = WokiePacketType.fromCode(typeCode)
            ?: return ParseResult.Invalid("Unknown packet type: $typeCode")

        // 5. Hop limit check
        val hopLimit = buffer.get()
        if (hopLimit < 0 || hopLimit > WokiePacket.MAX_HOP_LIMIT) {
            return ParseResult.Invalid("Invalid hop limit: $hopLimit")
        }

        // 6. Expiration timestamp check (seconds epoch)
        val expirationInt = buffer.int
        val expiration = expirationInt.toLong() and 0xFFFFFFFFL
        val currentSeconds = System.currentTimeMillis() / 1000
        if (expiration > 0 && expiration < currentSeconds) {
            return ParseResult.Invalid("Packet expired at $expiration (now: $currentSeconds)")
        }

        // 7. Sender Node ID check
        if (buffer.remaining() < 1) {
            return ParseResult.Invalid("Truncated buffer before nodeId length")
        }
        val nodeIdLen = buffer.get().toInt() and 0xFF
        if (nodeIdLen <= 0 || nodeIdLen > WokiePacket.MAX_NODE_ID_LENGTH) {
            return ParseResult.Invalid("Invalid nodeId length: $nodeIdLen")
        }
        if (buffer.remaining() < nodeIdLen) {
            return ParseResult.Invalid("Truncated buffer while reading nodeId")
        }
        val nodeIdBytes = ByteArray(nodeIdLen)
        buffer.get(nodeIdBytes)
        val senderNodeId = String(nodeIdBytes, Charsets.UTF_8)
        if (!WokiePacket.NODE_ID_REGEX.matches(senderNodeId)) {
            return ParseResult.Invalid("Malformed nodeId format: $senderNodeId")
        }

        // 8. Message/Packet ID check
        if (buffer.remaining() < 1) {
            return ParseResult.Invalid("Truncated buffer before packetId length")
        }
        val packetIdLen = buffer.get().toInt() and 0xFF
        if (packetIdLen > WokiePacket.MAX_PACKET_ID_LENGTH) {
            return ParseResult.Invalid("Invalid packetId length: $packetIdLen")
        }
        if (buffer.remaining() < packetIdLen) {
            return ParseResult.Invalid("Truncated buffer while reading packetId")
        }
        val packetIdBytes = ByteArray(packetIdLen)
        buffer.get(packetIdBytes)
        val packetId = String(packetIdBytes, Charsets.UTF_8)

        // For messages and relays, packetId must not be empty
        if ((packetType == WokiePacketType.ENCRYPTED_MESSAGE || packetType == WokiePacketType.MESH_RELAY) && packetId.isEmpty()) {
            return ParseResult.Invalid("Missing required packetId for $packetType")
        }

        // 9. Payload length check
        if (buffer.remaining() < 2) {
            return ParseResult.Invalid("Truncated buffer before payload length")
        }
        val declaredPayloadLength = buffer.short.toInt() and 0xFFFF
        val actualRemaining = buffer.remaining()
        if (declaredPayloadLength != actualRemaining) {
            return ParseResult.Invalid("Payload length mismatch: declared $declaredPayloadLength != actual $actualRemaining")
        }

        val payload = ByteArray(declaredPayloadLength)
        buffer.get(payload)

        // 10. Public-Key payload validation if KEY_EXCHANGE
        if (packetType == WokiePacketType.KEY_EXCHANGE) {
            if (!validateX509EcPublicKey(payload)) {
                return ParseResult.Invalid("Invalid X.509 EC public key payload")
            }
        }

        return ParseResult.Success(
            WokiePacket(
                magic = magic,
                protocolVersion = version,
                packetType = packetType,
                hopLimit = hopLimit,
                expirationTimestamp = expiration,
                senderNodeId = senderNodeId,
                packetId = packetId,
                payload = payload
            )
        )
    }

    /**
     * Strict validation of X.509 SubjectPublicKeyInfo EC P-256 public key structure.
     * Expected DER header for secp256r1 (91 bytes):
     * 30 59 30 13 06 07 2A 86 48 CE 3D 02 01 06 08 2A 86 48 CE 3D 03 01 07 03 42 00 04 [64 coords]
     */
    fun validateX509EcPublicKey(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size != 91) return false
        if (bytes[0] != 0x30.toByte() || bytes[1] != 0x59.toByte()) return false
        if (bytes[2] != 0x30.toByte() || bytes[3] != 0x13.toByte()) return false
        
        // ecPublicKey OID (1.2.840.10045.2.1)
        val ecOid = byteArrayOf(0x06, 0x07, 0x2A.toByte(), 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)
        for (i in ecOid.indices) {
            if (bytes[4 + i] != ecOid[i]) return false
        }
        
        // prime256v1 OID (1.2.840.10045.3.1.7)
        val curveOid = byteArrayOf(0x06, 0x08, 0x2A.toByte(), 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)
        for (i in curveOid.indices) {
            if (bytes[13 + i] != curveOid[i]) return false
        }
        
        // BIT STRING header (0x03, 0x42, 0x00)
        if (bytes[23] != 0x03.toByte() || bytes[24] != 0x42.toByte() || bytes[25] != 0x00.toByte()) return false
        // Uncompressed EC point marker (0x04)
        if (bytes[26] != 0x04.toByte()) return false

        return true
    }
}
