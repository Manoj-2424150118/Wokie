package com.wokie.message.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Regression tests for the socket framing bug: the transports used to write
 * the packet length as a SINGLE BYTE and read the payload with one read(),
 * corrupting every packet over 255 bytes and any packet delivered in
 * multiple TCP segments.
 */
class PacketFramingTest {

    /** An InputStream that returns at most [chunk] bytes per read call. */
    private class TricklingInputStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int {
            if (pos >= data.size) return -1
            return data[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(chunk, len, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun framed(packet: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        PacketFraming.writePacket(out, packet)
        return out.toByteArray()
    }

    @Test
    fun roundTrip_smallPacket() {
        val packet = "hello".toByteArray()
        val back = PacketFraming.readPacket(ByteArrayInputStream(framed(packet)))
        assertArrayEquals(packet, back)
    }

    @Test
    fun roundTrip_packetOver255Bytes() {
        // THE regression case: 1,000 bytes cannot be expressed in the old
        // single-byte length (1000 mod 256 = 232) and desynchronised the stream.
        val packet = ByteArray(1000) { (it % 251).toByte() }
        val back = PacketFraming.readPacket(ByteArrayInputStream(framed(packet)))
        assertArrayEquals(packet, back)
    }

    @Test
    fun roundTrip_twoPacketsBackToBack() {
        val a = ByteArray(300) { 0x0A }
        val b = "second".toByteArray()
        val stream = ByteArrayInputStream(framed(a) + framed(b))
        assertArrayEquals(a, PacketFraming.readPacket(stream))
        assertArrayEquals(b, PacketFraming.readPacket(stream))
        assertNull(PacketFraming.readPacket(stream))
    }

    @Test
    fun readPacket_handlesPartialTcpReads() {
        val packet = ByteArray(500) { (255 - it % 256).toByte() }
        val trickling = TricklingInputStream(framed(packet), chunk = 1)
        val back = PacketFraming.readPacket(trickling)
        assertArrayEquals(packet, back)
    }

    @Test
    fun readPacket_emptyStreamReturnsNull() {
        assertNull(PacketFraming.readPacket(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun readPacket_oversizedLengthThrows() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F)) // ~2 GB length prefix
        try {
            PacketFraming.readPacket(ByteArrayInputStream(out.toByteArray()))
            fail("expected EOFException for oversized length")
        } catch (expected: EOFException) {
            // ok
        }
    }

    @Test
    fun readPacket_truncatedPayloadThrows() {
        val framedBytes = framed(ByteArray(100) { 1 })
        val truncated = framedBytes.copyOfRange(0, framedBytes.size - 40)
        try {
            PacketFraming.readPacket(ByteArrayInputStream(truncated))
            fail("expected EOFException for truncated payload")
        } catch (expected: EOFException) {
            // ok
        }
    }

    @Test
    fun writePacket_rejectsOversized() {
        try {
            PacketFraming.writePacket(ByteArrayOutputStream(), ByteArray(PacketFraming.MAX_PACKET_SIZE + 1))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun lengthPrefix_isFourByteBigEndian() {
        val bytes = framed(ByteArray(258))
        assertEquals(0, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(1, bytes[2].toInt())
        assertEquals(2, bytes[3].toInt())
        assertTrue(bytes.size == 258 + 4)
    }
}
