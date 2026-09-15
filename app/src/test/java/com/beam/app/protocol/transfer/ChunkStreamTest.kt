@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.beam.app.protocol.transfer

import com.beam.app.protocol.ChunkHeader
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private fun chunkPayload(index: Long): ByteArray =
    ByteArray(256) { byteIndex -> ((index * 31 + byteIndex) % 251).toByte() }

private fun sourceBytes(chunkCount: Int): ByteArray {
    val bytes = ByteArray(chunkCount * 256)
    for (index in 0 until chunkCount) {
        chunkPayload(index.toLong()).copyInto(bytes, index * 256)
    }
    return bytes
}

private fun testMetadata(
    sizeBytes: Long,
    chunkSize: Int,
): FileMetadata =
    FileMetadata(
        transferId = UUID.randomUUID().toString(),
        fileId = "0123456789abcdef",
        name = "test.bin",
        mime = "application/octet-stream",
        sizeBytes = sizeBytes,
        sha256 = "a".repeat(64),
        chunkSize = chunkSize,
        chunkCount = FileMetadata.derivedChunkCount(sizeBytes, chunkSize),
    )

private class PipedTransfer(
    metadata: FileMetadata,
    tempDir: File,
    window: Int = ChunkPlan.DEFAULT_WINDOW_CHUNKS,
) : TransferWire {
    private val source = sourceBytes(metadata.chunkCount.toInt())

    val sender =
        ChunkSender(
            metadata = metadata,
            openStream = { ByteArrayInputStream(source) },
            wire = this,
            window = window,
        )
    val receiver = ChunkReceiver(metadata, tempDir, this)

    val acks = mutableListOf<ChunkAckBody>()

    override suspend fun sendStart(body: TransferStartBody) {
        receiver.onStart(body)
    }

    override suspend fun sendChunk(
        header: ChunkHeader,
        payload: ByteArray,
    ) {
        receiver.onChunk(header, payload)
    }

    override suspend fun sendAck(body: ChunkAckBody) {
        acks += body
        sender.onAck(body)
    }

    override suspend fun sendEnd(body: TransferEndBody) {
        receiver.onEnd(body)
    }

    override suspend fun sendVerified(body: TransferVerifiedBody) = Unit

    override suspend fun sendVerifyFailed(body: VerifyFailedBody) = Unit
}

/**
 * Records everything without routing, for manual flow-control pacing.
 */
private class RecordingWire : TransferWire {
    var started = false
    val chunks = mutableListOf<Pair<ChunkHeader, ByteArray>>()
    var ended = false
    var endBody: TransferEndBody? = null

    override suspend fun sendStart(body: TransferStartBody) {
        started = true
    }

    override suspend fun sendChunk(
        header: ChunkHeader,
        payload: ByteArray,
    ) {
        chunks += header to payload
    }

    override suspend fun sendAck(body: ChunkAckBody) = Unit

    override suspend fun sendEnd(body: TransferEndBody) {
        ended = true
        endBody = body
    }

    override suspend fun sendVerified(body: TransferVerifiedBody) = Unit

    override suspend fun sendVerifyFailed(body: VerifyFailedBody) = Unit
}

class ChunkStreamTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `full transfer writes the file and acks on the cadence`() =
        runTest {
            val metadata = testMetadata(sizeBytes = 10L * 256, chunkSize = 256)
            val piped = PipedTransfer(metadata, temp.newFolder())

            piped.sender.run()

            assertEquals(TransferPhase.Verifying, piped.receiver.state.phase)
            assertTrue(piped.receiver.partFile.exists())
            assertEquals(
                sourceBytes(10).toList(),
                piped.receiver.partFile
                    .readBytes()
                    .toList(),
            )
            // ACK after chunks 4 and 8, plus the final ACK on TRANSFER_END.
            assertEquals(3, piped.acks.size)
            val last = piped.acks.last()
            assertEquals(listOf(IndexRange(0, 9)), last.received)
            assertEquals(9L, last.highestContiguous)
            assertEquals(10L * 256, piped.sender.bytesSent)
        }

    @Test
    fun `empty file transfers one empty chunk`() =
        runTest {
            val metadata = testMetadata(sizeBytes = 0, chunkSize = 256)
            val piped = PipedTransfer(metadata, temp.newFolder())

            piped.sender.run()

            assertEquals(TransferPhase.Verifying, piped.receiver.state.phase)
            assertEquals(0L, piped.receiver.partFile.length())
            assertEquals(1, piped.acks.size)
            assertEquals(listOf(IndexRange(0, 0)), piped.acks.last().received)
        }

    @Test
    fun `one-byte file transfers one one-byte chunk`() =
        runTest {
            val metadata = testMetadata(sizeBytes = 1, chunkSize = 256)
            val piped = PipedTransfer(metadata, temp.newFolder())

            piped.sender.run()

            assertEquals(TransferPhase.Verifying, piped.receiver.state.phase)
            assertEquals(1L, piped.receiver.partFile.length())
            assertEquals(
                sourceBytes(1).copyOf(1).toList(),
                piped.receiver.partFile
                    .readBytes()
                    .toList(),
            )
            assertEquals(1L, piped.sender.bytesSent)
        }

    @Test
    fun `chunkSize plus one sends a short final chunk`() =
        runTest {
            val metadata = testMetadata(sizeBytes = 257, chunkSize = 256)
            val piped = PipedTransfer(metadata, temp.newFolder())

            piped.sender.run()

            assertEquals(TransferPhase.Verifying, piped.receiver.state.phase)
            assertEquals(257L, piped.receiver.partFile.length())
            assertEquals(
                sourceBytes(2).copyOf(257).toList(),
                piped.receiver.partFile
                    .readBytes()
                    .toList(),
            )
            assertEquals(257L, piped.sender.bytesSent)
        }

    @Test
    fun `duplicate CHUNK_DATA is an idempotent rewrite`() =
        runTest {
            val metadata = testMetadata(sizeBytes = 2L * 256, chunkSize = 256)
            val receiver = ChunkReceiver(metadata, temp.newFolder(), RecordingWire())
            val transferUuid = UUID.fromString(metadata.transferId)

            receiver.onStart(TransferStartBody(metadata.transferId, 256, 2, 0))
            receiver.onChunk(ChunkHeader(transferUuid, 0, 256), chunkPayload(0))
            // Same chunk, same bytes, again (Section 25: set union, no penalty).
            receiver.onChunk(ChunkHeader(transferUuid, 0, 256), chunkPayload(0))
            receiver.onChunk(ChunkHeader(transferUuid, 1, 256), chunkPayload(1))

            val status = receiver.onEnd(TransferEndBody(metadata.transferId, 2L * 256))

            assertEquals(ReceiveEndStatus.READY_TO_VERIFY, status)
            assertEquals(TransferPhase.Verifying, receiver.state.phase)
            assertEquals(
                sourceBytes(2).toList(),
                receiver.partFile.readBytes().toList(),
            )
        }

    @Test
    fun `sender suspends when the window is full and resumes on acks`() =
        runTest(timeout = 15.seconds) {
            val metadata = testMetadata(sizeBytes = 10L * 256, chunkSize = 256)
            val wire = RecordingWire()
            val sender =
                ChunkSender(
                    metadata = metadata,
                    openStream = { ByteArrayInputStream(sourceBytes(10)) },
                    wire = wire,
                    window = 3,
                )

            val job = async { sender.run() }
            runCurrent()

            assertTrue(wire.started)
            assertEquals(3, wire.chunks.size)
            assertTrue(job.isActive)

            sender.onAck(ChunkAckBody(metadata.transferId, listOf(IndexRange(0, 1)), 1))
            runCurrent()
            assertEquals(5, wire.chunks.size)
            assertTrue(job.isActive)

            sender.onAck(ChunkAckBody(metadata.transferId, listOf(IndexRange(0, 4)), 4))
            runCurrent()

            assertEquals(8, wire.chunks.size)
            assertFalse(wire.ended)
            assertTrue(job.isActive)

            sender.onAck(ChunkAckBody(metadata.transferId, listOf(IndexRange(0, 9)), 9))
            runCurrent()

            assertEquals(10, wire.chunks.size)
            assertTrue(wire.ended)
            assertEquals(10L * 256, wire.endBody?.bytesSent)
            job.join()
        }
}
