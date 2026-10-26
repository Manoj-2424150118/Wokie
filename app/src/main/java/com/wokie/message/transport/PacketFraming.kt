package com.wokie.message.transport

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed packet framing shared by the socket transports
 * (INTERNET, WIFI DIRECT, HOTSPOT).
 *
 * Wire format: 4-byte big-endian length, followed by the packet bytes.
 *
 * The previous framing wrote the length with a single-byte
 * `OutputStream.write(packet.size)` call — which keeps only the low 8 bits —
 * and read the payload with a single `read()` that TCP does not guarantee to
 * fill. Any packet over 255 bytes (i.e. most encrypted messages, whose JSON
 * envelope + Base64 ciphertext routinely exceed that) desynchronised the
 * stream and corrupted every following packet.
 */
object PacketFraming {

    /** Sanity cap so a corrupt/hostile length prefix cannot force a huge allocation. */
    const val MAX_PACKET_SIZE = 1 shl 20 // 1 MiB

    fun writePacket(out: OutputStream, packet: ByteArray) {
        require(packet.size <= MAX_PACKET_SIZE) { "packet too large: ${packet.size}" }
        val header = byteArrayOf(
            (packet.size ushr 24).toByte(),
            (packet.size ushr 16).toByte(),
            (packet.size ushr 8).toByte(),
            packet.size.toByte()
        )
        out.write(header)
        out.write(packet)
        out.flush()
    }

    /**
     * Reads exactly one framed packet, looping until the full payload has
     * arrived (TCP may deliver it in several segments).
     *
     * @return the packet, or null on a clean end-of-stream before a new header.
     * @throws EOFException on a truncated header/payload or an invalid length.
     */
    fun readPacket(input: InputStream): ByteArray? {
        val dis = if (input is DataInputStream) input else DataInputStream(input)
        val length = try {
            dis.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (length < 0 || length > MAX_PACKET_SIZE) {
            throw EOFException("invalid packet length: $length")
        }
        val buffer = ByteArray(length)
        dis.readFully(buffer)
        return buffer
    }
}
