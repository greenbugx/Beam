package com.beam.app.network

import com.beam.app.protocol.session.NearbyByteLink
import com.beam.app.protocol.session.NearbyLinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Binds the GMS Nearby connection to the protocol layer's [NearbyLinkHub].
 *
 * Thin shell by design: all framing, demux, and lifecycle logic lives in the
 * JVM-testable seam ([NearbyTransport] / [NearbyLinkHub])
 *
 * This class only adapts callback/threading shapes.
 */
class NearbyTransportBinding(
    private val manager: BeamNearbyManager,
    private val scope: CoroutineScope,
) {
    val hub = NearbyLinkHub()

    private var link: GmsByteLink? = null

    /** The one live byte link, backed by the manager's single connection. */
    private inner class GmsByteLink : NearbyByteLink {
        override val incoming = MutableSharedFlow<NearbyByteLink.NearbyLinkEvent>(extraBufferCapacity = 64)

        override suspend fun sendBytes(bytes: ByteArray) {
            val endpointId = manager.connectedEndpointId ?: error("No connected endpoint")
            manager.sendBytes(endpointId, bytes)
        }

        override fun close() {
            manager.connectedEndpointId?.let { manager.disconnectFromEndpoint(it) }
        }
    }

    /** Wires a newly connected endpoint into the hub which is safe to re-invoke. */
    fun onEndpointConnected(endpointId: String) {
        val current = link ?: GmsByteLink().also { link = it }
        hub.transportFor(endpointId, current, scope)
    }

    /** Routes one inbound wire buffer to the endpoint's transport. */
    fun onEndpointBytes(
        endpointId: String,
        bytes: ByteArray,
    ) {
        hub.dispatchBytes(endpointId, bytes)
    }

    /** Ends the endpoint's transport with a [com.beam.app.protocol.session.TransportEvent.LinkLost]. */
    fun onEndpointDisconnected(endpointId: String) {
        link = null
        hub.dispatchDisconnected(endpointId)
    }

    /** Stops every transport and releases the GMS connection. */
    fun shutdown() {
        hub.closeAll()
        link?.close()
        link = null
    }
}
