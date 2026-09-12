package com.beam.app.network

import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

class BeamNearbyManager(
    context: Context,
) {
    companion object {
        private const val TAG = "BeamNearby"

        const val SERVICE_ID = "com.beam.app"

        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    private val connectionsClient =
        Nearby.getConnectionsClient(context.applicationContext)

    var onConnected: ((String) -> Unit)? = null
    var onConnectionFailed: ((String) -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null

    var onEndpointFound: ((String, String) -> Unit)? = null
    var onEndpointLost: ((String) -> Unit)? = null

    var onMessageReceived: ((String, String) -> Unit)? = null

    private val payloadCallback =
        object : PayloadCallback() {
            override fun onPayloadReceived(
                endpointId: String,
                payload: Payload,
            ) {
                val bytes = payload.asBytes() ?: return

                val message = bytes.decodeToString()

                Log.d(
                    TAG,
                    "Payload received from $endpointId: $message",
                )

                onMessageReceived?.invoke(endpointId, message)
            }

            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate,
            ) {
                // File transfer progress will be implemented later.
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

                connectionsClient.acceptConnection(
                    endpointId,
                    payloadCallback,
                )
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

                    onConnectionFailed?.invoke(endpointId)
                }
            }

            override fun onDisconnected(endpointId: String) {
                Log.d(TAG, "Disconnected: $endpointId")
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

    fun sendMessage(
        endpointId: String,
        message: String,
    ) {
        Log.d(
            TAG,
            "Sending payload to $endpointId: $message",
        )

        connectionsClient
            .sendPayload(
                endpointId,
                Payload.fromBytes(message.encodeToByteArray()),
            ).addOnFailureListener { error ->
                Log.e(
                    TAG,
                    "Failed to send payload to $endpointId",
                    error,
                )
            }
    }

    fun stop() {
        Log.d(TAG, "Stopping Nearby Connections")

        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
    }
}
