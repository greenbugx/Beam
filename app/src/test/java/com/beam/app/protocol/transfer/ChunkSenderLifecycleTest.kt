@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.beam.app.protocol.transfer

import com.beam.app.protocol.ChunkHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ChunkSenderLifecycleTest {
    @Test
    fun `cancel wakes paused sender and closes source without more DATA`() =
        runTest {
            val fixture = PausedSenderFixture()
            var failure: Throwable? = null
            val job =
                launch {
                    failure = runCatching { fixture.sender.run() }.exceptionOrNull()
                }
            try {
                runCurrent()
                fixture.assertPaused()
                assertTrue(job.isActive)

                fixture.sender.cancel(TransferError(TransferErrorCode.TRANSFER_CANCELLED, "stop"))
                runCurrent()

                assertTrue("cancel must release the resume waiter", job.isCompleted)
                assertTrue(failure is TransferCancelledException)
                assertEquals(TransferPhase.Cancelled, fixture.sender.state.phase)
                fixture.assertClosedWithoutMoreData()
                assertEquals(1, fixture.wire.cancels.size)
                assertEquals(
                    TransferErrorCode.TRANSFER_CANCELLED,
                    fixture.wire.cancels
                        .single()
                        .code,
                )
                assertTrue(fixture.wire.errors.isEmpty())
            } finally {
                job.cancelAndJoin()
            }
        }

    @Test
    fun `owner cancellation preserves exception and closes paused source without INTERNAL_ERROR`() =
        runTest {
            val fixture = PausedSenderFixture()
            var failure: CancellationException? = null
            val job =
                launch {
                    try {
                        fixture.sender.run()
                    } catch (e: CancellationException) {
                        failure = e
                        throw e
                    }
                }
            try {
                runCurrent()
                fixture.assertPaused()
                assertTrue(job.isActive)

                val cancellation = CancellationException("owner stopped")
                job.cancel(cancellation)
                runCurrent()

                assertTrue(job.isCompleted)
                assertTrue(job.isCancelled)
                assertEquals("owner stopped", failure?.message)
                fixture.assertClosedWithoutMoreData()
                assertTrue(fixture.wire.errors.isEmpty())
                assertTrue(fixture.wire.cancels.isEmpty())
                assertEquals(TransferPhase.Paused, fixture.sender.state.phase)
            } finally {
                job.cancelAndJoin()
            }
        }
}

private class PausedSenderFixture {
    private val chunkSize = FileMetadata.MIN_CHUNK_SIZE_BYTES
    private val metadata =
        FileMetadata(
            transferId = "11111111-2222-3333-4444-555555555555",
            fileId = "0123456789abcdef",
            name = "test.bin",
            mime = "application/octet-stream",
            sizeBytes = 2L * chunkSize,
            sha256 = "a".repeat(64),
            chunkSize = chunkSize,
            chunkCount = 2,
        )
    val source = ClosingSource(ByteArray(2 * chunkSize))
    val wire = LifecycleWire()
    val sender = ChunkSender(metadata, { source }, wire, window = 2)

    init {
        wire.afterChunk = { sender.onLinkLost() }
    }

    fun assertPaused() {
        assertEquals(TransferPhase.Paused, sender.state.phase)
        assertEquals(1, wire.chunks.size)
        assertEquals(chunkSize.toLong(), sender.bytesSent)
        assertEquals(0, source.closeCount)
        assertFalse(wire.ended)
    }

    fun assertClosedWithoutMoreData() {
        assertEquals(1, source.closeCount)
        assertEquals(chunkSize, source.available())
        assertEquals(1, wire.chunks.size)
        assertEquals(chunkSize.toLong(), sender.bytesSent)
        assertFalse(wire.ended)
    }
}

private class ClosingSource(
    bytes: ByteArray,
) : ByteArrayInputStream(bytes) {
    var closeCount = 0

    override fun close() {
        closeCount++
        super.close()
    }
}

private class LifecycleWire : TransferWire {
    val chunks = mutableListOf<Pair<ChunkHeader, ByteArray>>()
    val cancels = mutableListOf<TransferCancelBody>()
    val errors = mutableListOf<TransferErrorBody>()
    var ended = false
    var afterChunk: suspend () -> Unit = {}

    override suspend fun sendStart(body: TransferStartBody) = Unit

    override suspend fun sendChunk(
        header: ChunkHeader,
        payload: ByteArray,
    ) {
        chunks += header to payload.copyOf()
        afterChunk()
    }

    override suspend fun sendAck(body: ChunkAckBody) = Unit

    override suspend fun sendEnd(body: TransferEndBody) {
        ended = true
    }

    override suspend fun sendVerified(body: TransferVerifiedBody) = Unit

    override suspend fun sendVerifyFailed(body: VerifyFailedBody) = Unit

    override suspend fun sendError(body: TransferErrorBody) {
        errors += body
    }

    override suspend fun sendCancel(body: TransferCancelBody) {
        cancels += body
    }
}
