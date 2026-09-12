package com.beam.app.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.beam.app.network.BeamNearbyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BeamSessionState {
    Idle,
    Advertising,
    Searching,
    BeamFound,
    Connecting,
    Connected,
    Failed,
}

data class BeamSessionUiState(
    val state: BeamSessionState = BeamSessionState.Idle,
    val endpointId: String? = null,
    val endpointName: String? = null,
    val message: String? = null,
)

class BeamSessionViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _uiState =
        MutableStateFlow(BeamSessionUiState())

    val uiState: StateFlow<BeamSessionUiState> =
        _uiState.asStateFlow()

    private val nearbyManager =
        BeamNearbyManager(
            application.applicationContext,
        )

    init {
        nearbyManager.onEndpointFound = { endpointId, endpointName ->

            _uiState.value =
                BeamSessionUiState(
                    state = BeamSessionState.BeamFound,
                    endpointId = endpointId,
                    endpointName = endpointName,
                    message = "Beam found",
                )

            nearbyManager.requestConnection(endpointId)

            _uiState.value =
                _uiState.value.copy(
                    state = BeamSessionState.Connecting,
                    message = "Connecting...",
                )
        }

        nearbyManager.onConnected = { endpointId ->

            _uiState.value =
                _uiState.value.copy(
                    state = BeamSessionState.Connected,
                    endpointId = endpointId,
                    message = "Connected",
                )

            nearbyManager.sendMessage(
                endpointId = endpointId,
                message = "HELLO FROM BEAM",
            )
        }

        nearbyManager.onMessageReceived = { _, message ->

            _uiState.value =
                _uiState.value.copy(
                    message = message,
                )
        }

        nearbyManager.onDisconnected = { endpointId ->

            _uiState.value =
                BeamSessionUiState(
                    state = BeamSessionState.Failed,
                    endpointId = endpointId,
                    message = "Disconnected",
                )
        }

        nearbyManager.onConnectionFailed = { endpointId ->

            _uiState.value =
                BeamSessionUiState(
                    state = BeamSessionState.Failed,
                    endpointId = endpointId,
                    message = "Connection failed",
                )
        }

        nearbyManager.onEndpointLost = { endpointId ->

            if (
                _uiState.value.endpointId == endpointId &&
                _uiState.value.state != BeamSessionState.Connected
            ) {
                _uiState.value =
                    BeamSessionUiState(
                        state = BeamSessionState.Searching,
                        message = "Searching for Beams...",
                    )
            }
        }
    }

    fun createBeam() {
        _uiState.value =
            BeamSessionUiState(
                state = BeamSessionState.Advertising,
                message = "Waiting for people...",
            )

        nearbyManager.startAdvertising()
    }

    fun joinBeam() {
        _uiState.value =
            BeamSessionUiState(
                state = BeamSessionState.Searching,
                message = "Searching for Beams...",
            )

        nearbyManager.startDiscovery()
    }

    fun setPermissionDenied() {
        _uiState.value =
            BeamSessionUiState(
                state = BeamSessionState.Failed,
                message = "Nearby permission required",
            )
    }

    fun stopSession() {
        nearbyManager.stop()

        _uiState.value =
            BeamSessionUiState(
                state = BeamSessionState.Idle,
            )
    }

    override fun onCleared() {
        nearbyManager.stop()
        super.onCleared()
    }
}
