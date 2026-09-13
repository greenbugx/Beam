package com.beam.app.session

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beam.app.network.BeamNearbyManager
import com.beam.app.util.BeamCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

enum class BeamSessionState {
    Idle,
    EnteringCode,
    Creating,
    Advertising,
    Discovering,
    BeamFound,
    Connecting,
    Connected,
    Sharing,
    NearbyBeams,
    EnteringNearbyCode,
    Failed,
}

data class DiscoveredBeam(
    val endpointId: String,
    val name: String,
    val code: String? = null,
    val displayName: String? = null,
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

    private val _selectedFiles =
        MutableStateFlow<List<BeamSelectedFile>>(emptyList())

    val uiState: StateFlow<BeamSessionUiState> =
        _uiState.asStateFlow()

    val selectedFiles: StateFlow<List<BeamSelectedFile>> =
        _selectedFiles.asStateFlow()

    /**
     * Workspace view of the session: peers, shared files, transfers.
     * Derived from the session state and the files picked through the
     * Storage Access Framework, so the files UI stays independent of the
     * transfer engine (TODO: transfer protocol lands later in M3).
     */
    val filesUiState: StateFlow<BeamFilesUiState> =
        combine(
            _uiState,
            _selectedFiles,
        ) { session, selectedFiles ->
            BeamFilesUiState(
                isHost = session.room != null,
                beamName = session.room?.name ?: session.joinCode,
                beamCode = session.room?.code ?: session.joinCode,
                connectedPeers = session.connectedPeers,
                sharedFiles =
                    selectedFiles.map { selected ->
                        SharedFile(
                            id = selected.id,
                            name = selected.name,
                            sizeBytes = selected.sizeBytes.coerceAtLeast(0),
                            mimeType = selected.mimeType,
                            senderName = "You",
                        )
                    },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BeamFilesUiState(),
        )

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
                        state =
                            if (_uiState.value.room != null) {
                                _uiState.value.state
                            } else {
                                BeamSessionState.Connected
                            },
                        connectedPeers =
                            currentPeers +
                                BeamPeer(
                                    endpointId = endpointId,
                                    endpointName =
                                        beamName ?: "Beam Device",
                                    connected = true,
                                ),
                        message = null,
                    )

                if (_uiState.value.state == BeamSessionState.Sharing) {
                    nearbyManager.sendMessage(
                        endpointId,
                        MSG_BEAM_STARTED,
                    )
                }
            }
        }

        // TODO:
        // Incoming payloads are not surfaced in UI state. Raw wire messages
        // will be parsed by the transfer protocol (M3) before anything is
        // shown to the user.
        nearbyManager.onMessageReceived = { _, message ->
            // Only joiners react to the start signal; the host is the
            // one broadcasting it.
            if (message == MSG_BEAM_STARTED && _uiState.value.room == null) {
                _uiState.value =
                    _uiState.value.copy(
                        state = BeamSessionState.Sharing,
                        message = null,
                    )
            }

            // The host leaving destroys the beam for everyone in it.
            if (message == MSG_BEAM_CLOSED && _uiState.value.room == null) {
                destroyBeam(message = "Beam closed")
            }
        }

        nearbyManager.onDisconnected = { endpointId ->
            val current = _uiState.value

            val wasConnected =
                current.connectedPeers.any { it.endpointId == endpointId }

            if (wasConnected) {
                val remaining =
                    current.connectedPeers
                        .filterNot { it.endpointId == endpointId }

                _uiState.value =
                    if (current.room == null &&
                        remaining.isEmpty() &&
                        current.state != BeamSessionState.Idle
                    ) {
                        BeamSessionUiState(
                            state = BeamSessionState.Idle,
                            message = "Disconnected",
                        )
                    } else {
                        current.copy(
                            connectedPeers = remaining,
                            message =
                                if (current.state == BeamSessionState.Idle) {
                                    null
                                } else {
                                    "Disconnected"
                                },
                        )
                    }
            }
        }

        nearbyManager.onConnectionFailed = { endpointId ->
            val isHost = _uiState.value.room != null

            _uiState.value =
                _uiState.value.copy(
                    state =
                        if (isHost) {
                            _uiState.value.state
                        } else {
                            BeamSessionState.Failed
                        },
                    connectedPeers =
                        _uiState.value.connectedPeers
                            .filterNot { it.endpointId == endpointId },
                    message = if (isHost) null else "Cannot connect",
                )
        }

        nearbyManager.onEndpointFound = { endpointId, endpointName ->
            val joinCode = _uiState.value.joinCode

            // A joiner with a code auto-connects only to a beam advertising
            // exactly that code.
            val parsedCode =
                BeamCode.codeFromEndpointName(endpointName)

            val isCodeMatch = joinCode != null && parsedCode == joinCode

            val currentBeams = _uiState.value.discoveredBeams

            if (currentBeams.none { it.endpointId == endpointId }) {
                val browsingNearby =
                    _uiState.value.state == BeamSessionState.NearbyBeams

                val displayName =
                    "Beam ${endpointId.takeLast(4).uppercase()}"

                _uiState.value =
                    _uiState.value.copy(
                        state =
                            when {
                                browsingNearby -> BeamSessionState.NearbyBeams
                                isCodeMatch -> BeamSessionState.BeamFound
                                else -> BeamSessionState.Discovering
                            },
                        discoveredBeams =
                            currentBeams +
                                DiscoveredBeam(
                                    endpointId = endpointId,
                                    name = endpointName,
                                    code = parsedCode,
                                    displayName = displayName,
                                ),
                        message = null,
                    )
            }

            if (isCodeMatch &&
                (
                    _uiState.value.selectedEndpointId == null ||
                        _uiState.value.selectedEndpointId == endpointId
                )
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
                            state = BeamSessionState.Discovering,
                            message = "Looking for Beam ${_uiState.value.joinCode ?: ""}...",
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
                message = null,
            )

        nearbyManager.startAdvertising(
            endpointName = "Beam · ${room.code}",
        )

        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.Advertising,
                message = null,
            )
    }

    fun startBeam() {
        if (_uiState.value.room == null) {
            return
        }

        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.Sharing,
                message = null,
            )

        broadcastBeamStarted()
    }

    private fun broadcastBeamStarted() {
        _uiState.value.connectedPeers.forEach { peer ->
            nearbyManager.sendMessage(
                peer.endpointId,
                MSG_BEAM_STARTED,
            )
        }
    }

    fun openCodeEntry() {
        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.EnteringCode,
                message = null,
            )
    }

    fun openNearbyBeams() {
        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.NearbyBeams,
                joinCode = null,
                selectedEndpointId = null,
                message = null,
            )

        nearbyManager.startDiscovery()
    }

    fun openCodeEntryFor(endpointId: String) {
        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.EnteringNearbyCode,
                selectedEndpointId = endpointId,
                message = "Enter the code for this Beam",
            )
    }

    fun cancelNearbyCodeEntry() {
        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.NearbyBeams,
                selectedEndpointId = null,
                message = null,
            )
    }

    fun findBeam(code: String) {
        val sanitized = BeamCode.sanitize(code)

        val codeEntryState =
            when (_uiState.value.state) {
                BeamSessionState.EnteringNearbyCode -> BeamSessionState.EnteringNearbyCode
                else -> BeamSessionState.EnteringCode
            }

        if (!BeamCode.isValid(sanitized)) {
            _uiState.value =
                _uiState.value.copy(
                    state = codeEntryState,
                    message = "Enter a ${BeamCode.CODE_LENGTH}-character Beam code",
                )
            return
        }

        // A code entered from the nearby list must match the selected beam.
        val tappedBeam =
            _uiState.value.discoveredBeams.firstOrNull {
                it.endpointId == _uiState.value.selectedEndpointId
            }

        if (tappedBeam?.code != null && tappedBeam.code != sanitized) {
            _uiState.value =
                _uiState.value.copy(
                    state = codeEntryState,
                    message = "Beam code not found",
                )
            return
        }

        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.Discovering,
                joinCode = sanitized,
                message = "Looking for Beam $sanitized...",
            )

        val alreadyFound =
            if (tappedBeam != null) {
                tappedBeam.takeIf { it.code == sanitized }
            } else {
                _uiState.value.discoveredBeams.firstOrNull {
                    it.code == sanitized
                }
            }

        if (alreadyFound != null) {
            joinBeam(alreadyFound.endpointId)
        } else {
            nearbyManager.startDiscovery()
        }
    }

    private fun joinBeam(endpointId: String) {
        val beam =
            _uiState.value.discoveredBeams
                .firstOrNull { it.endpointId == endpointId }
                ?: return

        _uiState.value =
            _uiState.value.copy(
                state = BeamSessionState.Connecting,
                selectedEndpointId = endpointId,
                message = "Connecting",
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

    /**
     * TODO:
     * Stores a file picked through the Storage Access Framework. The
     * picked Uri is kept for the session and offered to peers once the
     * transfer protocol lands (M3); its metadata is read immediately so
     * the file shows up in the workspace.
     */
    fun onFilePicked(uri: Uri) {
        if (_selectedFiles.value.any { it.uri == uri }) {
            return
        }

        holdReadPermission(uri)

        viewModelScope.launch(Dispatchers.IO) {
            val selected =
                try {
                    BeamFileMetadata.read(
                        getApplication<Application>().contentResolver,
                        uri,
                    )
                } catch (readFailure: Exception) {
                    // A provider that refuses even the metadata read must
                    // not hide the file; degrade to unknown metadata and
                    // still show the row.
                    Log.w(TAG, "Could not read picked file: $uri", readFailure)

                    BeamSelectedFile(
                        id = UUID.randomUUID().toString(),
                        uri = uri,
                        name = uri.lastPathSegment ?: "file",
                        mimeType = null,
                        sizeBytes = BeamFileMetadata.UNKNOWN_SIZE,
                    )
                }

            Log.d(TAG, "Picked file added: ${selected.name} (${selected.uri})")

            _selectedFiles.value += selected
        }
    }

    /**
     * Keeps the read grant to the picked document alive across process
     * restarts while the session lasts.
     */
    private fun holdReadPermission(uri: Uri) {
        try {
            getApplication<Application>()
                .contentResolver
                .takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
        } catch (unpersistableGrant: SecurityException) {
            Log.w(TAG, "Uri grant is not persistable: $uri")
        }
    }

    fun stopSession() {
        if (_uiState.value.room != null) {
            _uiState.value.connectedPeers.forEach { peer ->
                nearbyManager.sendMessage(peer.endpointId, MSG_BEAM_CLOSED)
            }
        }

        destroyBeam(message = null)
    }

    private fun destroyBeam(message: String?) {
        nearbyManager.stop()

        _selectedFiles.value = emptyList()

        _uiState.value =
            BeamSessionUiState(
                state = BeamSessionState.Idle,
                message = message,
            )
    }

    override fun onCleared() {
        nearbyManager.stop()
        super.onCleared()
    }

    companion object {
        private const val TAG = "BeamFiles"

        // TODO: Session-control signal.
        private const val MSG_BEAM_STARTED = "BEAM:STARTED"

        // Host-to-joiners signal that the beam was destroyed by its host.
        private const val MSG_BEAM_CLOSED = "BEAM:CLOSED"
    }
}
