package com.beam.app.protocol.session

import com.beam.app.protocol.Frame
import com.beam.app.protocol.FrameCodec
import com.beam.app.protocol.FrameCodecException
import com.beam.app.protocol.FrameCodecKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface NearbyByteLink {
    /** Maximum BYTES payload, including the transport envelope. */
    val maxPayloadSize: Int get() = NearbyFragmentCodec.DEFAULT_MAX_PAYLOAD

    /** Submits one SDK-sized fragment; submission failures must throw. */
    suspend fun sendBytes(bytes: ByteArray)

    fun close()

    sealed interface NearbyLinkEvent {
        data class BytesReceived(
            val bytes: ByteArray,
        ) : NearbyLinkEvent

        data object Disconnected : NearbyLinkEvent
    }

    val incoming: Flow<NearbyLinkEvent>
}

class NearbyTransport(
    private val link: NearbyByteLink,
    scope: CoroutineScope,
) : Transport {
    private val events = Channel<TransportEvent>(BUFFER_CAPACITY)
    override val incoming: Flow<TransportEvent> = events.receiveAsFlow()
    private val closed = AtomicBoolean(false)
    private val outbound = Mutex()
    private val inboundLock = Any()
    private val reassembler = NearbyFragmentCodec.Reassembler(link.maxPayloadSize)
    private var sequence = 0L
    private var pumpJob: Job? = null

    init {
        pumpJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                link.incoming.collect { event ->
                    when (event) {
                        is NearbyByteLink.NearbyLinkEvent.BytesReceived -> onInboundBytes(event.bytes)
                        NearbyByteLink.NearbyLinkEvent.Disconnected -> onInboundLinkLost()
                    }
                }
            }
    }

    override suspend fun send(frame: Frame) {
        outbound.withLock {
            if (closed.get()) return
            val encoded =
                try {
                    FrameCodec.encode(frame)
                } catch (e: FrameCodecException) {
                    synchronized(inboundLock) {
                        emitEvent(TransportEvent.FrameMalformed(FrameCodecKind.INVALID, e.message ?: "encode failed"))
                    }
                    return
                }
            try {
                check(sequence < Long.MAX_VALUE) { "Frame sequence exhausted" }
                var offset = 0
                while (offset < encoded.size) {
                    if (closed.get()) return
                    val fragment = NearbyFragmentCodec.fragment(encoded, sequence, offset, link.maxPayloadSize)
                    link.sendBytes(fragment)
                    offset += fragment.size - NearbyFragmentCodec.HEADER_SIZE
                }
                sequence++
            } catch (e: CancellationException) {
                // A partially submitted frame cannot be resumed on this connection.
                onInboundLinkLost()
                throw e
            } catch (_: Exception) {
                onInboundLinkLost()
            }
        }
    }

    val isClosed: Boolean get() = closed.get()

    override fun close() {
        synchronized(inboundLock) {
            if (!closed.compareAndSet(false, true)) return
            reassembler.clear()
            events.cancel()
            pumpJob?.cancel()
            link.close()
        }
    }

    fun onInboundBytes(bytes: ByteArray) {
        synchronized(inboundLock) {
            if (closed.get()) return
            try {
                reassembler.accept(bytes) { complete ->
                    if (!closed.get()) {
                        val event =
                            try {
                                TransportEvent.FrameReceived(FrameCodec.decode(complete))
                            } catch (e: FrameCodecException) {
                                TransportEvent.FrameMalformed(e.kind, e.message ?: "malformed frame")
                            }
                        emitEvent(event)
                    }
                }
            } catch (_: IllegalArgumentException) {
                onInboundLinkLost()
            }
        }
    }

    fun onInboundLinkLost() {
        synchronized(inboundLock) {
            if (closed.getAndSet(true)) return
            reassembler.clear()
            pumpJob?.cancel()
            // Reserve delivery of the terminal event even when the bounded queue overflowed.
            while (events.tryReceive().isSuccess) { }
            check(events.trySend(TransportEvent.LinkLost).isSuccess)
            events.close()
            link.close()
        }
    }

    private fun emitEvent(event: TransportEvent) {
        if (!closed.get() && events.trySend(event).isFailure) onInboundLinkLost()
    }

    private companion object {
        const val BUFFER_CAPACITY = 16
    }
}

/** Per-endpoint transport registry. Reconnection always starts with a fresh framing state. */
class NearbyLinkHub {
    private val transports = ConcurrentHashMap<String, NearbyTransport>()

    fun transportForOrNull(endpointId: String): NearbyTransport? = transports[endpointId]

    @Synchronized
    fun transportFor(
        endpointId: String,
        link: NearbyByteLink,
        scope: CoroutineScope,
    ): NearbyTransport {
        transports[endpointId]?.let { if (!it.isClosed) return it }
        return NearbyTransport(link, scope).also { transports[endpointId] = it }
    }

    fun dispatchBytes(
        endpointId: String,
        bytes: ByteArray,
    ) {
        transports[endpointId]?.onInboundBytes(bytes)
    }

    fun dispatchDisconnected(endpointId: String) {
        transports.remove(endpointId)?.onInboundLinkLost()
    }

    fun closeAll() {
        transports.values.forEach(NearbyTransport::close)
        transports.clear()
    }
}
