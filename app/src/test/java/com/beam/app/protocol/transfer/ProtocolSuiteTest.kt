@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.beam.app.protocol.transfer

import com.beam.app.protocol.ChunkHeader
import com.beam.app.protocol.Frame
import com.beam.app.protocol.FrameType
import com.beam.app.protocol.MessageEnvelope
import com.beam.app.protocol.session.FakeTransport
import com.beam.app.protocol.session.LinkSession
import com.beam.app.protocol.session.LinkState
import com.beam.app.protocol.session.LocalIdentity
import com.beam.app.protocol.session.SessionCapabilities
import com.beam.app.protocol.session.SessionEvent
import com.beam.app.protocol.session.SessionRole
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }

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

private class SessionWire(
    private val session: LinkSession,
    val transferId: String,
) : TransferWire {
    override suspend fun sendStart(body: TransferStartBody) =
        ctrl(TransferMessageTypes.START, transferId, body.toJsonElement())

    override suspend fun sendChunk(
        header: ChunkHeader,
        payload: ByteArray,
    ) = session.sendData(header, payload)

    override suspend fun sendAck(body: ChunkAckBody) = ctrl(TransferMessageTypes.ACK, transferId, body.toJsonElement())

    override suspend fun sendEnd(body: TransferEndBody) =
        ctrl(TransferMessageTypes.END, transferId, body.toJsonElement())

    override suspend fun sendVerified(body: TransferVerifiedBody) =
        ctrl(TransferMessageTypes.VERIFIED, transferId, body.toJsonElement())

    override suspend fun sendVerifyFailed(body: VerifyFailedBody) =
        ctrl(TransferMessageTypes.VERIFY_FAILED, transferId, body.toJsonElement())

    override suspend fun sendError(body: TransferErrorBody) =
        ctrl(TransferMessageTypes.ERROR, transferId, body.toJsonElement())

    override suspend fun sendCancel(body: TransferCancelBody) =
        ctrl(TransferMessageTypes.CANCEL, transferId, body.toJsonElement())

    suspend fun sendOffer(metadata: FileMetadata) =
        ctrl(TransferMessageTypes.OFFER, transferId, metadata.toJsonElement())

    suspend fun sendAccept() = ctrl(TransferMessageTypes.ACCEPT, transferId, FileAcceptBody(transferId).toJsonElement())

    suspend fun sendReject(reason: RejectReason) =
        ctrl(TransferMessageTypes.REJECT, transferId, FileRejectBody(transferId, reason).toJsonElement())

    private suspend fun ctrl(
        type: String,
        transferId: String,
        body: JsonElement?,
    ) {
        val identity = session.localIdentity
        session.sendControl(
            buildTransferEnvelope(
                type = type,
                transferId = transferId,
                sessionId = identity.sessionId,
                deviceId = identity.deviceId,
                messageId = session.nextOutboundMid(),
                body = body,
            ),
        )
    }
}

private fun Peer.controlTypes(): List<String> =
    transport.sent
        .filter { it.type == FrameType.CTRL }
        .map { MessageEnvelope.decode(it.payload.toString(Charsets.UTF_8)).type }

private fun Peer.dataFrameCount(): Int = transport.sent.count { it.type == FrameType.DATA }

private class Peer(
    name: String,
    private val scope: TestScope,
    private val tempDir: File,
) {
    val transport = FakeTransport()
    val session =
        LinkSession(
            transport = transport,
            local =
                LocalIdentity(
                    deviceId = "dev-$name",
                    deviceName = "Device $name",
                    role = SessionRole.PEER,
                    sessionId = SESSION_ID,
                    beamCode = BEAM_CODE,
                ),
            scope = scope.backgroundScope,
            supportedVersions = listOf(MessageEnvelope.PROTOCOL_VERSION),
            advertisedCapabilities = setOf(SessionCapabilities.CHUNKING),
        )

    var delivered = 0

    val offers = mutableListOf<FileMetadata>()

    var offerPrompts = 0
    var acceptOffers = true
    var rejectReason = RejectReason.USER_REJECTED

    var offeredMetadata: FileMetadata? = null
    var sender: ChunkSender? = null
    var receiver: ChunkReceiver? = null
    var completer: TransferCompleter? = null
    var completion: CompletionStatus? = null

    val problems = mutableListOf<Throwable>()

    private val decisions = OfferDecisionCache()
    private val destinationDir = File(tempDir, "received")
    private var wire: SessionWire? = null

    init {
        scope.backgroundScope.launch {
            for (event in session.events) {
                try {
                    handle(event)
                } catch (t: Throwable) {
                    problems += t
                }
            }
        }
    }

    fun start() = session.start()

    suspend fun offer(
        metadata: FileMetadata,
        source: () -> InputStream,
        window: Int = ChunkPlan.DEFAULT_WINDOW_CHUNKS,
        timeouts: TransferTimeouts = TransferTimeouts(),
    ) {
        val transferWire = wireFor(metadata.transferId)
        offeredMetadata = metadata
        sender = ChunkSender(metadata, source, transferWire, window, timeouts)
        transferWire.sendOffer(metadata)
    }

    suspend fun resendOffer() {
        val metadata = offeredMetadata ?: return
        wireFor(metadata.transferId).sendOffer(metadata)
    }

    fun partFile(transferId: String): File = File(tempDir, "$transferId.part")

    fun sidecarFile(transferId: String): File = File(tempDir, "$transferId.ranges")

    private fun wireFor(transferId: String): SessionWire =
        wire?.takeIf { it.transferId == transferId }
            ?: SessionWire(session, transferId).also { wire = it }

    private suspend fun handle(event: SessionEvent) {
        when (event) {
            is SessionEvent.ControlReceived -> handleControl(event.envelope)
            is SessionEvent.ChunkReceived -> receiver?.onChunk(event.header, event.payload)
            else -> Unit
        }
    }

    private suspend fun handleControl(envelope: MessageEnvelope) {
        if (envelope.type !in TransferMessageTypes.ALL) return
        val transferId = envelope.transferId ?: return
        val transferWire = wireFor(transferId)
        when (envelope.type) {
            TransferMessageTypes.OFFER -> {
                handleOffer(envelope, transferWire)
            }

            TransferMessageTypes.ACCEPT -> {
                handleAccept(envelope)
            }

            TransferMessageTypes.REJECT -> {
                handleReject(envelope)
            }

            TransferMessageTypes.START -> {
                receiver?.onStart(envelope.body.decodeTransferBody(TransferStartBody.serializer(), envelope.type))
            }

            TransferMessageTypes.ACK -> {
                sender?.onAck(envelope.body.decodeTransferBody(ChunkAckBody.serializer(), envelope.type))
            }

            TransferMessageTypes.END -> {
                handleEnd(envelope)
            }

            TransferMessageTypes.VERIFIED -> {
                sender?.onVerified(envelope.body.decodeTransferBody(TransferVerifiedBody.serializer(), envelope.type))
            }

            TransferMessageTypes.VERIFY_FAILED -> {
                sender?.onVerifyFailed(envelope.body.decodeTransferBody(VerifyFailedBody.serializer(), envelope.type))
            }

            TransferMessageTypes.CANCEL -> {
                handleCancel(envelope)
            }

            TransferMessageTypes.ERROR -> {
                handleError(envelope)
            }
        }
    }

    private suspend fun handleOffer(
        envelope: MessageEnvelope,
        transferWire: SessionWire,
    ) {
        val metadata = envelope.body.decodeTransferBody(FileMetadata.serializer(), envelope.type)
        offers += metadata
        val decision =
            decisions.firstArrival(metadata.transferId) {
                offerPrompts += 1
                if (acceptOffers) OfferDecisionCache.Decision.ACCEPTED else OfferDecisionCache.Decision.REJECTED
            }
        if (decision == OfferDecisionCache.Decision.ACCEPTED) {
            if (receiver == null) {
                val created = ChunkReceiver(metadata, tempDir, transferWire)
                receiver = created
                completer = TransferCompleter(metadata, tempDir, transferWire, state = created.state)
            }
            transferWire.sendAccept()
        } else {
            transferWire.sendReject(rejectReason)
        }
    }

    private fun handleAccept(envelope: MessageEnvelope) {
        val chunkSender = sender ?: return
        val body = envelope.body.decodeTransferBody(FileAcceptBody.serializer(), envelope.type)
        if (body.transferId != offeredMetadata?.transferId) return
        // A duplicate ACCEPT on a live transfer is ignored.
        if (chunkSender.state.phase !is TransferPhase.Offered) return
        scope.backgroundScope.launch {
            runCatching { chunkSender.run() }.onFailure {
                // Cancelling is a normal outcome, not a protocol problem.
                if (it !is TransferCancelledException) problems += it
            }
        }
    }

    private fun handleReject(envelope: MessageEnvelope) {
        val chunkSender = sender ?: return
        val body = envelope.body.decodeTransferBody(FileRejectBody.serializer(), envelope.type)
        if (body.transferId != offeredMetadata?.transferId) return
        runCatching { chunkSender.state.on(TransferEvent.FileRejected) }
    }

    private suspend fun handleEnd(envelope: MessageEnvelope) {
        val chunkReceiver = receiver ?: return
        val body = envelope.body.decodeTransferBody(TransferEndBody.serializer(), envelope.type)
        if (chunkReceiver.onEnd(body) != ReceiveEndStatus.READY_TO_VERIFY) return
        completion = completer?.complete(File(destinationDir, chunkReceiverName()))
    }

    private fun chunkReceiverName(): String = offers.lastOrNull()?.name ?: "received.bin"

    private suspend fun handleCancel(envelope: MessageEnvelope) {
        val body = envelope.body.decodeTransferBody(TransferCancelBody.serializer(), envelope.type)
        runCatching { sender?.onPeerCancel(body) }
        runCatching { receiver?.onPeerCancel(body) }
    }

    private suspend fun handleError(envelope: MessageEnvelope) {
        val body = envelope.body.decodeTransferBody(TransferErrorBody.serializer(), envelope.type)
        runCatching { sender?.onPeerError(body) }
        runCatching { receiver?.onPeerError(body) }
    }
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

    @Test
    fun `text file flows offer accept start chunks verify publish`() =
        runTest(timeout = 30.seconds) {
            val senderPeer = Peer("a", this, temp.newFolder())
            val receiverPeer = Peer("b", this, temp.newFolder())
            connect(senderPeer, receiverPeer)
            assertEquals(LinkState.Active, senderPeer.session.state.value)
            assertEquals(LinkState.Active, receiverPeer.session.state.value)

            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64)
            senderPeer.offer(metadata, source = { ByteArrayInputStream(textBytes) })
            pump(senderPeer, receiverPeer)

            assertEquals(CompletionStatus.PUBLISHED, receiverPeer.completion)
            assertEquals(
                textBytes.toList(),
                receiverPeer.completer!!
                    .destinationUsed!!
                    .readBytes()
                    .toList(),
            )

            assertFalse(receiverPeer.partFile(metadata.transferId).exists())
            assertFalse(receiverPeer.sidecarFile(metadata.transferId).exists())

            assertEquals(1, receiverPeer.offerPrompts)
            val receiverTypes = receiverPeer.controlTypes().filter { it in TransferMessageTypes.ALL }
            val senderTypes = senderPeer.controlTypes().filter { it in TransferMessageTypes.ALL }
            assertEquals(TransferMessageTypes.ACCEPT, receiverTypes.first())
            assertEquals(1, receiverTypes.count { it == TransferMessageTypes.VERIFIED })
            assertTrue(receiverTypes.none { it == TransferMessageTypes.VERIFY_FAILED })
            assertEquals(
                listOf(TransferMessageTypes.OFFER, TransferMessageTypes.START, TransferMessageTypes.END),
                senderTypes,
            )
            assertEquals(metadata.chunkCount.toInt(), senderPeer.dataFrameCount())
            assertEquals(textBytes.size.toLong(), senderPeer.sender!!.bytesSent)
            assertTrue(senderPeer.problems.toString(), senderPeer.problems.isEmpty())
            assertTrue(receiverPeer.problems.toString(), receiverPeer.problems.isEmpty())
            assertEquals(TransferPhase.Completed, receiverPeer.receiver!!.state.phase)
            assertEquals(
                "controlTypes=$receiverTypes senderProblems=${senderPeer.problems}",
                TransferPhase.Completed,
                senderPeer.sender!!.state.phase,
            )

            assertEquals(LinkState.Active, senderPeer.session.state.value)
            assertEquals(LinkState.Active, receiverPeer.session.state.value)
        }

    @Test
    fun `every reject reason ends the offer without a single data frame`() =
        runTest(timeout = 30.seconds) {
            for (reason in RejectReason.entries) {
                val senderPeer = Peer("a", this, temp.newFolder())
                val receiverPeer = Peer("b", this, temp.newFolder())
                receiverPeer.acceptOffers = false
                receiverPeer.rejectReason = reason
                connect(senderPeer, receiverPeer)

                val metadata = metadataFor("note.txt", textBytes, chunkSize = 64)
                senderPeer.offer(metadata, source = { ByteArrayInputStream(textBytes) })
                pump(senderPeer, receiverPeer)

                assertEquals(reason.name, TransferPhase.Rejected, senderPeer.sender!!.state.phase)
                assertTrue(reason.name, senderPeer.dataFrameCount() == 0)
                assertEquals(
                    reason.name,
                    listOf(TransferMessageTypes.OFFER),
                    senderPeer.controlTypes().filter { it in TransferMessageTypes.ALL },
                )
                assertEquals(
                    reason.name,
                    listOf(TransferMessageTypes.REJECT),
                    receiverPeer.controlTypes().filter { it in TransferMessageTypes.ALL },
                )

                assertNull(receiverPeer.receiver)
                assertNull(receiverPeer.completer)
                assertFalse(receiverPeer.partFile(metadata.transferId).exists())
                assertEquals(LinkState.Active, senderPeer.session.state.value)
                assertEquals(LinkState.Active, receiverPeer.session.state.value)
                assertTrue(senderPeer.problems.toString(), senderPeer.problems.isEmpty())
                assertTrue(receiverPeer.problems.toString(), receiverPeer.problems.isEmpty())
            }
        }

    @Test
    fun `duplicate FILE_OFFER re-sends the cached decision without re-prompting`() =
        runTest(timeout = 30.seconds) {
            val senderPeer = Peer("a", this, temp.newFolder())
            val receiverPeer = Peer("b", this, temp.newFolder())
            connect(senderPeer, receiverPeer)

            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64)
            senderPeer.offer(metadata, source = { ByteArrayInputStream(textBytes) })
            pump(senderPeer, receiverPeer, maxRounds = 3)
            assertEquals(TransferPhase.Transferring, receiverPeer.receiver!!.state.phase)

            senderPeer.resendOffer()
            pump(senderPeer, receiverPeer)

            assertEquals(2, receiverPeer.offers.size)
            assertEquals(1, receiverPeer.offerPrompts)
            val receiverTypes = receiverPeer.controlTypes().filter { it in TransferMessageTypes.ALL }
            assertEquals(2, receiverTypes.count { it == TransferMessageTypes.ACCEPT })
            assertEquals(
                1,
                senderPeer
                    .controlTypes()
                    .filter { it in TransferMessageTypes.ALL }
                    .count { it == TransferMessageTypes.START },
            )

            assertEquals(TransferPhase.Completed, senderPeer.sender!!.state.phase)
            assertEquals(metadata.chunkCount.toInt(), senderPeer.dataFrameCount())
            assertEquals(
                textBytes.toList(),
                receiverPeer.completer!!
                    .destinationUsed!!
                    .readBytes()
                    .toList(),
            )
            assertTrue(senderPeer.problems.toString(), senderPeer.problems.isEmpty())
            assertTrue(receiverPeer.problems.toString(), receiverPeer.problems.isEmpty())
        }

    @Test
    fun `hash mismatch fails the transfer, deletes the temp and keeps the link alive`() =
        runTest(timeout = 30.seconds) {
            val senderPeer = Peer("a", this, temp.newFolder())
            val receiverPeer = Peer("b", this, temp.newFolder())
            connect(senderPeer, receiverPeer)

            val different = textBytes.copyOf().also { it[0] = (it[0].toInt() + 1).toByte() }
            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64, sha256 = sha256(different))
            senderPeer.offer(metadata, source = { ByteArrayInputStream(textBytes) })
            pump(senderPeer, receiverPeer)

            assertEquals(CompletionStatus.VERIFY_FAILED, receiverPeer.completion)
            assertEquals(TransferPhase.Failed, receiverPeer.receiver!!.state.phase)
            assertEquals(TransferPhase.Failed, senderPeer.sender!!.state.phase)
            assertNull(receiverPeer.completer!!.destinationUsed)
            assertFalse(receiverPeer.partFile(metadata.transferId).exists())
            assertFalse(receiverPeer.sidecarFile(metadata.transferId).exists())
            assertEquals(1, receiverPeer.controlTypes().count { it == TransferMessageTypes.VERIFY_FAILED })

            assertEquals(LinkState.Active, senderPeer.session.state.value)
            assertEquals(LinkState.Active, receiverPeer.session.state.value)
            assertTrue(senderPeer.problems.toString(), senderPeer.problems.isEmpty())
            assertTrue(receiverPeer.problems.toString(), receiverPeer.problems.isEmpty())
        }

    @Test
    fun `cancel mid-stream stops both sides, deletes the temp and is idempotent`() =
        runTest(timeout = 30.seconds) {
            val senderPeer = Peer("a", this, temp.newFolder())
            val receiverPeer = Peer("b", this, temp.newFolder())
            connect(senderPeer, receiverPeer)

            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64)
            senderPeer.offer(metadata, source = { ByteArrayInputStream(textBytes) })
            pump(senderPeer, receiverPeer, maxRounds = 3)
            assertEquals(TransferPhase.Transferring, receiverPeer.receiver!!.state.phase)
            assertTrue(receiverPeer.partFile(metadata.transferId).exists())
            val chunksBeforeCancel = senderPeer.dataFrameCount()

            receiverPeer.receiver!!.cancel(
                TransferError(TransferErrorCode.TRANSFER_CANCELLED, "user tapped stop"),
            )
            pump(senderPeer, receiverPeer)

            assertEquals(TransferPhase.Cancelled, receiverPeer.receiver!!.state.phase)
            assertEquals(TransferPhase.Cancelled, senderPeer.sender!!.state.phase)
            assertFalse(receiverPeer.partFile(metadata.transferId).exists())
            assertFalse(receiverPeer.sidecarFile(metadata.transferId).exists())
            assertNull(receiverPeer.completion)

            assertEquals(2, cancelCount(senderPeer, receiverPeer))

            val afterCancel = senderPeer.dataFrameCount()
            assertTrue((afterCancel - chunksBeforeCancel) <= 1)

            receiverPeer.receiver!!.cancel(TransferError(TransferErrorCode.TRANSFER_CANCELLED, "again"))
            senderPeer.sender!!.cancel(TransferError(TransferErrorCode.TRANSFER_CANCELLED, "again"))
            pump(senderPeer, receiverPeer)

            assertEquals(2, cancelCount(senderPeer, receiverPeer))
            assertEquals(afterCancel, senderPeer.dataFrameCount())
            assertEquals(LinkState.Active, senderPeer.session.state.value)
        }

    @Test
    fun `withdrawing an offer before accept sends no data`() =
        runTest(timeout = 30.seconds) {
            val senderPeer = Peer("a", this, temp.newFolder())
            val receiverPeer = Peer("b", this, temp.newFolder())
            connect(senderPeer, receiverPeer)

            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64)
            senderPeer.offer(metadata, source = { ByteArrayInputStream(textBytes) })
            pump(senderPeer, receiverPeer, maxRounds = 1)

            senderPeer.sender!!.cancel(
                TransferError(TransferErrorCode.TRANSFER_CANCELLED, "changed my mind"),
            )
            pump(senderPeer, receiverPeer)

            assertEquals(TransferPhase.Cancelled, senderPeer.sender!!.state.phase)
            assertTrue(senderPeer.dataFrameCount() == 0)
            assertTrue(
                senderPeer
                    .controlTypes()
                    .none { it == TransferMessageTypes.START },
            )
            // Nothing was received and nothing was published.
            assertNull(receiverPeer.completion)
            assertFalse(receiverPeer.partFile(metadata.transferId).exists())
            assertNull(receiverPeer.completer!!.destinationUsed)
            assertEquals(LinkState.Active, senderPeer.session.state.value)
            assertEquals(LinkState.Active, receiverPeer.session.state.value)
        }

    @Test
    fun `link loss mid-stream pauses then fails the sender and keeps the receiver temp`() =
        runTest(timeout = 30.seconds) {
            val senderPeer = Peer("a", this, temp.newFolder())
            val receiverPeer = Peer("b", this, temp.newFolder())
            connect(senderPeer, receiverPeer)

            val policy =
                TimeoutPolicy(
                    transferInactivityMillis = 1_000,
                    maxConsecutiveInactivity = 3,
                )
            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64)
            senderPeer.offer(
                metadata,
                source = { ByteArrayInputStream(textBytes) },
                window = 1,
                timeouts = TransferTimeouts(policy),
            )
            pump(senderPeer, receiverPeer, maxRounds = 3)
            assertEquals(TransferPhase.Transferring, receiverPeer.receiver!!.state.phase)
            assertTrue(receiverPeer.partFile(metadata.transferId).exists())
            val chunksBeforeLoss = senderPeer.dataFrameCount()

            senderPeer.transport.receiveLinkLost()
            receiverPeer.transport.receiveLinkLost()
            runCurrent()

            val senderClosed = senderPeer.session.state.value as LinkState.Closed
            assertFalse(senderClosed.graceful)
            assertTrue(senderClosed.initiatedByRemote)
            assertTrue((receiverPeer.session.state.value as LinkState.Closed).graceful.not())

            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Paused, senderPeer.sender!!.state.phase)

            advanceTimeBy(2_000.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Failed, senderPeer.sender!!.state.phase)
            assertTrue(senderPeer.problems.any { it is TransferTimeoutException })

            assertEquals(chunksBeforeLoss, senderPeer.dataFrameCount())
            assertTrue(receiverPeer.partFile(metadata.transferId).exists())
            assertNull(receiverPeer.completion)
        }

    @Test
    fun `a replayed envelope with the same mid is ignored`() =
        runTest(timeout = 30.seconds) {
            val senderPeer = Peer("a", this, temp.newFolder())
            val receiverPeer = Peer("b", this, temp.newFolder())
            connect(senderPeer, receiverPeer)

            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64)
            senderPeer.offer(metadata, source = { ByteArrayInputStream(textBytes) })
            pump(senderPeer, receiverPeer, maxRounds = 2)

            val acceptFrame =
                receiverPeer.transport.sent.first { frame ->
                    frame.type == FrameType.CTRL &&
                        MessageEnvelope.decode(frame.payload.toString(Charsets.UTF_8)).type ==
                        TransferMessageTypes.ACCEPT
                }
            senderPeer.transport.receiveFrame(acceptFrame)
            runCurrent()
            senderPeer.transport.receiveFrame(acceptFrame)
            runCurrent()

            assertEquals(1, startCount(senderPeer))

            pump(senderPeer, receiverPeer)
            assertEquals(TransferPhase.Completed, senderPeer.sender!!.state.phase)
            assertEquals(metadata.chunkCount.toInt(), senderPeer.dataFrameCount())
            assertTrue(senderPeer.problems.toString(), senderPeer.problems.isEmpty())
        }

    @Test
    fun `a paused transfer can still be cancelled`() =
        runTest(timeout = 30.seconds) {
            val senderPeer = Peer("a", this, temp.newFolder())
            val receiverPeer = Peer("b", this, temp.newFolder())
            connect(senderPeer, receiverPeer)

            val policy = TimeoutPolicy(transferInactivityMillis = 1_000, maxConsecutiveInactivity = 3)
            val metadata = metadataFor("note.txt", textBytes, chunkSize = 64)
            senderPeer.offer(
                metadata,
                source = { ByteArrayInputStream(textBytes) },
                window = 1,
                timeouts = TransferTimeouts(policy),
            )
            pump(senderPeer, receiverPeer, maxRounds = 3)
            senderPeer.transport.receiveLinkLost()
            runCurrent()
            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            assertEquals(TransferPhase.Paused, senderPeer.sender!!.state.phase)

            senderPeer.sender!!.cancel(
                TransferError(TransferErrorCode.TRANSFER_CANCELLED, "user gave up"),
            )
            pump(senderPeer, receiverPeer)

            assertEquals(TransferPhase.Cancelled, senderPeer.sender!!.state.phase)
            assertTrue(receiverPeer.partFile(metadata.transferId).exists())
        }

    @Test
    fun `the ranges sidecar mirrors acked progress and dies with the transfer`() =
        runTest(timeout = 30.seconds) {
            val receiverDir = temp.newFolder()
            val senderPeer = Peer("a", this, temp.newFolder())
            val receiverPeer = Peer("b", this, receiverDir)
            connect(senderPeer, receiverPeer)

            val payload = ByteArray(64 * 256) { (it % 251).toByte() }
            val metadata = metadataFor("bulk.bin", payload, chunkSize = 256)
            senderPeer.offer(metadata, source = { ByteArrayInputStream(payload) })
            pump(senderPeer, receiverPeer, maxRounds = 3)

            val sidecar = receiverPeer.sidecarFile(metadata.transferId)
            assertTrue(sidecar.exists())
            assertEquals(listOf("0-7"), sidecar.readLines().filter { it.isNotBlank() })
            assertEquals(listOf(0L..7L), RangesSidecar(receiverDir, metadata.transferId).read())

            pump(senderPeer, receiverPeer)

            assertEquals(CompletionStatus.PUBLISHED, receiverPeer.completion)
            assertEquals(
                payload.toList(),
                receiverPeer.completer!!
                    .destinationUsed!!
                    .readBytes()
                    .toList(),
            )
            assertFalse(sidecar.exists())
            assertFalse(receiverPeer.partFile(metadata.transferId).exists())
        }
}

private fun cancelCount(
    a: Peer,
    b: Peer,
): Int =
    a.controlTypes().count { it == TransferMessageTypes.CANCEL } +
        b.controlTypes().count { it == TransferMessageTypes.CANCEL }

private fun startCount(peer: Peer): Int =
    peer
        .controlTypes()
        .filter { it in TransferMessageTypes.ALL }
        .count { it == TransferMessageTypes.START }
