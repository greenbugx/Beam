package com.beam.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FrameCodecTest {
    @Test
    fun `encodes big-endian length and type byte`() {
        val payload = "hello".toByteArray()
        val wire = FrameCodec.encode(Frame(FrameType.CTRL, payload))

        assertEquals(5 + payload.size, wire.size)
        // 4-byte big-endian length counts the payload only.
        assertEquals(0, wire[0].toInt())
        assertEquals(0, wire[1].toInt())
        assertEquals(0, wire[2].toInt())
        assertEquals(5, wire[3].toInt())
        assertEquals(0x01, wire[4].toInt())
    }

    @Test
    fun `round trips control frame`() {
        val frame = Frame(FrameType.CTRL, "BEAM JSON".toByteArray())
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))

        assertEquals(frame.type, decoded.type)
        assertTrue(frame.payload.contentEquals(decoded.payload))
    }

    @Test
    fun `round trips data frame with chunk header and bytes`() {
        val header = ChunkHeader(transferId = TestIds.transferId, chunkIndex = 7, chunkLength = 4)
        val payload = header.encode() + byteArrayOf(1, 2, 3, 4)
        val frame = Frame(FrameType.DATA, payload)
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))

        assertEquals(FrameType.DATA, decoded.type)
        assertEquals(header, ChunkHeader.decode(decoded.payload))
    }

    @Test
    fun `round trips empty close frame`() {
        val frame = Frame(FrameType.CLOSE, ByteArray(0))
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))

        assertEquals(FrameType.CLOSE, decoded.type)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `accepts control payload at the 64 KiB limit`() {
        val frame = Frame(FrameType.CTRL, ByteArray(FrameCodec.MAX_CONTROL_PAYLOAD))
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))

        assertEquals(FrameCodec.MAX_CONTROL_PAYLOAD, decoded.payload.size)
    }

    @Test
    fun `rejects control payload above the 64 KiB limit on decode`() {
        // Hand-build the wire bytes so decode is tested independently of encode.
        val wire = ByteArray(5 + FrameCodec.MAX_CONTROL_PAYLOAD + 1)
        FrameCodec.encode(Frame(FrameType.CTRL, ByteArray(0))).copyInto(wire)
        writeLength(wire, FrameCodec.MAX_CONTROL_PAYLOAD + 1)

        val exception =
            assertThrows(FrameCodecException::class.java) {
                FrameCodec.decode(wire)
            }
        assertEquals(FrameCodecKind.OVERSIZE, exception.kind)
    }

    @Test
    fun `rejects encoding control payload above the limit`() {
        assertThrows(FrameCodecException::class.java) {
            FrameCodec.encode(Frame(FrameType.CTRL, ByteArray(FrameCodec.MAX_CONTROL_PAYLOAD + 1)))
        }
    }

    @Test
    fun `rejects data payload above 1 MiB plus header`() {
        assertThrows(FrameCodecException::class.java) {
            FrameCodec.encode(Frame(FrameType.DATA, ByteArray(FrameCodec.MAX_DATA_PAYLOAD + 1)))
        }
    }

    private fun writeLength(
        wire: ByteArray,
        length: Int,
    ) {
        wire[0] = (length ushr 24).toByte()
        wire[1] = (length ushr 16).toByte()
        wire[2] = (length ushr 8).toByte()
        wire[3] = length.toByte()
    }
}

/** Shared deterministic identifiers for protocol tests. */
object TestIds {
    val transferId: java.util.UUID = java.util.UUID.fromString("9c1f0000-0000-4000-8000-000000000042")
}
