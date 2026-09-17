@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.beam.app.protocol.session

import com.beam.app.protocol.ChunkHeader
import com.beam.app.protocol.Frame
import com.beam.app.protocol.FrameType
import com.beam.app.protocol.MessageEnvelope
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

private const val SESSION_ID = "sess-0001"
private const val BEAM_CODE = "BEAM01"

/** One LinkSession wired to a [FakeTransport], with its collected events. */
private class TestPeer(
    name: String,
    scope: TestScope,
    versions: List<String> = listOf(MessageEnvelope.PROTOCOL_VERSION),
    capabilities: Set<String> = setOf(SessionCapabilities.CHUNKING),
    role: SessionRole = SessionRole.PEER,
) {
    val transport = FakeTransport()
    val session =
        LinkSession(
            transport = transport,
            local =
                LocalIdentity(
                    deviceId = "dev-$name",
                    deviceName = "Device $name",
                    role = role,
                    sessionId = SESSION_ID,
                    beamCode = BEAM_CODE,
                ),
            scope = scope.backgroundScope,
            supportedVersions = versions,
            advertisedCapabilities = capabilities,
        )
    val events = mutableListOf<SessionEvent>()

    init {
        // backgroundScope: collector is auto-cancelled at test end, so the
        // never-completing events channel can't trip runTest's
        // UncompletedCoroutinesError check.
        scope.backgroundScope.launch {
            session.events.consumeEach { event -> events += event }
        }
    }
}

private fun helloEnvelope(
    versions: List<String> = listOf(MessageEnvelope.PROTOCOL_VERSION),
    capabilities: List<String> = listOf(SessionCapabilities.CHUNKING),
    sessionId: String = SESSION_ID,
    beamCode: String = BEAM_CODE,
    messageId: String = "00000001",
): MessageEnvelope =
    buildSessionEnvelope(
        type = SessionMessageTypes.HELLO,
        sessionId = sessionId,
        deviceId = "dev-remote",
        messageId = messageId,
        body =
            SessionHelloBody(
                supportedVersions = versions,
                deviceId = "dev-remote",
                deviceName = "Remote Device",
                role = SessionRole.HOST,
                sessionId = sessionId,
                capabilities = capabilities,
                beamCode = beamCode,
            ).toJsonElement(),
    )

private fun TestPeer.startSession(scope: TestScope) {
    session.start()
    scope.runCurrent()
}

private fun startEnvelope(messageId: String = "00000009"): MessageEnvelope =
    buildSessionEnvelope(
        type = SessionMessageTypes.START,
        sessionId = SESSION_ID,
        deviceId = "dev-host",
        messageId = messageId,
        body = SessionStartBody().toJsonElement(),
    )

private fun Frame.typeName(): String = MessageEnvelope.decode(payload.toString(Charsets.UTF_8)).type

/** Runs one full simultaneous HELLO/READY exchange between two fresh peers. */
private suspend fun TestScope.handshakedPair(
    localVersions: List<String> = listOf(MessageEnvelope.PROTOCOL_VERSION),
    remoteVersions: List<String> = listOf(MessageEnvelope.PROTOCOL_VERSION),
): Pair<TestPeer, TestPeer> {
    val host = TestPeer("host", this, remoteVersions, role = SessionRole.HOST)
    val joiner = TestPeer("joiner", this, localVersions)
    host.startSession(this)
    joiner.startSession(this)
    // Exchange HELLOs.
    joiner.transport.receiveFrame(host.transport.sent[0])
    runCurrent()
    host.transport.receiveFrame(joiner.transport.sent[0])
    runCurrent()
    // Exchange the READYs each side produced.
    host.transport.receiveFrame(joiner.transport.sent[1])
    runCurrent()
    joiner.transport.receiveFrame(host.transport.sent[1])
    runCurrent()
    return joiner to host
}

class LinkSessionTest {
    @Test
    fun `simultaneous handshake activates both sides with agreed version`() =
        runTest {
            val (joiner, host) = handshakedPair()

            assertEquals(LinkState.Active, joiner.session.state.value)
            assertEquals(LinkState.Active, host.session.state.value)

            val joinerCompleted = joiner.events.filterIsInstance<SessionEvent.HandshakeCompleted>().single()
            assertEquals("BEAM/1.1", joinerCompleted.agreedVersion)
            assertEquals("dev-host", joinerCompleted.remote.deviceId)
            assertEquals(SessionRole.HOST, joinerCompleted.remote.role)
            assertEquals(setOf(SessionCapabilities.CHUNKING), joinerCompleted.negotiatedCapabilities)

            val hostCompleted = host.events.filterIsInstance<SessionEvent.HandshakeCompleted>().single()
            assertEquals("dev-joiner", hostCompleted.remote.deviceId)

            // HELLO, READY from each side; nothing else on the wire.
            assertEquals(listOf("SESSION_HELLO", "SESSION_READY"), host.transport.sent.map { it.typeName() })
        }

    @Test
    fun `major version mismatch refuses with UNSUPPORTED_VERSION`() =
        runTest {
            val (joiner, _) = handshakedPair(remoteVersions = listOf("BEAM/2.0"))

            val closed = joiner.session.state.value as LinkState.Closed
            assertEquals(SessionCloseReason.UNSUPPORTED_VERSION, closed.reason)
            assertTrue(closed.graceful)
            assertFalse(closed.initiatedByRemote)
            assertTrue(joiner.transport.closed)

            // Refusal went on the wire as a CTRL SESSION_CLOSE.
            val closeFrame = joiner.transport.sent.last()
            assertEquals(FrameType.CTRL, closeFrame.type)
            val closeEnvelope = MessageEnvelope.decode(closeFrame.payload.toString(Charsets.UTF_8))
            assertEquals(SessionMessageTypes.CLOSE, closeEnvelope.type)
            val closeBody =
                closeEnvelope.body.decodeSessionBody(SessionCloseBody.serializer(), SessionMessageTypes.CLOSE)
            assertEquals(SessionCloseReason.UNSUPPORTED_VERSION, closeBody.reason)
        }

    @Test
    fun `highest common major wins with lower minor`() =
        runTest {
            val agreed =
                BeamVersion.negotiate(
                    local = listOf("BEAM/1.1", "BEAM/2.1"),
                    remote = listOf("BEAM/1.5", "BEAM/2.0"),
                )
            assertEquals("BEAM/2.0", agreed)
        }

    @Test
    fun `sessionId mismatch refuses with AUTH_FAILED`() =
        runTest {
            val peer = TestPeer("joiner", this)
            peer.startSession(this)

            peer.transport.receiveFrame(helloEnvelope(sessionId = "other-session").toCtrlFrame())
            runCurrent()

            val closed = peer.session.state.value as LinkState.Closed
            assertEquals(SessionCloseReason.AUTH_FAILED, closed.reason)
            assertTrue(closed.graceful)
        }

    @Test
    fun `beamCode mismatch refuses with AUTH_FAILED`() =
        runTest {
            val peer = TestPeer("joiner", this)
            peer.startSession(this)

            peer.transport.receiveFrame(helloEnvelope(beamCode = "WRONG1").toCtrlFrame())
            runCurrent()

            val closed = peer.session.state.value as LinkState.Closed
            assertEquals(SessionCloseReason.AUTH_FAILED, closed.reason)
        }

    @Test
    fun `duplicate HELLO is ignored without penalty`() =
        runTest {
            val peer = TestPeer("joiner", this)
            peer.startSession(this)

            peer.transport.receiveFrame(helloEnvelope().toCtrlFrame())
            runCurrent()
            peer.transport.receiveFrame(helloEnvelope().toCtrlFrame())
            runCurrent()

            assertTrue(peer.events.filterIsInstance<SessionEvent.InvalidMessageDiscarded>().isEmpty())
            assertEquals(LinkState.Handshaking, peer.session.state.value)
        }

    @Test
    fun `first malformed frame discarded second closes the link`() =
        runTest {
            val peer = TestPeer("joiner", this)
            peer.startSession(this)

            peer.transport.receiveMalformed()
            runCurrent()
            assertEquals(LinkState.Handshaking, peer.session.state.value)

            peer.transport.receiveMalformed()
            runCurrent()

            val closed = peer.session.state.value as LinkState.Closed
            assertEquals(SessionCloseReason.INVALID_MESSAGE, closed.reason)
            assertTrue(peer.transport.closed)
            assertEquals(
                listOf(
                    SessionEvent.MalformedFrameDiscarded::class,
                    SessionEvent.MalformedFrameDiscarded::class,
                    SessionEvent.SessionClosed::class,
                ),
                peer.events.map { it::class },
            )
        }

    @Test
    fun `non-HELLO first message closes with INVALID_MESSAGE`() =
        runTest {
            val peer = TestPeer("joiner", this)
            peer.startSession(this)

            val readyEnvelope =
                buildSessionEnvelope(
                    type = SessionMessageTypes.READY,
                    sessionId = SESSION_ID,
                    deviceId = "dev-remote",
                    messageId = "00000001",
                    body = SessionReadyBody("BEAM/1.1").toJsonElement(),
                )
            peer.transport.receiveFrame(readyEnvelope.toCtrlFrame())
            runCurrent()

            val closed = peer.events.last() as SessionEvent.SessionClosed
            assertEquals(SessionCloseReason.INVALID_MESSAGE, closed.reason)
            assertEquals(
                SessionCloseReason.INVALID_MESSAGE,
                (peer.session.state.value as LinkState.Closed).reason,
            )
        }

    @Test
    fun `unknown type as the first message also closes the link`() =
        runTest {
            val peer = TestPeer("joiner", this)
            peer.startSession(this)

            peer.transport.receiveFrame(
                buildSessionEnvelope(
                    type = "FILE_OFFER",
                    sessionId = SESSION_ID,
                    deviceId = "dev-remote",
                    messageId = "00000001",
                    body = null,
                ).toCtrlFrame(),
            )
            runCurrent()

            assertEquals(
                SessionCloseReason.INVALID_MESSAGE,
                (peer.events.last() as SessionEvent.SessionClosed).reason,
            )
        }

    @Test
    fun `malformed envelope version closes with INVALID_MESSAGE`() =
        runTest {
            val (_, joiner) = handshakedPair()

            joiner.transport.receiveFrame(
                buildSessionEnvelope(
                    type = SessionMessageTypes.HELLO,
                    sessionId = SESSION_ID,
                    deviceId = "dev-remote",
                    messageId = "00000099",
                    body = null,
                ).copy(version = "HTTP/1.1").toCtrlFrame(),
            )
            runCurrent()

            assertEquals(
                SessionCloseReason.INVALID_MESSAGE,
                (joiner.events.last() as SessionEvent.SessionClosed).reason,
            )
        }

    @Test
    fun `envelope with a different major than agreed closes with INVALID_MESSAGE`() =
        runTest {
            val (_, joiner) = handshakedPair()
            assertEquals(LinkState.Active, joiner.session.state.value)

            joiner.transport.receiveFrame(
                buildSessionEnvelope(
                    type = "FILE_OFFER",
                    sessionId = SESSION_ID,
                    deviceId = "dev-remote",
                    messageId = "00000077",
                    body = null,
                ).copy(version = "BEAM/2.0").toCtrlFrame(),
            )
            runCurrent()

            assertEquals(
                SessionCloseReason.INVALID_MESSAGE,
                (joiner.events.last() as SessionEvent.SessionClosed).reason,
            )
        }

    @Test
    fun `remote SESSION_CLOSE closes gracefully`() =
        runTest {
            val (joiner, host) = handshakedPair()

            host.session.close(SessionCloseReason.HOST_ENDED)
            runCurrent()
            joiner.transport.receiveFrame(host.transport.sent.last())
            runCurrent()

            val closed = joiner.session.state.value as LinkState.Closed
            assertEquals(SessionCloseReason.HOST_ENDED, closed.reason)
            assertTrue(closed.graceful)
            assertTrue(closed.initiatedByRemote)
            assertTrue(joiner.transport.closed)

            val closedEvent = joiner.events.filterIsInstance<SessionEvent.SessionClosed>().single()
            assertEquals(SessionCloseReason.HOST_ENDED, closedEvent.reason)
        }

    @Test
    fun `local close sends SESSION_CLOSE then closes`() =
        runTest {
            val (joiner, host) = handshakedPair()

            joiner.session.close(SessionCloseReason.USER_LEFT)
            runCurrent()

            val closed = joiner.session.state.value as LinkState.Closed
            assertEquals(SessionCloseReason.USER_LEFT, closed.reason)
            assertTrue(closed.graceful)
            assertFalse(closed.initiatedByRemote)
            assertTrue(joiner.transport.closed)

            assertEquals(
                SessionMessageTypes.CLOSE,
                joiner.transport.sent
                    .last()
                    .typeName(),
            )

            // The peer processes that CLOSE off the wire.
            host.transport.receiveFrame(joiner.transport.sent.last())
            runCurrent()
            val hostClosed = host.session.state.value as LinkState.Closed
            assertEquals(SessionCloseReason.USER_LEFT, hostClosed.reason)
            assertTrue(hostClosed.initiatedByRemote)
        }

    @Test
    fun `link loss closes abruptly`() =
        runTest {
            val (joiner, _) = handshakedPair()

            joiner.transport.receiveLinkLost()
            runCurrent()

            val closed = joiner.session.state.value as LinkState.Closed
            assertEquals(null, closed.reason)
            assertFalse(closed.graceful)
            assertTrue(closed.initiatedByRemote)
        }

    @Test
    fun `unknown message type between HELLO and READY is discarded`() =
        runTest {
            val peer = TestPeer("joiner", this)
            peer.startSession(this)

            peer.transport.receiveFrame(helloEnvelope(messageId = "00000001").toCtrlFrame())
            runCurrent()

            // Handshake only half done: upper-layer traffic is discarded.
            peer.transport.receiveFrame(
                buildSessionEnvelope(
                    type = "FILE_OFFER",
                    sessionId = SESSION_ID,
                    deviceId = "dev-remote",
                    messageId = "00000002",
                    body = null,
                ).toCtrlFrame(),
            )
            runCurrent()

            assertTrue(peer.events.filterIsInstance<SessionEvent.InvalidMessageDiscarded>().isNotEmpty())
            assertEquals(LinkState.Handshaking, peer.session.state.value)
        }

    @Test
    fun `unknown message type while ACTIVE is forwarded`() =
        runTest {
            val (_, joiner) = handshakedPair()
            assertEquals(LinkState.Active, joiner.session.state.value)

            joiner.transport.receiveFrame(
                buildSessionEnvelope(
                    type = "FILE_OFFER",
                    sessionId = SESSION_ID,
                    deviceId = "dev-remote",
                    messageId = "00000050",
                    body = null,
                ).toCtrlFrame(),
            )
            runCurrent()

            val forwarded = joiner.events.last() as SessionEvent.ControlReceived
            assertEquals("FILE_OFFER", forwarded.envelope.type)
        }

    @Test
    fun `sendControl and sendData emit the frames a peer expects`() =
        runTest {
            val peer = TestPeer("host", this, role = SessionRole.HOST)
            peer.startSession(this)

            peer.session.sendData(
                ChunkHeader(UUID.fromString("11111111-2222-3333-4444-555555555555"), 3, 4),
                byteArrayOf(1, 2, 3, 4),
            )
            peer.session.sendControl(
                buildSessionEnvelope(
                    type = "FILE_OFFER",
                    sessionId = SESSION_ID,
                    deviceId = peer.session.localIdentity.deviceId,
                    messageId = peer.session.nextOutboundMid(),
                    body = null,
                ),
            )
            runCurrent()

            // Index 0 is the HELLO this peer sent when the session started.
            assertEquals(FrameType.DATA, peer.transport.sent[1].type)
            val dataPayload = peer.transport.sent[1].payload
            assertEquals(ChunkHeader.SIZE + 4, dataPayload.size)
            val header = ChunkHeader.decode(dataPayload)
            assertEquals(3L, header.chunkIndex)
            assertEquals(4, header.chunkLength)
            assertEquals("FILE_OFFER", peer.transport.sent[2].typeName())
        }

    @Test
    fun `DATA frame while ACTIVE forwards ChunkReceived`() =
        runTest {
            val (joiner, _) = handshakedPair()

            val transferId = UUID.randomUUID()
            val header = ChunkHeader(transferId, 0L, 3)
            val dataFrame = Frame(FrameType.DATA, header.encode() + byteArrayOf(1, 2, 3))
            joiner.transport.receiveFrame(dataFrame)
            runCurrent()

            val chunk = joiner.events.filterIsInstance<SessionEvent.ChunkReceived>().single()
            assertEquals(transferId, chunk.header.transferId)
            assertEquals(0L, chunk.header.chunkIndex)
            assertEquals(3, chunk.header.chunkLength)
            assertTrue(chunk.payload.contentEquals(byteArrayOf(1, 2, 3)))
        }

    @Test
    fun `short DATA payload is invalid not fatal`() =
        runTest {
            val (joiner, _) = handshakedPair()

            joiner.transport.receiveFrame(Frame(FrameType.DATA, byteArrayOf(1, 2, 3)))
            runCurrent()

            assertTrue(joiner.events.filterIsInstance<SessionEvent.InvalidMessageDiscarded>().isNotEmpty())
            assertEquals(LinkState.Active, joiner.session.state.value)
        }

    @Test
    fun `host SESSION_START marks the beam live and emits BeamStarted`() =
        runTest {
            val (joiner, host) = handshakedPair()

            host.session.sendStart()
            runCurrent()

            val start = host.transport.sent.last()
            assertEquals(SessionMessageTypes.START, start.typeName())

            joiner.transport.receiveFrame(start)
            runCurrent()

            assertTrue(joiner.session.beamLive.value)
            val started = joiner.events.filterIsInstance<SessionEvent.BeamStarted>().single()
            assertEquals("dev-host", started.hostDeviceId)
            // ACTIVE alone does not make a beam live.
            assertEquals(LinkState.Active, joiner.session.state.value)
        }

    @Test
    fun `sendStart is a no-op unless the local role is host`() =
        runTest {
            val (joiner, _) = handshakedPair()
            val before = joiner.transport.sent.size

            joiner.session.sendStart()
            runCurrent()

            assertEquals(before, joiner.transport.sent.size)
            assertFalse(joiner.session.beamLive.value)
        }

    @Test
    fun `SESSION_START from a peer is invalid and leaves the link alive`() =
        runTest {
            val (_, host) = handshakedPair()

            // The host's remote is a PEER, so START arriving there is illegal.
            host.transport.receiveFrame(startEnvelope().toCtrlFrame())
            runCurrent()

            assertFalse(host.session.beamLive.value)
            assertTrue(host.events.filterIsInstance<SessionEvent.InvalidMessageDiscarded>().isNotEmpty())
            assertEquals(LinkState.Active, host.session.state.value)
        }

    @Test
    fun `SESSION_START before handshake completion is invalid`() =
        runTest {
            val peer = TestPeer("joiner", this)
            peer.startSession(this)

            peer.transport.receiveFrame(helloEnvelope(messageId = "00000001").toCtrlFrame())
            runCurrent()
            peer.transport.receiveFrame(startEnvelope().toCtrlFrame())
            runCurrent()

            assertFalse(peer.session.beamLive.value)
            assertEquals(LinkState.Handshaking, peer.session.state.value)
        }

    @Test
    fun `duplicate SESSION_START is idempotent`() =
        runTest {
            val (joiner, host) = handshakedPair()

            host.session.sendStart()
            host.session.sendStart()
            runCurrent()

            val starts = host.transport.sent.filter { it.typeName() == SessionMessageTypes.START }
            assertEquals(2, starts.size)
            starts.forEach { joiner.transport.receiveFrame(it) }
            runCurrent()

            assertTrue(joiner.session.beamLive.value)
            assertEquals(1, joiner.events.filterIsInstance<SessionEvent.BeamStarted>().size)
        }
}
