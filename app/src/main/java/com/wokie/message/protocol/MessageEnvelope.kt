package com.wokie.message.protocol

import java.util.UUID
import java.util.Base64

data class MessageEnvelope(
    val protocolVersion: String = "1.0",
    val messageId: String = UUID.randomUUID().toString(),
    val sequenceNumber: Long,
    val messageType: String,
    val sessionId: String,
    val senderNodeId: String,
    val destinationNodeId: String = "",
    val ciphertext: ByteArray
) {
    fun toJson(): String {
        val encodedCiphertext = Base64.getEncoder().encodeToString(ciphertext)
        val json = org.json.JSONObject()
        json.put("protocolVersion", protocolVersion)
        json.put("messageId", messageId)
        json.put("sequenceNumber", sequenceNumber)
        json.put("messageType", messageType)
        json.put("sessionId", sessionId)
        json.put("senderNodeId", senderNodeId)
        json.put("destinationNodeId", destinationNodeId)
        json.put("ciphertext", encodedCiphertext)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonString: String): MessageEnvelope {
            if (jsonString.length > 50000) throw IllegalArgumentException("Payload too large")
            
            val map = try {
                org.json.JSONObject(jsonString)
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed JSON envelope")
            }
            
            val protocolVersion = map.optString("protocolVersion")
            if (protocolVersion != "1.0") throw IllegalArgumentException("Unsupported protocol version")
            
            val messageId = map.optString("messageId")
            if (messageId.isEmpty() || messageId.length > 100) throw IllegalArgumentException("Invalid messageId length")
            
            val sequenceNumber = map.optLong("sequenceNumber", -1L)
            if (sequenceNumber < 0) throw IllegalArgumentException("Missing or invalid sequenceNumber")
            
            val messageType = map.optString("messageType")
            if (messageType != "ENCRYPTED_MESSAGE") throw IllegalArgumentException("Invalid messageType")
            
            val sessionId = map.optString("sessionId")
            if (sessionId.isEmpty() || sessionId.length > 100) throw IllegalArgumentException("Invalid sessionId length")
            
            val senderNodeId = map.optString("senderNodeId")
            if (senderNodeId.isEmpty() || senderNodeId.length > 100) throw IllegalArgumentException("Invalid senderNodeId length")
            
            val destinationNodeId = map.optString("destinationNodeId")
            if (destinationNodeId.length > 100) throw IllegalArgumentException("Invalid destinationNodeId length")
            
            val ciphertextEncoded = map.optString("ciphertext")
            if (ciphertextEncoded.isEmpty()) throw IllegalArgumentException("Missing ciphertext")
            val ciphertext = try {
                Base64.getDecoder().decode(ciphertextEncoded)
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed Base64 ciphertext")
            }
            if (ciphertext.isEmpty() || ciphertext.size > 32000) throw IllegalArgumentException("Invalid ciphertext length")
            
            return MessageEnvelope(protocolVersion, messageId, sequenceNumber, messageType, sessionId, senderNodeId, destinationNodeId, ciphertext)
        }
    }
}
