package com.beam.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

/** Malformed-frame and streaming behavior of the frame codec. */
class FrameMalformedTest {
    @Test
    fun `rejects truncated buffer at every cut`() {
        val wire = FrameCodec.encode(Frame(FrameType.CTRL, "payload".toByteArray()))

        for (cut in 0 until wire.size) {
            val exception =
                assertThrows("cut at $cut", FrameCodecException::class.java) {
                    FrameCodec.decode(wire.copyOf(cut))
                }
            assertEquals(FrameCodecKind.TRUNCATED, exception.kind)
        }
    }

    @Test
    fun `rejects unknown frame type`() {
        val wire = FrameCodec.encode(Frame(FrameType.CTRL, ByteArray(1)))
        wire[4] = 0x7F

        val exception =
            assertThrows(FrameCodecException::class.java) {
                FrameCodec.decode(wire)
            }
        assertEquals(FrameCodecKind.UNKNOWN_TYPE, exception.kind)
    }

    @Test
    fun `rejects close frame with payload`() {
        val wire = FrameCodec.encode(Frame(FrameType.CTRL, "x".toByteArray()))
        wire[4] = FrameType.CLOSE.value

        val exception =
            assertThrows(FrameCodecException::class.java) {
                FrameCodec.decode(wire)
            }
        assertEquals(FrameCodecKind.INVALID, exception.kind)
    }

    @Test
    fun `stream decode reads two consecutive frames`() {
        val first = Frame(FrameType.CTRL, "one".toByteArray())
        val second = Frame(FrameType.DATA, ChunkHeader(TestIds.transferId, 0, 2).encode() + byteArrayOf(9, 9))
        val stream = ByteArrayInputStream(FrameCodec.encode(first) + FrameCodec.encode(second))

        assertEquals(first.type, FrameCodec.decodeStream(stream).type)
        val decodedSecond = FrameCodec.decodeStream(stream)
        assertEquals(FrameType.DATA, decodedSecond.type)
        assertEquals(2, ChunkHeader.decode(decodedSecond.payload).chunkLength)
    }

    @Test
    fun `stream decode reports truncation at end of stream`() {
        val empty =
            assertThrows(FrameCodecException::class.java) {
                FrameCodec.decodeStream(ByteArrayInputStream(ByteArray(0)))
            }
        assertEquals(FrameCodecKind.TRUNCATED, empty.kind)

        val partial = FrameCodec.encode(Frame(FrameType.CTRL, "abcdef".toByteArray())).copyOf(7)
        val midFrame =
            assertThrows(FrameCodecException::class.java) {
                FrameCodec.decodeStream(ByteArrayInputStream(partial))
            }
        assertEquals(FrameCodecKind.TRUNCATED, midFrame.kind)
    }

    @Test
    fun `stream decode reports unknown type mid-stream`() {
        val wire = FrameCodec.encode(Frame(FrameType.CTRL, "x".toByteArray()))
        wire[4] = 0x42

        val exception =
            assertThrows(FrameCodecException::class.java) {
                FrameCodec.decodeStream(ByteArrayInputStream(wire))
            }
        assertEquals(FrameCodecKind.UNKNOWN_TYPE, exception.kind)
    }
}
