package com.beam.app.protocol.session

import com.beam.app.protocol.Frame
import com.beam.app.protocol.FrameCodec
import com.beam.app.protocol.FrameCodecException
import com.beam.app.protocol.FrameCodecKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface NearbyByteLink {
    /** Sends one wire buffer to the remote end. */
    suspend fun sendBytes(bytes: ByteArray)

    /** Tears the underlying connection down. */
    fun close()

    /** Events raised by the underlying link. */
    sealed interface NearbyLinkEvent {
        /** One complete wire buffer arrived from the remote end. */
        data class BytesReceived(
            val bytes: ByteArray,
        ) : NearbyLinkEvent

        data object Disconnected : NearbyLinkEvent
    }

    val incoming: Flow<NearbyLinkEvent>
}

/**
 * [Transport] over one [NearbyByteLink]: one frame per wire buffer.
 *
 * Outbound frames are encoded with [FrameCodec] and handed to the link as a
 * single buffer; each complete inbound buffer is decoded back into a frame.
 * Malformed payloads surface as [TransportEvent.FrameMalformed] (the session
 * layer's strike policy decides what to do with them) and link loss as
 * [TransportEvent.LinkLost]. After [close], sends are silent no-ops and no
 * further events are emitted.
 */
class NearbyTransport(
    private val link: NearbyByteLink,
    private val scope: CoroutineScope,
) : Transport {
    private val _incoming = MutableSharedFlow<TransportEvent>(replay = 1, extraBufferCapacity = BUFFER_CAPACITY)
    override val incoming: Flow<TransportEvent> = _incoming

    private val closed = AtomicBoolean(false)

    /** Collects link events for this transport's lifetime. */
    private val pumpJob: Job

    init {
        pumpJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                link.incoming.collect { event ->
                    when (event) {
                        is NearbyByteLink.NearbyLinkEvent.BytesReceived -> onBytes(event.bytes)
                        NearbyByteLink.NearbyLinkEvent.Disconnected -> onLinkLost()
                    }
                }
            }
    }

    override suspend fun send(frame: Frame) {
        if (closed.get()) return
        val encoded =
            try {
                FrameCodec.encode(frame)
            } catch (e: FrameCodecException) {
                emitEvent(TransportEvent.FrameMalformed(FrameCodecKind.INVALID, e.message ?: "encode failed"))
                return
            }
        if (closed.get()) return
        link.sendBytes(encoded)
    }

    /** True once this transport has been closed or its link reported lost. */
    val isClosed: Boolean get() = closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pumpJob.cancel()
        link.close()
    }

    fun onInboundBytes(bytes: ByteArray) {
        if (closed.get()) return
        val frame =
            try {
                FrameCodec.decode(bytes)
            } catch (e: FrameCodecException) {
                emitEvent(TransportEvent.FrameMalformed(e.kind, e.message ?: "malformed frame"))
                return
            }
        emitEvent(TransportEvent.FrameReceived(frame))
    }

    fun onInboundLinkLost() {
        if (closed.getAndSet(true)) return
        pumpJob.cancel()
        emitEvent(TransportEvent.LinkLost)
    }

    private fun onBytes(bytes: ByteArray) = onInboundBytes(bytes)

    private fun onLinkLost() = onInboundLinkLost()

    private fun emitEvent(event: TransportEvent) {
        _incoming.tryEmit(event)
    }

    private companion object {
        const val BUFFER_CAPACITY = 4096
    }
}

/**
 * Routes raw Nearby traffic to the [NearbyTransport] for its endpoint.
 *
 * The production binding owns exactly one live endpoint at a time (the room
 * has a single peer), so the hub holds one transport; the per-endpoint key is
 * kept so a future multi-peer room only changes the binding, not this logic.
 * After [dispatchDisconnected], a reconnect for the same endpoint id gets a
 * fresh transport.
 */
class NearbyLinkHub {
    private val transports = ConcurrentHashMap<String, NearbyTransport>()

    /** Current transport for [endpointId], or null when none is registered. */
    fun transportForOrNull(endpointId: String): NearbyTransport? = transports[endpointId]

    fun transportFor(
        endpointId: String,
        link: NearbyByteLink,
        scope: CoroutineScope,
    ): NearbyTransport {
        transports[endpointId]?.let { existing ->
            if (!existing.isClosed) return existing
            transports.remove(endpointId, existing)
        }
        val created = NearbyTransport(link, scope)
        val winner = transports.putIfAbsent(endpointId, created) ?: created
        if (winner !== created) created.close()
        return winner
    }

    /** Delivers one wire buffer to the transport registered for [endpointId]. */
    fun dispatchBytes(
        endpointId: String,
        bytes: ByteArray,
    ) {
        transports[endpointId]?.onInboundBytes(bytes)
    }

    /** Ends the link for [endpointId], emitting [TransportEvent.LinkLost]. */
    fun dispatchDisconnected(endpointId: String) {
        val transport = transports.remove(endpointId) ?: return
        transport.onInboundLinkLost()
    }

    /** Closes every registered transport. */
    fun closeAll() {
        transports.values.forEach(NearbyTransport::close)
        transports.clear()
    }
}
