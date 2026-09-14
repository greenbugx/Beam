package com.beam.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ChunkHeaderTest {
    @Test
    fun `encodes the fixed 28-byte big-endian layout`() {
        val transferId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        val header = ChunkHeader(transferId = transferId, chunkIndex = 7, chunkLength = 4096)
        val wire = header.encode()

        assertEquals(28, wire.size)
        assertEquals(0x00, wire[0].toInt() and 0xFF) // first UUID byte
        assertEquals(0x2233, (wire[2].toInt() and 0xFF) shl 8 or (wire[3].toInt() and 0xFF))
        // chunkIndex = 7 at bytes 16..23
        assertEquals(0, wire[16].toInt())
        assertEquals(7, wire[23].toInt())
        // chunkLength = 4096 = 0x00001000 at bytes 24..27
        assertEquals(0x10, wire[26].toInt() and 0xFF)
        assertEquals(0x00, wire[27].toInt() and 0xFF)
    }

    @Test
    fun `round trips random uuid with large index and length`() {
        val header =
            ChunkHeader(
                transferId = UUID.randomUUID(),
                chunkIndex = 1L shl 40,
                chunkLength = FrameCodec.MAX_CHUNK_SIZE,
            )
        assertEquals(header, ChunkHeader.decode(header.encode()))
    }

    @Test
    fun `round trips zero-length final-padded chunk`() {
        val header = ChunkHeader(transferId = TestIds.transferId, chunkIndex = 0, chunkLength = 0)
        assertEquals(header, ChunkHeader.decode(header.encode()))
    }

    @Test
    fun `preserves the uuid exactly`() {
        val header = ChunkHeader(transferId = TestIds.transferId, chunkIndex = 123, chunkLength = 64)
        val decoded = ChunkHeader.decode(header.encode())

        assertTrue(decoded.transferId == TestIds.transferId)
        assertEquals(123L, decoded.chunkIndex)
        assertEquals(64, decoded.chunkLength)
    }

    @Test
    fun `rejects short header buffer`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChunkHeader.decode(ByteArray(17))
        }
    }

    @Test
    fun `rejects negative index and length`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChunkHeader(transferId = TestIds.transferId, chunkIndex = -1, chunkLength = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChunkHeader(transferId = TestIds.transferId, chunkIndex = 0, chunkLength = -1)
        }
    }
}
