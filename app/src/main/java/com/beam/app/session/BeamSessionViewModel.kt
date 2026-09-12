package com.beam.app.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.beam.app.network.BeamNearbyManager
import com.beam.app.util.BeamCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BeamSessionState {
    Idle,
    EnteringCode,
    Creating,
    Advertising,
    Discovering,
    BeamFound,
    Connecting,
    Connected,
    Failed,
}

data class DiscoveredBeam(
    val endpointId: String,
    val name: String,
    val code: String? = null,
)

data class BeamSessionUiState(
    val state: BeamSessionState = BeamSessionState.Idle,
    val room: BeamRoom? = null,
    val joinCode: String? = null,
    val discoveredBeams: List<DiscoveredBeam> = emptyList(),
    val connectedPeers: List<BeamPeer> = emptyList(),
    val selectedEndpointId: String? = null,
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
        nearbyManager.onConnected = { endpointId ->

            val currentPeers = _uiState.value.connectedPeers

            if (currentPeers.none { it.endpointId == endpointId }) {
                val beamName =
                    _uiState.value.discoveredBeams
                        .firstOrNull { it.endpointId == endpointId }
                        ?.name

                _uiState.value =
                    _uiState.value.copy(
                        state = BeamSessionState.Connected,
                        connectedPeers =
                            currentPeers +
                                BeamPeer(
                                    endpointId = endpointId,
                                    endpointName =
                                        beamName ?: "Beam Device",
                                    connected = true,
                                ),
                        message = "Connected",
                    )
            }

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
                _uiState.value.copy(
                    connectedPeers =
                        _uiState.value.connectedPeers
                            .filterNot { it.endpointId == endpointId },
                    message = "Disconnected",
                )
        }

        nearbyManager.onConnectionFailed = { endpointId ->

            _uiState.value =
                _uiState.value.copy(
                    state = BeamSessionState.Failed,
                    connectedPeers =
                        _uiState.value.connectedPeers
                            .filterNot { it.endpointId == endpointId },
                    message = "Connection failed",
                )
        }

        nearbyManager.onEndpointFound = { endpointId, endpointName ->
            val joinCode = _uiState.value.joinCode

            // A joiner with a code auto-connects only to a beam advertising
            // exactly that code. Other nearby beams are still listed so the
            // user can connect manually.
            val parsedCode =
                BeamCode.codeFromEndpointName(endpointName)

            val isCodeMatch = joinCode != null && parsedCode == joinCode

            val currentBeams = _uiState.value.discoveredBeams

            if (currentBeams.none { it.endpointId == endpointId }) {
                _uiState.value =
                    _uiState.value.copy(
                        state = BeamSessionState.BeamFound,
                        discoveredBeams =
                            currentBeams +
                                DiscoveredBeam(
                                    endpointId = endpointId,
                                    name = endpointName,
                                    code = parsedCode,
                                ),
                        message =
                            if (isCodeMatch) {
                                "Beam $joinCode found"
                            } else if (joinCode != null) {
                                "Beam $joinCode not found yet"
                            } else {
                                "Beams nearby"
                            },
                    )
            }

            if (isCodeMatch &&
                _uiState.value.selectedEndpointId == null
            ) {
                joinBeam(endpointId)
            }
        }

        nearbyManager.onEndpointLost = { endpointId ->
            val previousBeams = _uiState.value.discoveredBeams

            _uiState.value =
                _uiState.value.copy(
                    discoveredBeams =
                        previousBeams.filterNot { it.endpointId == endpointId },
                )

            // The same host can appear as several endpoints (one per
            // transport). If the one we were connecting to dropped mid-connect,
            // fall back to another beam advertising the same code.
            if (_uiState.value.selectedEndpointId == endpointId &&
                _uiState.value.state == BeamSessionState.Connecting
            ) {
                val fallback =
                    _uiState.value.discoveredBeams.firstOrNull {
                        it.code != null && it.code == _uiState.value.joinCode
                    }

                if (fallback != null) {
                    joinBeam(fallback.endpointId)
                } else {
                    _uiState.value =
                        _uiState.value.copy(
                            selectedEndpointId = null,
                            state = BeamSessionState.BeamFound,
                            message = "Beam lost. Tap a nearby beam to connect.",
                        )
                }
            }
        }
    }

    fun createBeam() {
        val room =
            BeamRoom(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                name = "My Beam",
                code = BeamCode.generateCode(),
            )

        _uiState.value =
            BeamSessionUiState(
                state = BeamSessionState.Creating,
                room = room,
                message = "Starting Beam...",
            )

        nearbyManager.startAdvertising(
            endpointName = "Beam · ${room.code}",
        )

        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.Advertising,
                message = "Waiting for people...",
            )
    }

    fun openCodeEntry() {
        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.EnteringCode,
                message = null,
            )
    }

    fun findBeam(code: String) {
        val sanitized = BeamCode.sanitize(code)

        if (!BeamCode.isValid(sanitized)) {
            _uiState.value =
                _uiState.value.copy(
                    state = BeamSessionState.EnteringCode,
                    message = "Enter a ${BeamCode.CODE_LENGTH}-character Beam code",
                )
            return
        }

        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.Discovering,
                joinCode = sanitized,
                message = "Looking for Beam $sanitized...",
            )

        nearbyManager.startDiscovery()
    }

    fun joinBeam(endpointId: String) {
        val beam =
            _uiState.value.discoveredBeams
                .firstOrNull { it.endpointId == endpointId }
                ?: return

        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.Connecting,
                selectedEndpointId = endpointId,
                message = "Connecting...",
            )

        nearbyManager.requestConnection(
            endpointId = endpointId,
            localEndpointName = "Beam Device",
        )
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
