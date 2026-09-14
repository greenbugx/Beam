package com.beam.app.protocol.session

import com.beam.app.protocol.Frame
import com.beam.app.protocol.FrameCodecKind
import kotlinx.coroutines.flow.Flow

interface Transport {
    /** Sends one encoded frame to the remote end of this link. */
    suspend fun send(frame: Frame)

    val incoming: Flow<TransportEvent>

    /** Tears the underlying connection down. Idempotent. */
    fun close()
}

/** Events surfaced by a [Transport]. */
sealed interface TransportEvent {
    /** A complete, well-formed frame. */
    data class FrameReceived(
        val frame: Frame,
    ) : TransportEvent

    /** Bytes that failed frame decoding. */
    data class FrameMalformed(
        val kind: FrameCodecKind,
        val detail: String,
    ) : TransportEvent

    /** The underlying connection was lost. */
    data object LinkLost : TransportEvent
}
