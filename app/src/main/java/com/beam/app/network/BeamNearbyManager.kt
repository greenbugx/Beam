package com.beam.app.network

import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BeamNearbyManager(
    context: Context,
) {
    private val connectionsClient =
        Nearby.getConnectionsClient(context.applicationContext)

    var onConnected: ((String) -> Unit)? = null
    var onConnectionFailed: ((String) -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null

    internal class Connection(
        val endpointId: String,
    )

    private val connections = ConcurrentHashMap<String, Connection>()

    internal fun connectionFor(endpointId: String): Connection =
        connections[endpointId] ?: error("No connected endpoint: $endpointId")

    var onEndpointFound: ((String, String) -> Unit)? = null
    var onEndpointLost: ((String) -> Unit)? = null

    var onBytesReceived: ((String, ByteArray) -> Unit)? = null
    var onPayloadFailed: ((String) -> Unit)? = null

    private fun payloadCallback(connection: Connection) =
        object : PayloadCallback() {
            override fun onPayloadReceived(
                endpointId: String,
                payload: Payload,
            ) {
                if (connections[endpointId] !== connection) return
                if (payload.type != Payload.Type.BYTES) return
                val bytes = payload.asBytes() ?: return
                onBytesReceived?.invoke(endpointId, bytes)
            }

            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate,
            ) {
                if (connections[endpointId] !== connection) return
                if (update.status == PayloadTransferUpdate.Status.FAILURE ||
                    update.status == PayloadTransferUpdate.Status.CANCELED
                ) {
                    onPayloadFailed?.invoke(endpointId)
                    disconnect(connection)
                }
            }
        }

    private val connectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(
                endpointId: String,
                connectionInfo: ConnectionInfo,
            ) {
                Log.d(
                    TAG,
                    "Connection initiated: $endpointId (${connectionInfo.endpointName})",
                )

                val connection = Connection(endpointId)
                connections.put(endpointId, connection)?.let { onPayloadFailed?.invoke(endpointId) }
                connectionsClient.acceptConnection(endpointId, payloadCallback(connection))
            }

            override fun onConnectionResult(
                endpointId: String,
                result: ConnectionResolution,
            ) {
                if (result.status.statusCode == CommonStatusCodes.SUCCESS) {
                    Log.d(TAG, "Connected: $endpointId")
                    onConnected?.invoke(endpointId)
                } else {
                    Log.d(
                        TAG,
                        "Connection failed: $endpointId " +
                            "code=${result.status.statusCode}",
                    )

                    connections.remove(endpointId)
                    onConnectionFailed?.invoke(endpointId)
                }
            }

            override fun onDisconnected(endpointId: String) {
                Log.d(TAG, "Disconnected: $endpointId")
                connections.remove(endpointId)
                onDisconnected?.invoke(endpointId)
            }
        }

    private val endpointDiscoveryCallback =
        object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(
                endpointId: String,
                info: DiscoveredEndpointInfo,
            ) {
                Log.d(
                    TAG,
                    "Endpoint found: $endpointId / ${info.endpointName}",
                )

                onEndpointFound?.invoke(
                    endpointId,
                    info.endpointName,
                )
            }

            override fun onEndpointLost(endpointId: String) {
                Log.d(TAG, "Endpoint lost: $endpointId")
                onEndpointLost?.invoke(endpointId)
            }
        }

    fun startAdvertising(endpointName: String) {
        Log.d(TAG, "Starting advertising as $endpointName")

        val options =
            AdvertisingOptions
                .Builder()
                .setStrategy(STRATEGY)
                .build()

        connectionsClient
            .startAdvertising(
                endpointName,
                SERVICE_ID,
                connectionLifecycleCallback,
                options,
            ).addOnSuccessListener {
                Log.d(TAG, "Advertising started")
            }.addOnFailureListener { error ->
                Log.e(TAG, "Failed to start advertising", error)
            }
    }

    fun startDiscovery() {
        Log.d(TAG, "Starting discovery...")

        val options =
            DiscoveryOptions
                .Builder()
                .setStrategy(STRATEGY)
                .build()

        connectionsClient
            .startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                options,
            ).addOnSuccessListener {
                Log.d(TAG, "Discovery started")
            }.addOnFailureListener { error ->
                Log.e(TAG, "Failed to start discovery", error)
            }
    }

    fun requestConnection(
        endpointId: String,
        localEndpointName: String,
    ) {
        Log.d(TAG, "Requesting connection to $endpointId")

        connectionsClient
            .requestConnection(
                localEndpointName,
                endpointId,
                connectionLifecycleCallback,
            ).addOnSuccessListener {
                Log.d(TAG, "Connection request sent: $endpointId")
            }.addOnFailureListener { error ->
                Log.e(
                    TAG,
                    "Failed to request connection: $endpointId",
                    error,
                )
            }
    }

    internal suspend fun sendBytes(
        connection: Connection,
        bytes: ByteArray,
    ) {
        check(connections[connection.endpointId] === connection) { "Connection replaced" }
        require(bytes.size <= ConnectionsClient.MAX_BYTES_DATA_SIZE)
        suspendCancellableCoroutine<Unit> { continuation ->
            connectionsClient
                .sendPayload(connection.endpointId, Payload.fromBytes(bytes))
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }.addOnCanceledListener { continuation.cancel() }
        }
    }

    internal fun disconnect(connection: Connection) {
        if (connections.remove(connection.endpointId, connection)) {
            connectionsClient.disconnectFromEndpoint(connection.endpointId)
        }
    }

    /** Tears down the connection to [endpointId]. */
    fun disconnectFromEndpoint(endpointId: String) {
        Log.d(TAG, "Disconnecting from $endpointId")
        connections[endpointId]?.let(::disconnect)
    }

    fun stop() {
        Log.d(TAG, "Stopping Nearby Connections")

        connections.clear()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
    }

    companion object {
        private const val TAG = "BeamNearby"

        const val SERVICE_ID = "com.beam.app"

        private val STRATEGY = Strategy.P2P_STAR
    }
}
