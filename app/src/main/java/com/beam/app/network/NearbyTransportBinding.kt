package com.beam.app.network

import com.beam.app.protocol.session.NearbyByteLink
import com.beam.app.protocol.session.NearbyLinkHub
import com.beam.app.protocol.session.NearbyTransport
import com.google.android.gms.nearby.connection.ConnectionsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class NearbyTransportBinding(
    private val manager: BeamNearbyManager,
    private val scope: CoroutineScope,
) {
    val hub = NearbyLinkHub()

    init {
        manager.onPayloadFailed = { endpointId -> hub.dispatchDisconnected(endpointId) }
    }

    private inner class GmsByteLink(
        private val connection: BeamNearbyManager.Connection,
    ) : NearbyByteLink {
        override val maxPayloadSize: Int = ConnectionsClient.MAX_BYTES_DATA_SIZE
        override val incoming: Flow<NearbyByteLink.NearbyLinkEvent> = emptyFlow()

        override suspend fun sendBytes(bytes: ByteArray) = manager.sendBytes(connection, bytes)

        override fun close() = manager.disconnect(connection)
    }

    fun onEndpointConnected(endpointId: String): NearbyTransport =
        hub.transportFor(endpointId, GmsByteLink(manager.connectionFor(endpointId)), scope)

    fun onEndpointBytes(
        endpointId: String,
        bytes: ByteArray,
    ) {
        hub.dispatchBytes(endpointId, bytes)
    }

    fun onEndpointDisconnected(endpointId: String) {
        hub.dispatchDisconnected(endpointId)
    }

    fun shutdown() {
        hub.closeAll()
    }
}
