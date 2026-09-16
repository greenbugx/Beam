@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.beam.app.protocol.manager

import com.beam.app.protocol.Frame
import com.beam.app.protocol.FrameType
import com.beam.app.protocol.MessageEnvelope
import com.beam.app.protocol.session.FakeTransport
import com.beam.app.protocol.session.LinkSession
import com.beam.app.protocol.session.LinkState
import com.beam.app.protocol.session.LocalIdentity
import com.beam.app.protocol.session.SessionCapabilities
import com.beam.app.protocol.session.SessionRole
import com.beam.app.protocol.transfer.CompletionStatus
import com.beam.app.protocol.transfer.FileMetadata
import com.beam.app.protocol.transfer.RejectReason
import com.beam.app.protocol.transfer.TimeoutPolicy
import com.beam.app.protocol.transfer.TransferError
import com.beam.app.protocol.transfer.TransferErrorCode
import com.beam.app.protocol.transfer.TransferMessageTypes
import com.beam.app.protocol.transfer.TransferPhase
import com.beam.app.protocol.transfer.TransferProtocolException
import com.beam.app.protocol.transfer.TransferStartBody
import com.beam.app.protocol.transfer.TransferTimeouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SESSION_ID = "sess-e2e"
private const val BEAM_CODE = "BEAM01"

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

private fun metadataFor(
    name: String,
    bytes: ByteArray,
    chunkSize: Int,
    sha256: String = sha256(bytes),
): FileMetadata =
    FileMetadata(
        transferId = UUID.randomUUID().toString(),
        fileId = "0123456789abcdef",
        name = name,
        mime = "text/plain",
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256,
        chunkSize = chunkSize,
        chunkCount = FileMetadata.derivedChunkCount(bytes.size.toLong(), chunkSize),
    )

private class Peer(
    name: String,
    scope: TestScope,
    val dir: File,
    policy: TimeoutPolicy = TimeoutPolicy(),
    val transport: FakeTransport = FakeTransport(),
) {
    val linkId = "dev-$name"
    val session =
        LinkSession(
            transport = transport,
            local = LocalIdentity(linkId, "Device $name", SessionRole.PEER, SESSION_ID, BEAM_CODE),
            scope = scope.backgroundScope,
            supportedVersions = listOf(MessageEnvelope.PROTOCOL_VERSION),
            advertisedCapabilities = setOf(SessionCapabilities.CHUNKING),
        )
    val manager = TransferManager(dir, scope.backgroundScope, TransferTimeouts(policy))

    var delivered = 0

    init {
        manager.attach(session, linkId, "Device $name")
    }

    fun start() = session.start()

    fun phaseOf(transferId: String): TransferPhase? = manager.snapshot(transferId)?.phase

    fun errorCodeOf(transferId: String): TransferErrorCode? = manager.snapshot(transferId)?.error?.code

    fun snapshot(transferId: String): TransferSnapshot? = manager.snapshot(transferId)

    fun transferIds(): List<String> = manager.transfers.value.map { it.transferId }

    fun controlTypes(): List<String> =
        transport.sent
            .filter { it.type == FrameType.CTRL }
            .map { MessageEnvelope.decode(it.payload.toString(Charsets.UTF_8)).type }

    fun transferMessageTypes(): List<String> = controlTypes().filter { it in TransferMessageTypes.ALL }

    fun dataFrameCount(): Int = transport.sent.count { it.type == FrameType.DATA }

    fun acceptFrame(): Frame =
        transport.sent.first { frame ->
            frame.type == FrameType.CTRL &&
                MessageEnvelope.decode(frame.payload.toString(Charsets.UTF_8)).type == TransferMessageTypes.ACCEPT
        }

    fun partFile(transferId: String): File = File(dir, "$transferId.part")

    fun sidecarFile(transferId: String): File = File(dir, "$transferId.ranges")

    fun destination(name: String): File = File(dir, "received/$name")
}

private suspend fun TestScope.connect(
    a: Peer,
    b: Peer,
) {
    a.start()
    b.start()
    runCurrent()
    pump(a, b)
}

private suspend fun TestScope.pump(
    a: Peer,
    b: Peer,
    maxRounds: Int = 200,
) {
    repeat(maxRounds) {
        var moved = false
        while (a.delivered < a.transport.sent.size) {
            b.transport.receiveFrame(a.transport.sent[a.delivered])
            a.delivered++
            moved = true
        }
        while (b.delivered < b.transport.sent.size) {
            a.transport.receiveFrame(b.transport.sent[b.delivered])
            b.delivered++
            moved = true
        }
        runCurrent()
        if (!moved) return
    }
}

class ProtocolSuiteTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val textBytes = "Hello Beam! The protocol suite moves this text file.\n".repeat(20).toByteArray()

    private fun source(bytes: ByteArray): () -> InputStream = { ByteArrayInputStream(bytes) }

    @Test
    fun `text file flows offer accept start chunks verify publish`() =
        runTest(timeout = 30.seconds) {
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, temp.newFolder())
            connect(sender, receiver)
            assertEquals(LinkState.Active, sender.session.state.value)

            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64 * 1024)
            val transferId = sender.manager.offer(metadata, source(textBytes), sender.linkId)
            pump(sender, receiver, maxRounds = 1)

            // The offer reached the UI as an OFFERED transfer, awaiting the user.
            assertEquals(TransferPhase.Offered, receiver.phaseOf(transferId))
            assertEquals(TransferDirection.RECEIVING, receiver.snapshot(transferId)?.direction)

            receiver.manager.accept(transferId, receiver.destination(metadata.name))
            pump(sender, receiver)

            assertEquals(TransferPhase.Completed, sender.phaseOf(transferId))
            assertEquals(TransferPhase.Completed, receiver.phaseOf(transferId))
            assertEquals(TransferDirection.SENDING, sender.snapshot(transferId)?.direction)
            assertEquals(
                textBytes.toList(),
                receiver.destination(metadata.name).readBytes().toList(),
            )

            assertFalse(receiver.partFile(transferId).exists())
            assertFalse(receiver.sidecarFile(transferId).exists())

            val snapshot = receiver.snapshot(transferId)
            assertEquals(textBytes.size.toLong(), snapshot?.bytesTransferred)
            assertEquals(1f, snapshot?.progressFraction)
            assertNull(snapshot?.etaSeconds)

            // One offer, one ACCEPT, one START, one VERIFIED, one END.
            assertEquals(
                listOf(TransferMessageTypes.OFFER, TransferMessageTypes.START, TransferMessageTypes.END),
                sender.transferMessageTypes(),
            )
            assertEquals(TransferMessageTypes.ACCEPT, receiver.transferMessageTypes().first())
            assertEquals(1, receiver.transferMessageTypes().count { it == TransferMessageTypes.VERIFIED })
            assertEquals(metadata.chunkCount.toInt(), sender.dataFrameCount())

            // A finished transfer never closes the link.
            assertEquals(LinkState.Active, sender.session.state.value)
            assertEquals(LinkState.Active, receiver.session.state.value)
        }

    @Test
    fun `every reject reason ends the offer without a single data frame`() =
        runTest(timeout = 30.seconds) {
            for (reason in RejectReason.entries) {
                val sender = Peer("a", this, temp.newFolder())
                val receiver = Peer("b", this, temp.newFolder())
                connect(sender, receiver)

                val metadata = metadataFor("note.txt", textBytes, chunkSize = 64 * 1024)
                val transferId = sender.manager.offer(metadata, source(textBytes), sender.linkId)
                pump(sender, receiver, maxRounds = 1)
                receiver.manager.reject(transferId, reason)
                pump(sender, receiver)

                assertEquals(reason.name, TransferPhase.Rejected, sender.phaseOf(transferId))
                assertEquals(reason.name, TransferPhase.Rejected, receiver.phaseOf(transferId))
                assertTrue(reason.name, sender.dataFrameCount() == 0)
                assertEquals(reason.name, listOf(TransferMessageTypes.OFFER), sender.transferMessageTypes())
                assertEquals(
                    reason.name,
                    listOf(TransferMessageTypes.REJECT),
                    receiver.transferMessageTypes(),
                )
                // Nothing was allocated for a rejected transfer.
                assertFalse(receiver.partFile(transferId).exists())
                assertEquals(LinkState.Active, sender.session.state.value)
            }
        }

    @Test
    fun `duplicate FILE_OFFER re-sends the cached decision without re-prompting`() =
        runTest(timeout = 30.seconds) {
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, temp.newFolder())
            connect(sender, receiver)

            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64 * 1024)
            val transferId = sender.manager.offer(metadata, source(textBytes), sender.linkId)
            pump(sender, receiver, maxRounds = 1)
            receiver.manager.accept(transferId, receiver.destination(metadata.name))
            pump(sender, receiver, maxRounds = 3)

            LinkTransferWire(sender.session, transferId).sendOffer(metadata)
            pump(sender, receiver)

            assertEquals(2, receiver.transferMessageTypes().count { it == TransferMessageTypes.ACCEPT })
            assertEquals(1, sender.transferMessageTypes().count { it == TransferMessageTypes.START })
            assertEquals(1, receiver.transferIds().count { it == transferId })
            assertEquals(metadata.chunkCount.toInt(), sender.dataFrameCount())
            assertEquals(TransferPhase.Completed, sender.phaseOf(transferId))
            assertEquals(textBytes.toList(), receiver.destination(metadata.name).readBytes().toList())
        }

    @Test
    fun `hash mismatch fails the transfer, deletes the temp and keeps the link alive`() =
        runTest(timeout = 30.seconds) {
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, temp.newFolder())
            connect(sender, receiver)

            val different = textBytes.copyOf().also { it[0] = (it[0].toInt() + 1).toByte() }
            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64 * 1024, sha256 = sha256(different))
            val transferId = sender.manager.offer(metadata, source(textBytes), sender.linkId)
            pump(sender, receiver, maxRounds = 1)
            receiver.manager.accept(transferId, receiver.destination(metadata.name))
            pump(sender, receiver)

            assertEquals(TransferPhase.Failed, receiver.phaseOf(transferId))
            assertEquals(TransferPhase.Failed, sender.phaseOf(transferId))
            assertEquals(TransferErrorCode.HASH_MISMATCH, receiver.errorCodeOf(transferId))
            assertEquals(1, receiver.transferMessageTypes().count { it == TransferMessageTypes.VERIFY_FAILED })
            assertFalse(receiver.partFile(transferId).exists())
            assertFalse(receiver.sidecarFile(transferId).exists())
            assertFalse(receiver.destination(metadata.name).exists())

            assertEquals(LinkState.Active, sender.session.state.value)
            assertEquals(LinkState.Active, receiver.session.state.value)
        }

    @Test
    fun `cancel mid-stream stops both sides, deletes the temp and is idempotent`() =
        runTest(timeout = 30.seconds) {
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, temp.newFolder())
            connect(sender, receiver)

            val payload = ByteArray(64 * 64 * 1024) { (it % 251).toByte() }
            val metadata = metadataFor("note.txt", payload, chunkSize = 64 * 1024)
            val transferId = sender.manager.offer(metadata, source(payload), sender.linkId)
            pump(sender, receiver, maxRounds = 1)
            receiver.manager.accept(transferId, receiver.destination(metadata.name))
            pump(sender, receiver, maxRounds = 4)
            assertEquals(TransferPhase.Transferring, receiver.phaseOf(transferId))
            assertTrue(receiver.partFile(transferId).exists())
            val chunksBeforeCancel = sender.dataFrameCount()

            receiver.manager.cancel(transferId, TransferError(TransferErrorCode.TRANSFER_CANCELLED, "user tapped stop"))
            pump(sender, receiver)

            assertEquals(TransferPhase.Cancelled, receiver.phaseOf(transferId))
            assertEquals(TransferPhase.Cancelled, sender.phaseOf(transferId))
            assertFalse(receiver.partFile(transferId).exists())
            assertFalse(receiver.sidecarFile(transferId).exists())
            assertFalse(receiver.destination(metadata.name).exists())
            assertEquals(2, cancelCount(sender, receiver))
            assertTrue((sender.dataFrameCount() - chunksBeforeCancel) <= 1)

            // The second cancel on either side is a no-op.
            val afterCancel = sender.dataFrameCount()
            receiver.manager.cancel(transferId)
            sender.manager.cancel(transferId)
            pump(sender, receiver)
            assertEquals(2, cancelCount(sender, receiver))
            assertEquals(afterCancel, sender.dataFrameCount())
            assertEquals(LinkState.Active, sender.session.state.value)
        }

    @Test
    fun `withdrawing an offer before accept cancels it on both sides`() =
        runTest(timeout = 30.seconds) {
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, temp.newFolder())
            connect(sender, receiver)

            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64 * 1024)
            val transferId = sender.manager.offer(metadata, source(textBytes), sender.linkId)
            pump(sender, receiver, maxRounds = 1)
            assertEquals(TransferPhase.Offered, receiver.phaseOf(transferId))

            sender.manager.cancel(transferId)
            pump(sender, receiver)

            assertEquals(TransferPhase.Cancelled, sender.phaseOf(transferId))
            assertEquals(TransferPhase.Cancelled, receiver.phaseOf(transferId))
            assertTrue(sender.dataFrameCount() == 0)
            assertEquals(listOf(TransferMessageTypes.OFFER, TransferMessageTypes.CANCEL), sender.transferMessageTypes())
            assertFalse(receiver.partFile(transferId).exists())
            assertFalse(receiver.destination(metadata.name).exists())
            assertEquals(LinkState.Active, receiver.session.state.value)
        }

    @Test
    fun `link loss pauses both sides then fails after the reconnect window`() =
        runTest(timeout = 30.seconds) {
            val policy = TimeoutPolicy(transferInactivityMillis = 60_000, reconnectWindowMillis = 5_000)
            val sender = Peer("a", this, temp.newFolder(), policy)
            val receiver = Peer("b", this, temp.newFolder(), policy)
            connect(sender, receiver)

            val payload = ByteArray(64 * 64 * 1024) { (it % 251).toByte() }
            val metadata = metadataFor("note.txt", payload, chunkSize = 64 * 1024)
            val transferId = sender.manager.offer(metadata, source(payload), sender.linkId)
            pump(sender, receiver, maxRounds = 1)
            receiver.manager.accept(transferId, receiver.destination(metadata.name))
            pump(sender, receiver, maxRounds = 4)
            assertEquals(TransferPhase.Transferring, receiver.phaseOf(transferId))
            assertTrue(receiver.partFile(transferId).exists())
            val chunksBeforeLoss = sender.dataFrameCount()

            sender.transport.receiveLinkLost()
            receiver.transport.receiveLinkLost()
            runCurrent()

            assertEquals(TransferPhase.Paused, sender.phaseOf(transferId))
            assertEquals(TransferPhase.Paused, receiver.phaseOf(transferId))
            assertFalse((sender.session.state.value as LinkState.Closed).graceful)
            assertTrue(receiver.partFile(transferId).exists())
            assertEquals(chunksBeforeLoss, sender.dataFrameCount())

            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Paused, receiver.phaseOf(transferId))
            assertTrue(receiver.partFile(transferId).exists())

            advanceTimeBy(5_000.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Failed, sender.phaseOf(transferId))
            assertEquals(TransferPhase.Failed, receiver.phaseOf(transferId))
            assertEquals(TransferErrorCode.CONNECTION_LOST, receiver.errorCodeOf(transferId))
            assertFalse(receiver.partFile(transferId).exists())
            assertFalse(receiver.sidecarFile(transferId).exists())
        }

    @Test
    fun `a paused transfer can still be cancelled`() =
        runTest(timeout = 30.seconds) {
            val policy = TimeoutPolicy(transferInactivityMillis = 60_000, reconnectWindowMillis = 60_000)
            val sender = Peer("a", this, temp.newFolder(), policy)
            val receiver = Peer("b", this, temp.newFolder(), policy)
            connect(sender, receiver)

            val payload = ByteArray(64 * 64 * 1024) { (it % 251).toByte() }
            val metadata = metadataFor("note.txt", payload, chunkSize = 64 * 1024)
            val transferId = sender.manager.offer(metadata, source(payload), sender.linkId)
            pump(sender, receiver, maxRounds = 1)
            receiver.manager.accept(transferId, receiver.destination(metadata.name))
            pump(sender, receiver, maxRounds = 4)

            sender.transport.receiveLinkLost()
            runCurrent()
            assertEquals(TransferPhase.Paused, sender.phaseOf(transferId))

            sender.manager.cancel(transferId, TransferError(TransferErrorCode.TRANSFER_CANCELLED, "user gave up"))
            pump(sender, receiver)

            assertEquals(TransferPhase.Cancelled, sender.phaseOf(transferId))
        }

    @Test
    fun `a replayed envelope with the same mid is ignored`() =
        runTest(timeout = 30.seconds) {
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, temp.newFolder())
            connect(sender, receiver)

            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64 * 1024)
            val transferId = sender.manager.offer(metadata, source(textBytes), sender.linkId)
            pump(sender, receiver, maxRounds = 2)
            receiver.manager.accept(transferId, receiver.destination(metadata.name))

            val accept = receiver.acceptFrame()
            sender.transport.receiveFrame(accept)
            runCurrent()
            sender.transport.receiveFrame(accept)
            runCurrent()

            assertEquals(1, sender.transferMessageTypes().count { it == TransferMessageTypes.START })

            pump(sender, receiver)
            assertEquals(TransferPhase.Completed, sender.phaseOf(transferId))
            assertEquals(metadata.chunkCount.toInt(), sender.dataFrameCount())
        }

    @Test
    fun `the ranges sidecar mirrors acked progress and dies with the transfer`() =
        runTest(timeout = 30.seconds) {
            val receiverDir = temp.newFolder()
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, receiverDir)
            connect(sender, receiver)

            val payload = ByteArray(64 * 64 * 1024) { (it % 251).toByte() }
            val metadata = metadataFor("bulk.bin", payload, chunkSize = 64 * 1024)
            val transferId = sender.manager.offer(metadata, source(payload), sender.linkId)
            pump(sender, receiver, maxRounds = 1)
            receiver.manager.accept(transferId, receiver.destination(metadata.name))
            pump(sender, receiver, maxRounds = 3)

            val sidecar = receiver.sidecarFile(transferId)
            assertTrue(sidecar.exists())
            assertEquals(listOf("0-7"), sidecar.readLines().filter { it.isNotBlank() })

            pump(sender, receiver)

            assertEquals(TransferPhase.Completed, receiver.phaseOf(transferId))
            assertEquals(payload.toList(), receiver.destination(metadata.name).readBytes().toList())
            assertFalse(sidecar.exists())
            assertFalse(receiver.partFile(transferId).exists())
        }

    @Test
    fun `an accepted offer that never starts fails on the accept-to-start timer`() =
        runTest(timeout = 30.seconds) {
            val policy = TimeoutPolicy(acceptToStartMillis = 1_000)
            val sender = Peer("a", this, temp.newFolder(), policy)
            val receiver = Peer("b", this, temp.newFolder(), policy)
            connect(sender, receiver)

            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64 * 1024)
            val transferId = sender.manager.offer(metadata, source(textBytes), sender.linkId)
            pump(sender, receiver, maxRounds = 1)

            receiver.manager.accept(transferId, receiver.destination(metadata.name))
            advanceTimeBy(1_000.milliseconds)
            runCurrent()

            assertEquals(TransferPhase.Failed, receiver.phaseOf(transferId))
            assertEquals(TransferErrorCode.TRANSFER_TIMEOUT, receiver.errorCodeOf(transferId))
            assertEquals(1, receiver.transferMessageTypes().count { it == TransferMessageTypes.ERROR })
            assertFalse(receiver.partFile(transferId).exists())

            // The sender is told rather than left waiting, and the link stays up.
            pump(sender, receiver)
            assertEquals(TransferPhase.Failed, sender.phaseOf(transferId))
            assertEquals(TransferErrorCode.TRANSFER_TIMEOUT, sender.errorCodeOf(transferId))
            assertEquals(LinkState.Active, receiver.session.state.value)
        }

    @Test
    fun `a second concurrent offer on one link is refused locally`() =
        runTest(timeout = 30.seconds) {
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, temp.newFolder())
            connect(sender, receiver)

            val first = metadataFor("one.txt", textBytes, chunkSize = 64 * 1024)
            sender.manager.offer(first, source(textBytes), sender.linkId)

            val second = metadataFor("two.txt", textBytes, chunkSize = 64 * 1024)
            try {
                sender.manager.offer(second, source(textBytes), sender.linkId)
                fail("expected one active transfer per link")
            } catch (expected: TransferProtocolException) {
                // Refused before anything reached the wire.
            }
            assertEquals(1, sender.transferMessageTypes().count { it == TransferMessageTypes.OFFER })
        }

    @Test
    fun `queued accepts with distinct mids start the sender only once`() =
        runTest(timeout = 30.seconds) {
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, temp.newFolder())
            connect(sender, receiver)
            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64 * 1024)
            val transferId = sender.manager.offer(metadata, source(textBytes), sender.linkId)
            pump(sender, receiver, maxRounds = 1)
            receiver.manager.accept(transferId, receiver.destination(metadata.name))
            LinkTransferWire(receiver.session, transferId).sendAccept()
            pump(sender, receiver)

            assertEquals(1, sender.transferMessageTypes().count { it == TransferMessageTypes.START })
            assertEquals(TransferPhase.Completed, sender.phaseOf(transferId))
            assertEquals(TransferPhase.Completed, receiver.phaseOf(transferId))
        }

    @Test
    fun `cancelling an incoming pending offer frees the link and stays terminal`() =
        runTest(timeout = 30.seconds) {
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, temp.newFolder())
            connect(sender, receiver)
            val metadata = metadataFor("one.txt", textBytes, chunkSize = 64 * 1024)
            val id = sender.manager.offer(metadata, source(textBytes), sender.linkId)
            pump(sender, receiver)
            receiver.manager.cancel(id)
            pump(sender, receiver)
            assertEquals(TransferPhase.Cancelled, receiver.phaseOf(id))
            assertEquals(TransferPhase.Cancelled, sender.phaseOf(id))
            val count = cancelCount(sender, receiver)
            receiver.manager.cancel(id)
            advanceTimeBy(60.seconds + 1.milliseconds)
            runCurrent()
            assertEquals(count, cancelCount(sender, receiver))
            assertEquals(TransferPhase.Cancelled, receiver.phaseOf(id))
            val next = metadataFor("two.txt", textBytes, chunkSize = 64 * 1024)
            sender.manager.offer(next, source(textBytes), sender.linkId)
            pump(sender, receiver)
            assertEquals(TransferPhase.Offered, receiver.phaseOf(next.transferId))
            assertFalse(receiver.partFile(id).exists())
        }

    @Test
    fun `detaching an active receiver deletes temps and stops routing`() =
        runTest(timeout = 30.seconds) {
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, temp.newFolder())
            connect(sender, receiver)
            val bytes = ByteArray(64 * 64 * 1024)
            val metadata = metadataFor("bulk.bin", bytes, chunkSize = 64 * 1024)
            val id = sender.manager.offer(metadata, source(bytes), sender.linkId)
            pump(sender, receiver)
            receiver.manager.accept(id, receiver.destination(metadata.name))
            pump(sender, receiver, maxRounds = 3)
            assertTrue(receiver.partFile(id).exists())
            receiver.manager.detach(receiver.linkId)
            assertFalse(receiver.partFile(id).exists())
            assertFalse(receiver.sidecarFile(id).exists())
            assertEquals(TransferPhase.Cancelled, receiver.phaseOf(id))
            val sent = receiver.transport.sent.size
            pump(sender, receiver)
            advanceTimeBy(120.seconds + 1.milliseconds)
            runCurrent()
            assertEquals(sent, receiver.transport.sent.size)
            assertEquals(TransferPhase.Cancelled, receiver.phaseOf(id))
        }

    @Test
    fun `closing manager joins sender and releases source without cancelling session`() =
        runTest(timeout = 30.seconds) {
            val sender = Peer("a", this, temp.newFolder())
            val receiver = Peer("b", this, temp.newFolder())
            connect(sender, receiver)
            val bytes = ByteArray(64 * 64 * 1024)
            var closes = 0
            val metadata = metadataFor("bulk.bin", bytes, chunkSize = 64 * 1024)
            val id =
                sender.manager.offer(
                    metadata,
                    FileSource {
                        object : ByteArrayInputStream(bytes) {
                            override fun close() {
                                closes++
                                super.close()
                            }
                        }
                    },
                    sender.linkId,
                )
            pump(sender, receiver)
            receiver.manager.accept(id, receiver.destination(metadata.name))
            pump(sender, receiver, maxRounds = 3)
            assertEquals(0, closes)
            sender.manager.close()
            assertEquals(1, closes)
            assertEquals(TransferPhase.Cancelled, sender.phaseOf(id))
            assertEquals(LinkState.Active, sender.session.state.value)
            sender.manager.close()
            assertEquals(1, closes)
        }

    @Test
    fun `manager close joins ticker but not the caller scope`() =
        runTest {
            val parent = kotlinx.coroutines.Job()
            val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + parent)
            try {
                val manager = TransferManager(temp.newFolder(), scope)
                runCurrent()
                assertTrue(parent.children.any())
                manager.close()
                assertFalse(parent.children.any())
                assertTrue(parent.isActive)
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `START during suspended ACCEPT preserves watchdog and reconnect timers`() =
        runTest {
            for (loseLink in listOf(false, true)) {
                val releaseAccept = CompletableDeferred<Unit>()
                val acceptSent = CompletableDeferred<Unit>()
                val transport =
                    FakeTransport { frame ->
                        val type =
                            if (frame.type == FrameType.CTRL) {
                                MessageEnvelope.decode(frame.payload.toString(Charsets.UTF_8)).type
                            } else {
                                null
                            }
                        if (type == TransferMessageTypes.ACCEPT) {
                            acceptSent.complete(Unit)
                            releaseAccept.await()
                        }
                    }
                val policy =
                    TimeoutPolicy(
                        acceptToStartMillis = 100,
                        transferInactivityMillis = 1_000,
                        maxConsecutiveInactivity = 2,
                        reconnectWindowMillis = 3_000,
                    )
                val sender = Peer("a", this, temp.newFolder(), policy)
                val receiver = Peer("b", this, temp.newFolder(), policy, transport)
                connect(sender, receiver)
                val metadata = metadataFor("race.bin", ByteArray(64 * 1024), chunkSize = 64 * 1024)
                val id = metadata.transferId
                val wire = LinkTransferWire(sender.session, id)
                wire.sendOffer(metadata)
                pump(sender, receiver)
                val accepting = launch { receiver.manager.accept(id, receiver.destination(metadata.name)) }
                runCurrent()
                assertTrue(acceptSent.isCompleted)
                assertFalse(accepting.isCompleted)
                pump(sender, receiver)
                wire.sendStart(
                    TransferStartBody(id, metadata.chunkSize, metadata.chunkCount, 0),
                )
                pump(sender, receiver)
                assertEquals(TransferPhase.Transferring, receiver.phaseOf(id))
                assertTrue(receiver.partFile(id).exists())
                if (loseLink) {
                    receiver.transport.receiveLinkLost()
                    runCurrent()
                    assertEquals(TransferPhase.Paused, receiver.phaseOf(id))
                }
                releaseAccept.complete(Unit)
                accepting.join()
                advanceTimeBy(100.milliseconds)
                runCurrent()
                assertEquals(
                    if (loseLink) TransferPhase.Paused else TransferPhase.Transferring,
                    receiver.phaseOf(id),
                )
                assertFalse(TransferMessageTypes.ERROR in receiver.transferMessageTypes())
                advanceTimeBy(900.milliseconds)
                runCurrent()
                assertEquals(TransferPhase.Paused, receiver.phaseOf(id))
                assertEquals(
                    if (loseLink) TransferErrorCode.CONNECTION_LOST else TransferErrorCode.TRANSFER_TIMEOUT,
                    receiver.errorCodeOf(id),
                )
                assertTrue(receiver.partFile(id).exists())
                advanceTimeBy(1.seconds)
                runCurrent()
                assertEquals(if (loseLink) TransferPhase.Paused else TransferPhase.Failed, receiver.phaseOf(id))
                if (loseLink) {
                    advanceTimeBy(1.seconds)
                    runCurrent()
                }
                assertEquals(TransferPhase.Failed, receiver.phaseOf(id))
                assertFalse(receiver.partFile(id).exists())
                assertFalse(receiver.sidecarFile(id).exists())
                assertEquals(
                    if (loseLink) TransferErrorCode.CONNECTION_LOST else TransferErrorCode.TRANSFER_TIMEOUT,
                    receiver.errorCodeOf(id),
                )
                if (!loseLink) {
                    assertEquals(LinkState.Active, receiver.session.state.value)
                    assertEquals(1, receiver.transferMessageTypes().count { it == TransferMessageTypes.ERROR })
                }
            }
        }

    private suspend fun TestScope.idleReceiver(
        policy: TimeoutPolicy = TimeoutPolicy(transferInactivityMillis = 1_000),
    ): Triple<Peer, Peer, FileMetadata> {
        val sender = Peer("a", this, temp.newFolder(), policy)
        val receiver = Peer("b", this, temp.newFolder(), policy)
        connect(sender, receiver)
        val metadata = metadataFor("idle.bin", ByteArray(8 * 64 * 1024), chunkSize = 64 * 1024)
        val wire = LinkTransferWire(sender.session, metadata.transferId)
        wire.sendOffer(metadata)
        pump(sender, receiver)
        receiver.manager.accept(metadata.transferId, receiver.destination(metadata.name))
        pump(sender, receiver)
        wire.sendStart(
            com.beam.app.protocol.transfer.TransferStartBody(
                metadata.transferId,
                metadata.chunkSize,
                metadata.chunkCount,
                0,
            ),
        )
        pump(sender, receiver)
        return Triple(sender, receiver, metadata)
    }

    private suspend fun TestScope.deliverChunk(
        sender: Peer,
        receiver: Peer,
        metadata: FileMetadata,
        index: Long,
    ) {
        LinkTransferWire(sender.session, metadata.transferId).sendChunk(
            com.beam.app.protocol
                .ChunkHeader(UUID.fromString(metadata.transferId), index, metadata.chunkSize),
            ByteArray(metadata.chunkSize),
        )
        pump(sender, receiver)
    }

    @Test
    fun `receiver inactivity pauses then fails and cleans temps at configured limit`() =
        runTest {
            val (sender, receiver, metadata) = idleReceiver()
            val id = metadata.transferId
            repeat(4) { deliverChunk(sender, receiver, metadata, it.toLong()) }
            assertTrue(receiver.sidecarFile(id).exists())
            advanceTimeBy(999.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Transferring, receiver.phaseOf(id))
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Paused, receiver.phaseOf(id))
            assertEquals(TransferErrorCode.TRANSFER_TIMEOUT, receiver.errorCodeOf(id))
            assertTrue(receiver.partFile(id).exists())
            assertTrue(receiver.sidecarFile(id).exists())
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(TransferPhase.Paused, receiver.phaseOf(id))
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(TransferPhase.Failed, receiver.phaseOf(id))
            assertFalse(receiver.partFile(id).exists())
            assertFalse(receiver.sidecarFile(id).exists())
            assertFalse(receiver.destination(metadata.name).exists())
            assertEquals(1, receiver.transferMessageTypes().count { it == TransferMessageTypes.ERROR })
            assertEquals(LinkState.Active, receiver.session.state.value)
            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(1, receiver.transferMessageTypes().count { it == TransferMessageTypes.ERROR })
        }

    @Test
    fun `receiver inactivity is armed at START even if no first chunk arrives`() =
        runTest {
            val (_, receiver, metadata) =
                idleReceiver(
                    TimeoutPolicy(transferInactivityMillis = 750, maxConsecutiveInactivity = 1),
                )
            advanceTimeBy(749.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Transferring, receiver.phaseOf(metadata.transferId))
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Failed, receiver.phaseOf(metadata.transferId))
            assertFalse(receiver.partFile(metadata.transferId).exists())
        }

    @Test
    fun `new receiver progress clears inactivity pause and resets consecutive expiries`() =
        runTest {
            val (sender, receiver, metadata) = idleReceiver()
            val id = metadata.transferId
            deliverChunk(sender, receiver, metadata, 0)
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(TransferPhase.Paused, receiver.phaseOf(id))
            deliverChunk(sender, receiver, metadata, 0)
            assertEquals(TransferPhase.Paused, receiver.phaseOf(id))
            deliverChunk(sender, receiver, metadata, 1)
            assertEquals(TransferPhase.Transferring, receiver.phaseOf(id))
            assertNull(receiver.errorCodeOf(id))
            advanceTimeBy(999.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Transferring, receiver.phaseOf(id))
            advanceTimeBy(1_001.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Paused, receiver.phaseOf(id))
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(TransferPhase.Failed, receiver.phaseOf(id))
        }

    @Test
    fun `duplicate chunks do not postpone receiver inactivity failure`() =
        runTest {
            val (sender, receiver, metadata) = idleReceiver()
            deliverChunk(sender, receiver, metadata, 0)
            repeat(3) {
                advanceTimeBy(900.milliseconds)
                runCurrent()
                deliverChunk(sender, receiver, metadata, 0)
            }
            advanceTimeBy(300.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Failed, receiver.phaseOf(metadata.transferId))
        }

    @Test
    fun `receiver watchdog stops on completion or cancellation`() =
        runTest {
            for (complete in listOf(true, false)) {
                val (sender, receiver, metadata) = idleReceiver()
                val id = metadata.transferId
                if (complete) {
                    repeat(8) { deliverChunk(sender, receiver, metadata, it.toLong()) }
                    LinkTransferWire(sender.session, id).sendEnd(
                        com.beam.app.protocol.transfer
                            .TransferEndBody(id, metadata.sizeBytes),
                    )
                    pump(sender, receiver)
                } else {
                    receiver.manager.cancel(id)
                }
                val expected = if (complete) TransferPhase.Completed else TransferPhase.Cancelled
                assertEquals(expected, receiver.phaseOf(id))
                advanceTimeBy(5.seconds)
                runCurrent()
                assertEquals(expected, receiver.phaseOf(id))
                assertEquals(0, receiver.transferMessageTypes().count { it == TransferMessageTypes.ERROR })
                assertFalse(receiver.partFile(id).exists())
            }
        }

    @Test
    fun `real link loss replaces receiver inactivity with reconnect window`() =
        runTest {
            val (_, receiver, metadata) =
                idleReceiver(
                    TimeoutPolicy(transferInactivityMillis = 1_000, reconnectWindowMillis = 5_000),
                )
            advanceTimeBy(1.seconds)
            runCurrent()
            receiver.transport.receiveLinkLost()
            runCurrent()
            advanceTimeBy(3.seconds)
            runCurrent()
            assertEquals(TransferPhase.Paused, receiver.phaseOf(metadata.transferId))
            assertTrue(receiver.partFile(metadata.transferId).exists())
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(TransferPhase.Failed, receiver.phaseOf(metadata.transferId))
            assertEquals(TransferErrorCode.CONNECTION_LOST, receiver.errorCodeOf(metadata.transferId))
            assertFalse(receiver.partFile(metadata.transferId).exists())
        }

    @Test
    fun `stale temps are swept when the manager starts`() =
        runTest(timeout = 30.seconds) {
            val dir = temp.newFolder()
            val orphanPart = File(dir, "${UUID.randomUUID()}.part").apply { writeBytes(ByteArray(16)) }
            val orphanSidecar = File(dir, "${UUID.randomUUID()}.ranges").apply { writeText("0-1\n") }

            Peer("b", this, dir)

            assertFalse(orphanPart.exists())
            assertFalse(orphanSidecar.exists())
        }
}

private fun cancelCount(
    a: Peer,
    b: Peer,
): Int =
    a.transferMessageTypes().count { it == TransferMessageTypes.CANCEL } +
        b.transferMessageTypes().count { it == TransferMessageTypes.CANCEL }
