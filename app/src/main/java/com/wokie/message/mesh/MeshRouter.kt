package com.wokie.message.mesh

import android.util.Log
import com.wokie.message.protocol.MessageEnvelope
import com.wokie.message.protocol.WokiePacket
import com.wokie.message.protocol.WokiePacketParser
import com.wokie.message.protocol.WokiePacketType
import com.wokie.message.transport.WokieTransport

data class Route(val nextHopNodeId: String, val transportType: String, val distance: Int)

class MeshRouter(
    val localNodeId: String,
    private val onMessageForLocalNode: (ByteArray) -> Unit
) {
    private val routingTable = mutableMapOf<String, Route>()
    private val storeAndForwardQueue = mutableListOf<MessageEnvelope>()
    private var activeTransport: WokieTransport? = null
    private val seenPacketIds = LinkedHashSet<String>()
    private val MAX_SEEN_PACKETS = 1000

    fun setTransport(transport: WokieTransport) {
        this.activeTransport = transport
        transport.setReceiver { payload ->
            handleIncomingPacket(payload)
        }
    }

    private fun handleIncomingPacket(payload: ByteArray) {
        if (payload.isEmpty()) return

        // 1. Try parsing through WokiePacketParser
        val packet = WokiePacketParser.parse(payload)
        if (packet != null) {
            when (packet.packetType) {
                WokiePacketType.KEY_EXCHANGE -> {
                    onMessageForLocalNode(payload)
                }
                WokiePacketType.ENCRYPTED_MESSAGE -> {
                    routeEnvelopePayload(packet.payload, payload)
                }
                WokiePacketType.MESH_RELAY -> {
                    if (packet.hopLimit <= 1) return
                    synchronized(seenPacketIds) {
                        if (packet.packetId.isNotEmpty() && !seenPacketIds.add(packet.packetId)) return
                        if (seenPacketIds.size > MAX_SEEN_PACKETS) seenPacketIds.remove(seenPacketIds.iterator().next())
                    }
                    val nowSec = System.currentTimeMillis() / 1000
                    if (packet.expirationTimestamp > 0 && packet.expirationTimestamp < nowSec) return
                    val forwarded = packet.copy(hopLimit = (packet.hopLimit - 1).toByte())
                    activeTransport?.send(forwarded.toByteArray())
                }
                WokiePacketType.VOICE_FRAME,
                WokiePacketType.DISCOVERY_BEACON -> {
                    onMessageForLocalNode(payload)
                }
            }
            return
        }

        // 2. Fallback for legacy test packet format
        val type = payload[0]
        val data = payload.copyOfRange(1, payload.size)

        if (type == 0x01.toByte()) {
            onMessageForLocalNode(payload)
            return
        }

        if (type == 0x02.toByte()) {
            routeEnvelopePayload(data, payload)
        }
    }

    private fun routeEnvelopePayload(envelopeBytes: ByteArray, rawPayload: ByteArray) {
        try {
            val envelope = MessageEnvelope.fromJson(String(envelopeBytes, Charsets.UTF_8))
            if (envelope.destinationNodeId == "" || envelope.destinationNodeId == localNodeId) {
                onMessageForLocalNode(rawPayload)
            } else {
                val route = routingTable[envelope.destinationNodeId]
                if (route != null) {
                    Log.d("WOKIE_E2EE", "Routing envelope for ${envelope.destinationNodeId} -> Forwarding via ${route.transportType}")
                    activeTransport?.send(rawPayload)
                } else {
                    Log.d("WOKIE_E2EE", "Routing envelope for ${envelope.destinationNodeId} -> Stored in forwarding queue")
                    storeAndForwardQueue.add(envelope)
                }
            }
        } catch (e: Exception) {
            Log.d("WOKIE_E2EE", "MESH_ROUTER parse failed: ${e.message}")
        }
    }

    fun mockReceiveMessageForOtherNode(destinationNodeId: String) {
        Log.d("WOKIE_E2EE", "Routing envelope for $destinationNodeId -> Stored in forwarding queue")
        val mockEnv = MessageEnvelope(
            sequenceNumber = 1L,
            messageType = "ENCRYPTED_MESSAGE",
            sessionId = "mock-session",
            senderNodeId = "mock-sender",
            ciphertext = byteArrayOf(0)
        )
        storeAndForwardQueue.add(mockEnv)
        Log.d("WOKIE_E2EE", "Store-and-forward queue size: ${storeAndForwardQueue.size}")
    }
}
