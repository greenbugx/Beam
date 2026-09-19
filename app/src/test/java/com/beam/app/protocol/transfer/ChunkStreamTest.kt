@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.beam.app.protocol.transfer

import com.beam.app.protocol.ChunkHeader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
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
    ioDispatcher: CoroutineDispatcher,
) : TransferWire {
    private val source = sourceBytes(metadata.chunkCount.toInt())

    val sender =
        ChunkSender(
            metadata = metadata,
            openStream = { ByteArrayInputStream(source) },
            wire = this,
            window = window,
            ioDispatcher = ioDispatcher,
        )
    val receiver = ChunkReceiver(metadata, tempDir, this, ioDispatcher = ioDispatcher)

    val acks = mutableListOf<ChunkAckBody>()
    val errors = mutableListOf<TransferErrorBody>()
    val cancels = mutableListOf<TransferCancelBody>()

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

    override suspend fun sendError(body: TransferErrorBody) {
        errors += body
    }

    override suspend fun sendCancel(body: TransferCancelBody) {
        cancels += body
        sender.onPeerCancel(body)
    }
}

/**
 * Records everything without routing, for manual flow-control pacing.
 */
private class RecordingWire : TransferWire {
    var started = false
    val chunks = mutableListOf<Pair<ChunkHeader, ByteArray>>()
    var ended = false
    var endBody: TransferEndBody? = null
    val acks = mutableListOf<ChunkAckBody>()

    override suspend fun sendStart(body: TransferStartBody) {
        started = true
    }

    override suspend fun sendChunk(
        header: ChunkHeader,
        payload: ByteArray,
    ) {
        chunks += header to payload
    }

    override suspend fun sendAck(body: ChunkAckBody) {
        acks += body
    }

    override suspend fun sendEnd(body: TransferEndBody) {
        ended = true
        endBody = body
    }

    override suspend fun sendVerified(body: TransferVerifiedBody) = Unit

    override suspend fun sendVerifyFailed(body: VerifyFailedBody) = Unit

    var errorBody: TransferErrorBody? = null

    override suspend fun sendError(body: TransferErrorBody) {
        errorBody = body
    }

    var cancelBody: TransferCancelBody? = null

    override suspend fun sendCancel(body: TransferCancelBody) {
        cancelBody = body
    }
}

class ChunkStreamTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `ACKs precede checkpoint and byte threshold persists complete range`() =
        runTest {
            val chunkSize = 256 * 1024
            val metadata = testMetadata(65L * chunkSize, chunkSize)
            val dir = temp.newFolder()
            val wire = RecordingWire()
            val receiver = ChunkReceiver(metadata, dir, wire, StandardTestDispatcher(testScheduler), nowNanos = { 0L })
            val sidecar = RangesSidecar(dir, metadata.transferId)
            val payload = ByteArray(chunkSize)
            val id = UUID.fromString(metadata.transferId)
            try {
                receiver.onStart(TransferStartBody(metadata.transferId, chunkSize, 65, 0))
                repeat(63) { receiver.onChunk(ChunkHeader(id, it.toLong(), chunkSize), payload) }
                assertEquals(15, wire.acks.size)
                assertTrue(sidecar.read().isEmpty())
                receiver.onChunk(ChunkHeader(id, 63, chunkSize), payload)
                assertEquals(16, wire.acks.size)
                assertEquals(listOf(0L..63L), sidecar.read())
                receiver.onChunk(ChunkHeader(id, 64, chunkSize), payload)
                assertEquals(listOf(0L..63L), sidecar.read())
                receiver.onEnd(TransferEndBody(metadata.transferId, metadata.sizeBytes))
                assertEquals(listOf(0L..64L), sidecar.read())
            } finally {
                receiver.abandon()
            }
        }

    @Test
    fun `elapsed progress checkpoint and controlled abandonment preserve latest ranges`() =
        runTest {
            val metadata = testMetadata(4L * 256, 256)
            val dir = temp.newFolder()
            var now = 0L
            val receiver =
                ChunkReceiver(metadata, dir, RecordingWire(), StandardTestDispatcher(testScheduler), nowNanos = { now })
            val sidecar = RangesSidecar(dir, metadata.transferId)
            val id = UUID.fromString(metadata.transferId)
            receiver.onStart(TransferStartBody(metadata.transferId, 256, 4, 0))
            try {
                receiver.onChunk(ChunkHeader(id, 0, 256), chunkPayload(0))
                now = 999_999_999L
                receiver.onChunk(ChunkHeader(id, 1, 256), chunkPayload(1))
                assertTrue(sidecar.read().isEmpty())
                now = 1_000_000_000L
                receiver.onChunk(ChunkHeader(id, 2, 256), chunkPayload(2))
                assertEquals(listOf(0L..2L), sidecar.read())
                receiver.onChunk(ChunkHeader(id, 3, 256), chunkPayload(3))
                assertEquals(listOf(0L..2L), sidecar.read())
            } finally {
                receiver.abandon()
            }
            assertEquals(listOf(0L..3L), sidecar.read())
            assertEquals(sourceBytes(4).toList(), receiver.partFile.readBytes().toList())
        }

    @Test
    fun `stale accept-to-start expiry cannot fail a started receiver`() =
        runTest {
            val metadata = testMetadata(sizeBytes = 1, chunkSize = FileMetadata.MIN_CHUNK_SIZE_BYTES)
            val wire = RecordingWire()
            val receiver =
                ChunkReceiver(metadata, temp.newFolder(), wire, ioDispatcher = StandardTestDispatcher(testScheduler))
            try {
                receiver.onStart(TransferStartBody(metadata.transferId, metadata.chunkSize, metadata.chunkCount, 0))
                receiver.onAcceptToStartExpired()

                assertEquals(TransferPhase.Transferring, receiver.state.phase)
                assertTrue(receiver.partFile.exists())
                assertEquals(null, wire.errorBody)
            } finally {
                receiver.abandon()
            }
        }

    @Test
    fun `full transfer writes the file and acks on the cadence`() =
        runTest {
            val metadata = testMetadata(sizeBytes = 10L * 256, chunkSize = 256)
            val piped = PipedTransfer(metadata, temp.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler))

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
            val piped = PipedTransfer(metadata, temp.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler))

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
            val piped = PipedTransfer(metadata, temp.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler))

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
            val piped = PipedTransfer(metadata, temp.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler))

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
            val receiver =
                ChunkReceiver(
                    metadata,
                    temp.newFolder(),
                    RecordingWire(),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
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
                    ioDispatcher = StandardTestDispatcher(testScheduler),
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

    @Test
    fun `sender cancel while window-blocked aborts within one chunk`() =
        runTest(timeout = 15.seconds) {
            val metadata = testMetadata(sizeBytes = 10L * 256, chunkSize = 256)
            val wire = RecordingWire()
            val sender =
                ChunkSender(
                    metadata = metadata,
                    openStream = { ByteArrayInputStream(sourceBytes(10)) },
                    wire = wire,
                    window = 1,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val job = async { runCatching { sender.run() } }
            runCurrent()
            assertEquals(1, wire.chunks.size)
            assertTrue(job.isActive)

            sender.cancel(TransferError(TransferErrorCode.TRANSFER_CANCELLED, "user tapped stop"))
            runCurrent()

            assertEquals(TransferPhase.Cancelled, sender.state.phase)
            assertEquals(TransferErrorCode.TRANSFER_CANCELLED, wire.cancelBody?.code)
            assertEquals(1, wire.chunks.size)
            assertTrue(job.await().exceptionOrNull() is TransferCancelledException)
        }

    @Test
    fun `receiver-side cancel propagates to the sender and deletes the temp file`() =
        runTest(timeout = 15.seconds) {
            val metadata = testMetadata(sizeBytes = 2L * 256, chunkSize = 256)
            val wire = PipedTransfer(metadata, temp.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler))
            val transferUuid = UUID.fromString(metadata.transferId)

            wire.receiver.onStart(TransferStartBody(metadata.transferId, 256, 2, 0))
            wire.receiver.onChunk(ChunkHeader(transferUuid, 0, 256), chunkPayload(0))
            assertTrue(wire.receiver.partFile.exists())

            wire.receiver.cancel(TransferError(TransferErrorCode.TRANSFER_CANCELLED, "changed my mind"))

            assertEquals(TransferPhase.Cancelled, wire.receiver.state.phase)
            assertEquals(TransferPhase.Cancelled, wire.sender.state.phase)
            assertTrue(wire.cancels.isNotEmpty())
            assertFalse(wire.receiver.partFile.exists())
        }

    @Test
    fun `cancel is idempotent on both sides`() =
        runTest(timeout = 15.seconds) {
            val metadata = testMetadata(sizeBytes = 2L * 256, chunkSize = 256)
            val wire = PipedTransfer(metadata, temp.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler))
            val transferUuid = UUID.fromString(metadata.transferId)

            wire.receiver.onStart(TransferStartBody(metadata.transferId, 256, 2, 0))
            wire.receiver.onChunk(ChunkHeader(transferUuid, 0, 256), chunkPayload(0))

            // The initiator's cancel plus the peer's acknowledging cancel.
            wire.receiver.cancel(TransferError(TransferErrorCode.TRANSFER_CANCELLED))
            assertEquals(2, wire.cancels.size)

            wire.receiver.cancel(TransferError(TransferErrorCode.TRANSFER_CANCELLED))
            wire.sender.cancel(TransferError(TransferErrorCode.TRANSFER_CANCELLED))
            assertEquals(2, wire.cancels.size)
        }
}

class ChunkTimeoutTest {
    private val policy =
        TimeoutPolicy(
            transferInactivityMillis = 100,
            offerMillis = 100,
            acceptToStartMillis = 100,
            ackMillis = 100,
            verificationMillis = 100,
            maxConsecutiveInactivity = 1,
        )

    @Test
    fun `inactivity timeout aborts a window-blocked sender with TRANSFER_TIMEOUT`() =
        runTest(timeout = 15.seconds) {
            val metadata = testMetadata(sizeBytes = 10L * 256, chunkSize = 256)
            val wire = RecordingWire()
            val sender =
                ChunkSender(
                    metadata = metadata,
                    openStream = { ByteArrayInputStream(sourceBytes(10)) },
                    wire = wire,
                    window = 1,
                    timeouts = TransferTimeouts(policy),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val job = async { runCatching { sender.run() } }
            advanceTimeBy(100.milliseconds)
            runCurrent()

            assertTrue(job.isCompleted)
            assertEquals(TransferPhase.Failed, sender.state.phase)
            assertEquals(TransferErrorCode.TRANSFER_TIMEOUT, wire.errorBody?.code)
            assertTrue(job.await().exceptionOrNull() is TransferTimeoutException)
        }

    @Test
    fun `an ack before the timer expires prevents the timeout`() =
        runTest(timeout = 15.seconds) {
            val metadata = testMetadata(sizeBytes = 10L * 256, chunkSize = 256)
            val wire = RecordingWire()
            val sender =
                ChunkSender(
                    metadata = metadata,
                    openStream = { ByteArrayInputStream(sourceBytes(10)) },
                    wire = wire,
                    window = 1,
                    timeouts = TransferTimeouts(policy),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val job = async { sender.run() }
            advanceTimeBy(99.milliseconds)
            runCurrent()
            assertTrue(job.isActive)

            sender.onAck(ChunkAckBody(metadata.transferId, listOf(IndexRange(0, 0)), 0))
            advanceTimeBy(99.milliseconds)
            runCurrent()
            assertTrue(job.isActive)
            assertNull(wire.errorBody)
            job.cancel()
        }

    @Test
    fun `first inactivity expiry pauses the transfer and an ack resumes it`() =
        runTest(timeout = 15.seconds) {
            val pausePolicy =
                TimeoutPolicy(
                    transferInactivityMillis = 100,
                    offerMillis = 100,
                    acceptToStartMillis = 100,
                    ackMillis = 100,
                    verificationMillis = 100,
                    maxConsecutiveInactivity = 3,
                )
            val metadata = testMetadata(sizeBytes = 10L * 256, chunkSize = 256)
            val wire = RecordingWire()
            val sender =
                ChunkSender(
                    metadata = metadata,
                    openStream = { ByteArrayInputStream(sourceBytes(10)) },
                    wire = wire,
                    window = 1,
                    timeouts = TransferTimeouts(pausePolicy),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val job = async { runCatching { sender.run() } }
            runCurrent()
            assertEquals(1, wire.chunks.size)

            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Paused, sender.state.phase)
            assertNull(wire.errorBody)
            assertTrue(job.isActive)

            // ACK progress restores the link and streaming continues.
            sender.onAck(ChunkAckBody(metadata.transferId, listOf(IndexRange(0, 0)), 0))
            runCurrent()
            assertEquals(TransferPhase.Transferring, sender.state.phase)
            assertEquals(2, wire.chunks.size)

            // Drain the rest to completion.
            var ackedUpTo = 0L
            while (!job.isCompleted && ackedUpTo < 9L) {
                ackedUpTo++
                sender.onAck(ChunkAckBody(metadata.transferId, listOf(IndexRange(0, ackedUpTo)), ackedUpTo))
                runCurrent()
            }
            job.join()
            assertEquals(10, wire.chunks.size)
            assertTrue(wire.ended)
        }

    @Test
    fun `three consecutive inactivity expiries fail the transfer`() =
        runTest(timeout = 15.seconds) {
            val pausePolicy =
                TimeoutPolicy(
                    transferInactivityMillis = 100,
                    offerMillis = 100,
                    acceptToStartMillis = 100,
                    ackMillis = 100,
                    verificationMillis = 100,
                    maxConsecutiveInactivity = 3,
                )
            val metadata = testMetadata(sizeBytes = 10L * 256, chunkSize = 256)
            val wire = RecordingWire()
            val sender =
                ChunkSender(
                    metadata = metadata,
                    openStream = { ByteArrayInputStream(sourceBytes(10)) },
                    wire = wire,
                    window = 1,
                    timeouts = TransferTimeouts(pausePolicy),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val job = async { runCatching { sender.run() } }

            repeat(3) {
                advanceTimeBy(100.milliseconds)
                runCurrent()
            }

            assertTrue(job.isCompleted)
            assertEquals(TransferPhase.Failed, sender.state.phase)
            assertEquals(TransferErrorCode.TRANSFER_TIMEOUT, wire.errorBody?.code)
            assertTrue(job.await().exceptionOrNull() is TransferTimeoutException)
        }
}

private fun newTempDir(): File =
    File.createTempFile("beam", "test").apply {
        delete()
        mkdirs()
    }

class DuplicateHandlingTest {
    @Test
    fun `duplicate TRANSFER_START with same geometry is ignored`() =
        runTest(timeout = 15.seconds) {
            val metadata = testMetadata(sizeBytes = 2L * 256, chunkSize = 256)
            val wire = PipedTransfer(metadata, newTempDir(), ioDispatcher = StandardTestDispatcher(testScheduler))
            val start = TransferStartBody(metadata.transferId, 256, 2, 0)

            wire.receiver.onStart(start)
            val phaseAfterFirst = wire.receiver.state.phase
            wire.receiver.onStart(start)

            assertEquals(phaseAfterFirst, wire.receiver.state.phase)
        }

    @Test
    fun `duplicate TRANSFER_START with different geometry is a protocol error`() =
        runTest(timeout = 15.seconds) {
            val metadata = testMetadata(sizeBytes = 2L * 256, chunkSize = 256)
            val wire = PipedTransfer(metadata, newTempDir(), ioDispatcher = StandardTestDispatcher(testScheduler))

            wire.receiver.onStart(TransferStartBody(metadata.transferId, 256, 2, 0))
            try {
                wire.receiver.onStart(TransferStartBody(metadata.transferId, 512, 1, 0))
                fail("expected TransferProtocolException for geometry change")
            } catch (expected: TransferProtocolException) {
                // duplicate with different geometry is invalid.
            }
        }

    @Test
    fun `duplicate TRANSFER_END after completion is idempotent`() =
        runTest(timeout = 15.seconds) {
            val metadata = testMetadata(sizeBytes = 2L * 256, chunkSize = 256)
            val wire = PipedTransfer(metadata, newTempDir(), ioDispatcher = StandardTestDispatcher(testScheduler))
            val transferUuid = UUID.fromString(metadata.transferId)

            wire.receiver.onStart(TransferStartBody(metadata.transferId, 256, 2, 0))
            wire.receiver.onChunk(ChunkHeader(transferUuid, 0, 256), chunkPayload(0))
            wire.receiver.onChunk(ChunkHeader(transferUuid, 1, 256), chunkPayload(1))

            val end = TransferEndBody(metadata.transferId, 512)
            assertEquals(ReceiveEndStatus.READY_TO_VERIFY, wire.receiver.onEnd(end))
            assertEquals(ReceiveEndStatus.READY_TO_VERIFY, wire.receiver.onEnd(end))
        }

    @Test
    fun `duplicate CHUNK_DATA does not double-count ranges`() =
        runTest(timeout = 15.seconds) {
            val metadata = testMetadata(sizeBytes = 2L * 256, chunkSize = 256)
            val wire = PipedTransfer(metadata, newTempDir(), ioDispatcher = StandardTestDispatcher(testScheduler))
            val transferUuid = UUID.fromString(metadata.transferId)

            wire.receiver.onStart(TransferStartBody(metadata.transferId, 256, 2, 0))
            wire.receiver.onChunk(ChunkHeader(transferUuid, 0, 256), chunkPayload(0))
            wire.receiver.onChunk(ChunkHeader(transferUuid, 0, 256), chunkPayload(0)) // duplicate

            assertEquals(listOf(0L..0L), wire.receiver.receivedRanges)

            wire.receiver.onChunk(ChunkHeader(transferUuid, 1, 256), chunkPayload(1))
            assertEquals(
                ReceiveEndStatus.READY_TO_VERIFY,
                wire.receiver.onEnd(TransferEndBody(metadata.transferId, 512)),
            )
        }
}

class OfferDecisionCacheTest {
    @Test
    fun `recorded decision is readable afterwards`() {
        val cache = OfferDecisionCache()
        assertNull(cache.decisionFor("offer-2"))
        cache.record("offer-2", OfferDecisionCache.Decision.REJECTED)
        assertEquals(OfferDecisionCache.Decision.REJECTED, cache.decisionFor("offer-2"))
    }

    @Test
    fun `record keeps the real rejection reason`() {
        val cache = OfferDecisionCache()
        cache.record("offer-3", OfferDecisionCache.Decision.REJECTED, RejectReason.BUSY)
        assertEquals(RejectReason.BUSY, cache.rejectionReasonFor("offer-3"))
        cache.record("offer-4", OfferDecisionCache.Decision.ACCEPTED)
        assertNull(cache.rejectionReasonFor("offer-4"))
    }
}
