package com.beam.app.protocol.session

import com.beam.app.protocol.ChunkHeader
import com.beam.app.protocol.Frame
import com.beam.app.protocol.FrameCodecKind
import com.beam.app.protocol.FrameType
import com.beam.app.protocol.MessageEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import java.util.concurrent.atomic.AtomicBoolean

/** Identity this device presents in its SESSION_HELLO. */
data class LocalIdentity(
    val deviceId: String,
    val deviceName: String,
    val role: SessionRole,
    val sessionId: String,
    val beamCode: String,
)

/** Remote peer identity learned from their SESSION_HELLO. */
data class RemoteIdentity(
    val deviceId: String,
    val deviceName: String,
    val role: SessionRole,
)

/** Per-link session state. */
sealed interface LinkState {
    /** HELLO/READY exchange in progress. */
    data object Handshaking : LinkState

    /** Handshake complete on both sides; transfer traffic allowed. */
    data object Active : LinkState

    data class Closed(
        val reason: SessionCloseReason?,
        val graceful: Boolean,
        val initiatedByRemote: Boolean,
    ) : LinkState
}

/** Events emitted by a [LinkSession] for the layers above. */
sealed interface SessionEvent {
    /** Both READYs processed; the link is usable. */
    data class HandshakeCompleted(
        val agreedVersion: String,
        val remote: RemoteIdentity,
        val negotiatedCapabilities: Set<String>,
    ) : SessionEvent

    data class ControlReceived(
        val envelope: MessageEnvelope,
    ) : SessionEvent

    /** Chunk bytes received while ACTIVE; the transfer layer owns validation. */
    data class ChunkReceived(
        val header: ChunkHeader,
        val payload: ByteArray,
    ) : SessionEvent

    data class MalformedFrameDiscarded(
        val kind: FrameCodecKind,
        val detail: String,
    ) : SessionEvent

    data class InvalidMessageDiscarded(
        val detail: String,
    ) : SessionEvent

    data class SessionClosed(
        val reason: SessionCloseReason?,
        val graceful: Boolean,
        val initiatedByRemote: Boolean,
    ) : SessionEvent
}

class LinkSession(
    private val transport: Transport,
    private val local: LocalIdentity,
    private val scope: CoroutineScope,
    private val supportedVersions: List<String> = listOf(MessageEnvelope.PROTOCOL_VERSION),
    private val advertisedCapabilities: Set<String> = setOf(SessionCapabilities.CHUNKING),
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Handshaking)
    val state: StateFlow<LinkState> = _state

    private val _events = Channel<SessionEvent>(Channel.UNLIMITED)

    val events: ReceiveChannel<SessionEvent> = _events

    private val started = AtomicBoolean(false)
    private val closeMutex = Mutex()
    private var receiveJob: Job? = null

    @Volatile private var closed = false
    private var strikes = 0
    private var remoteHello: SessionHelloBody? = null
    private var agreedVersion: String? = null
    private var negotiatedCapabilities: Set<String> = emptySet()
    private var readyReceived = false
    private val outMid = MessageIdGenerator()
    private val inMid = MidTracker()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        receiveJob =
            scope.launch {
                sendHello()
                receiveLoop()
            }
    }

    fun close(reason: SessionCloseReason) {
        scope.launch { refuse(reason, reason.name) }
    }

    private suspend fun receiveLoop() {
        transport.incoming.collect { event -> handle(event) }
        abruptClose()
    }

    private suspend fun handle(event: TransportEvent) {
        if (closed) return
        when (event) {
            is TransportEvent.FrameReceived -> handleFrame(event.frame)
            is TransportEvent.FrameMalformed -> onMalformedFrame(event.kind, event.detail)
            TransportEvent.LinkLost -> abruptClose()
        }
    }

    private suspend fun handleFrame(frame: Frame) {
        when (frame.type) {
            FrameType.CTRL -> handleControl(frame)
            FrameType.DATA -> handleData(frame)
            FrameType.CLOSE -> finishClose(reason = null, graceful = false, initiatedByRemote = true)
        }
    }

    private suspend fun handleControl(frame: Frame) {
        val envelope =
            try {
                MessageEnvelope.decode(frame.payload.toString(Charsets.UTF_8))
            } catch (e: SerializationException) {
                onInvalidMessage("Undecodable envelope")
                return
            }
        if (envelope.messageId.isBlank()) {
            onInvalidMessage("Missing messageId")
            return
        }
        if (inMid.isDuplicate(envelope.messageId)) return
        if (envelope.deviceId.isBlank()) {
            onInvalidMessage("Missing deviceId")
            return
        }
        if (envelope.sessionId != local.sessionId) {
            refuse(SessionCloseReason.AUTH_FAILED, "sessionId mismatch")
            return
        }
        when (envelope.type) {
            SessionMessageTypes.HELLO -> handleHello(envelope)
            SessionMessageTypes.READY -> handleReady(envelope)
            SessionMessageTypes.CLOSE -> handleClose(envelope)
            else -> handleUpperLayer(envelope)
        }
    }

    private suspend fun handleHello(envelope: MessageEnvelope) {
        if (envelope.transferId != null) {
            onInvalidMessage("transferId on session-level HELLO")
            return
        }
        if (remoteHello != null) return
        val hello =
            try {
                envelope.body.decodeSessionBody(SessionHelloBody.serializer(), SessionMessageTypes.HELLO)
            } catch (e: SessionProtocolException) {
                onInvalidMessage(e.message ?: "Invalid HELLO body")
                return
            }
        if (hello.sessionId != local.sessionId || hello.beamCode != local.beamCode) {
            refuse(SessionCloseReason.AUTH_FAILED, "sessionId/beamCode mismatch")
            return
        }
        val agreed = BeamVersion.negotiate(supportedVersions, hello.supportedVersions)
        if (agreed == null) {
            refuse(SessionCloseReason.UNSUPPORTED_VERSION, "no common protocol version")
            return
        }
        agreedVersion = agreed
        negotiatedCapabilities = advertisedCapabilities.intersect(hello.capabilities.toSet())
        remoteHello = hello
        sendEnvelope(SessionMessageTypes.READY, SessionReadyBody(agreed).toJsonElement())
        maybeActivate()
    }

    private suspend fun handleReady(envelope: MessageEnvelope) {
        if (envelope.transferId != null) {
            onInvalidMessage("transferId on session-level READY")
            return
        }
        if (remoteHello == null) {
            onInvalidMessage("READY before HELLO")
            return
        }
        if (readyReceived) return
        val ready =
            try {
                envelope.body.decodeSessionBody(SessionReadyBody.serializer(), SessionMessageTypes.READY)
            } catch (e: SessionProtocolException) {
                onInvalidMessage(e.message ?: "Invalid READY body")
                return
            }
        if (ready.agreedVersion != agreedVersion) {
            onInvalidMessage("agreedVersion mismatch")
            return
        }
        readyReceived = true
        maybeActivate()
    }

    private suspend fun handleClose(envelope: MessageEnvelope) {
        if (envelope.transferId != null) {
            onInvalidMessage("transferId on session-level CLOSE")
            return
        }
        val close =
            try {
                envelope.body.decodeSessionBody(SessionCloseBody.serializer(), SessionMessageTypes.CLOSE)
            } catch (e: SessionProtocolException) {
                onInvalidMessage(e.message ?: "Invalid CLOSE body")
                return
            }
        finishClose(reason = close.reason, graceful = true, initiatedByRemote = true)
    }

    private suspend fun handleUpperLayer(envelope: MessageEnvelope) {
        if (_state.value != LinkState.Active) {
            onInvalidMessage("${envelope.type} before handshake completion")
            return
        }
        _events.trySend(SessionEvent.ControlReceived(envelope))
    }

    private suspend fun handleData(frame: Frame) {
        if (_state.value != LinkState.Active) {
            onInvalidMessage("DATA frame before handshake completion")
            return
        }
        if (frame.payload.size < ChunkHeader.SIZE) {
            onInvalidMessage("DATA payload shorter than chunk header")
            return
        }
        val header =
            try {
                ChunkHeader.decode(frame.payload)
            } catch (e: IllegalArgumentException) {
                onInvalidMessage("Invalid chunk header")
                return
            }
        val chunk = frame.payload.copyOfRange(ChunkHeader.SIZE, frame.payload.size)
        _events.trySend(SessionEvent.ChunkReceived(header, chunk))
    }

    private fun maybeActivate() {
        if (remoteHello == null || !readyReceived || _state.value != LinkState.Handshaking) return
        _state.value = LinkState.Active
        val hello = remoteHello ?: return
        _events.trySend(
            SessionEvent.HandshakeCompleted(
                agreedVersion = agreedVersion ?: MessageEnvelope.PROTOCOL_VERSION,
                remote = RemoteIdentity(hello.deviceId, hello.deviceName, hello.role),
                negotiatedCapabilities = negotiatedCapabilities,
            ),
        )
    }

    private suspend fun onMalformedFrame(
        kind: FrameCodecKind,
        detail: String,
    ) {
        _events.trySend(SessionEvent.MalformedFrameDiscarded(kind, detail))
        registerStrike()
    }

    private suspend fun onInvalidMessage(detail: String) {
        _events.trySend(SessionEvent.InvalidMessageDiscarded(detail))
        registerStrike()
    }

    private suspend fun registerStrike() {
        strikes += 1
        if (strikes >= MAX_STRIKES) {
            refuse(SessionCloseReason.INVALID_MESSAGE, "repeated malformed traffic")
        }
    }

    private suspend fun sendHello() {
        val hello =
            SessionHelloBody(
                supportedVersions = supportedVersions,
                deviceId = local.deviceId,
                deviceName = local.deviceName,
                role = local.role,
                sessionId = local.sessionId,
                capabilities = advertisedCapabilities.toList(),
                beamCode = local.beamCode,
            )
        sendEnvelope(SessionMessageTypes.HELLO, hello.toJsonElement())
    }

    private suspend fun sendEnvelope(
        type: String,
        body: kotlinx.serialization.json.JsonElement,
    ) {
        if (closed) return
        val envelope =
            buildSessionEnvelope(
                type = type,
                sessionId = local.sessionId,
                deviceId = local.deviceId,
                messageId = outMid.next(),
                body = body,
            )
        transport.send(envelope.toCtrlFrame())
    }

    private suspend fun refuse(
        reason: SessionCloseReason,
        detail: String,
    ) {
        runCatching {
            sendEnvelope(
                SessionMessageTypes.CLOSE,
                SessionCloseBody(reason, detail.take(MAX_CLOSE_DETAIL)).toJsonElement(),
            )
        }
        finishClose(reason = reason, graceful = true, initiatedByRemote = false)
    }

    private suspend fun abruptClose() {
        finishClose(reason = null, graceful = false, initiatedByRemote = true)
    }

    private suspend fun finishClose(
        reason: SessionCloseReason?,
        graceful: Boolean,
        initiatedByRemote: Boolean,
    ) {
        closeMutex.withLock {
            if (closed) return
            closed = true
        }
        _state.value = LinkState.Closed(reason, graceful, initiatedByRemote)
        transport.close()
        _events.trySend(SessionEvent.SessionClosed(reason, graceful, initiatedByRemote))
        receiveJob?.cancel()
    }

    private companion object {
        const val MAX_STRIKES = 2
        const val MAX_CLOSE_DETAIL = 200
    }
}
