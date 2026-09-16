package com.beam.app.protocol.session

import com.beam.app.protocol.Frame
import com.beam.app.protocol.FrameCodecKind
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeTransport(
    private val afterSend: suspend (Frame) -> Unit = {},
) : Transport {
    override val incoming = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 4096)
    private val sendLog = mutableListOf<Frame>()
    var closed = false
        private set

    val sent: List<Frame> get() = sendLog.toList()

    override suspend fun send(frame: Frame) {
        sendLog += frame
        afterSend(frame)
    }

    override fun close() {
        closed = true
    }

    /** Delivers an event from the "wire" as if the peer produced it. */
    suspend fun receive(event: TransportEvent) {
        incoming.emit(event)
    }

    suspend fun receiveFrame(frame: Frame) {
        receive(TransportEvent.FrameReceived(frame))
    }

    suspend fun receiveMalformed(
        kind: FrameCodecKind = FrameCodecKind.TRUNCATED,
        detail: String = "test malformed bytes",
    ) {
        receive(TransportEvent.FrameMalformed(kind, detail))
    }

    suspend fun receiveLinkLost() {
        receive(TransportEvent.LinkLost)
    }
}
