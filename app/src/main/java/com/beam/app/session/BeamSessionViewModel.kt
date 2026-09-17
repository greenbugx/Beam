package com.beam.app.session

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beam.app.network.BeamNearbyManager
import com.beam.app.network.NearbyTransportBinding
import com.beam.app.protocol.manager.FileSource
import com.beam.app.protocol.manager.TransferDirection
import com.beam.app.protocol.manager.TransferManager
import com.beam.app.protocol.manager.TransferSnapshot
import com.beam.app.protocol.manager.sha256
import com.beam.app.protocol.session.LinkSession
import com.beam.app.protocol.session.LocalIdentity
import com.beam.app.protocol.session.SessionCloseReason
import com.beam.app.protocol.session.SessionRole
import com.beam.app.protocol.transfer.BeamIds
import com.beam.app.protocol.transfer.FileMetadata
import com.beam.app.protocol.transfer.FilenameSanitizer
import com.beam.app.protocol.transfer.RejectReason
import com.beam.app.protocol.transfer.TransferPhase
import com.beam.app.protocol.transfer.TransferProtocolException
import com.beam.app.protocol.transfer.isTerminal
import com.beam.app.util.BeamCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

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

    private val transferManagers = mutableMapOf<String, TransferManager>()

    private val _transferSnapshots = MutableStateFlow<List<TransferSnapshot>>(emptyList())

    /** Latest transfer snapshots across every connected peer. */
    val transferSnapshots: StateFlow<List<TransferSnapshot>> =
        _transferSnapshots.asStateFlow()

    /** Files the user wants to send; every connected peer is offered each one. */
    private val pendingShares = mutableListOf<BeamSelectedFile>()

    /** File ids already offered per endpoint, so retries skip them. */
    private val offeredFiles = mutableMapOf<String, MutableSet<String>>()

    /** Transfer id -> owning endpoint, for snapshot routing and cleanup. */
    private val transferOwners = mutableMapOf<String, String>()

    val uiState: StateFlow<BeamSessionUiState> =
        _uiState.asStateFlow()

    val selectedFiles: StateFlow<List<BeamSelectedFile>> =
        _selectedFiles.asStateFlow()

    /**
     * Workspace view of the session: peers, shared files, transfers.
     * Derived from the session state, the files picked through the
     * Storage Access Framework, and live transfer snapshots.
     */
    val filesUiState: StateFlow<BeamFilesUiState> =
        combine(
            _uiState,
            _selectedFiles,
            transferSnapshots,
        ) { session, selectedFiles, snapshots ->
            val activeOutgoing =
                snapshots.filter { it.direction == TransferDirection.SENDING }
            val activeIncoming =
                snapshots.filter { it.direction == TransferDirection.RECEIVING }
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
                outgoingTransfers = activeOutgoing.map { it.toBeamTransfer() },
                incomingTransfers = activeIncoming.map { it.toBeamTransfer() },
                incomingOffer = activeIncoming.firstOrNull { it.phase == TransferPhase.Offered }?.toIncomingOffer(),
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

    /** GMS <-> protocol bridge and the per-endpoint link sessions. */
    private val transportBinding = NearbyTransportBinding(nearbyManager, viewModelScope)

    private val linkSessions = mutableMapOf<String, LinkSession>()

    init {
        nearbyManager.onConnected = { endpointId ->
            startLinkSession(endpointId)

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

        nearbyManager.onBytesReceived = { endpointId, bytes ->
            transportBinding.onEndpointBytes(endpointId, bytes)
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
            stopLinkSession(endpointId, reason = null)

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
            viewModelScope.launch { queueForSharing(selected) }
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

    private fun startLinkSession(endpointId: String) {
        val isHost = _uiState.value.room != null
        val role = if (isHost) SessionRole.HOST else SessionRole.PEER

        val transport = transportBinding.onEndpointConnected(endpointId)

        val session =
            LinkSession(
                transport = transport,
                local =
                    LocalIdentity(
                        deviceId = localDeviceId,
                        deviceName = "Beam Device",
                        role = role,
                        sessionId = "BS-${_uiState.value.room?.code ?: _uiState.value.joinCode}",
                        beamCode = _uiState.value.room?.code ?: _uiState.value.joinCode ?: "",
                    ),
                scope = viewModelScope,
            )

        linkSessions[endpointId] = session

        val manager =
            TransferManager(
                tempDir = File(getApplication<Application>().cacheDir, TEMP_DIR_NAME),
                scope = viewModelScope,
            )
        transferManagers[endpointId] = manager
        manager.attach(session, linkId = endpointId, peerName = linkPeerName(endpointId))
        manager.transfers
            .onEach { snapshots ->
                mergeTransferSnapshots(snapshots)
                val terminal = snapshots.filter { it.phase.isTerminal }.map { it.transferId }
                if (terminal.isNotEmpty()) transferOwners.keys.removeAll(terminal.toSet())
                if (pendingShares.isNotEmpty() && snapshots.none { !it.phase.isTerminal }) {
                    flushPendingShares(endpointId)
                }
            }.launchIn(viewModelScope)

        viewModelScope.launch {
            delay(SHARE_FLUSH_GRACE)
            flushPendingShares(endpointId)
        }

        session.start()
    }

    /** Merges one manager's snapshots into the shared UI relay. */
    private fun mergeTransferSnapshots(snapshots: List<TransferSnapshot>) {
        val replaced = snapshots.map { it.transferId }.toSet()
        _transferSnapshots.value =
            _transferSnapshots.value.filterNot { it.transferId in replaced } + snapshots
    }

    /** Display name for a peer link, resolved from discovery or a shortened endpoint id. */
    private fun linkPeerName(endpointId: String): String =
        _uiState.value.discoveredBeams
            .firstOrNull { it.endpointId == endpointId }
            ?.let { discovered -> discovered.displayName ?: discovered.name }
            ?: "Device ${endpointId.take(4)}"

    private fun stopLinkSession(
        endpointId: String,
        reason: SessionCloseReason?,
    ) {
        val session = linkSessions.remove(endpointId) ?: return
        Log.d(TAG, "Stopping link session: $endpointId")
        transferManagers.remove(endpointId)?.let { manager ->
            viewModelScope.launch { manager.close() }
        }
        _transferSnapshots.value = emptyList()
        if (reason != null) {
            session.close(reason)
        } else {
            transportBinding.onEndpointDisconnected(endpointId)
        }
    }

    private val localDeviceId: String by lazy { UUID.randomUUID().toString() }

    private fun destroyBeam(message: String?) {
        nearbyManager.stop()

        transportBinding.shutdown()
        linkSessions.clear()
        closeTransferManagers()
        pendingShares.clear()
        offeredFiles.clear()
        transferOwners.clear()
        _selectedFiles.value = emptyList()

        _uiState.value =
            BeamSessionUiState(
                state = BeamSessionState.Idle,
                message = message,
            )
    }

    override fun onCleared() {
        nearbyManager.stop()

        transportBinding.shutdown()
        linkSessions.clear()
        closeTransferManagers()
        pendingShares.clear()
        offeredFiles.clear()
        transferOwners.clear()

        super.onCleared()
    }

    /** Closes every per-endpoint transfer engine and clears transfer UI state. */
    private fun closeTransferManagers() {
        transferManagers.values.forEach { manager ->
            viewModelScope.launch { manager.close() }
        }
        transferManagers.clear()
        _transferSnapshots.value = emptyList()
    }

    private fun queueForSharing(file: BeamSelectedFile) {
        pendingShares += file
        linkSessions.keys.forEach { flushPendingShares(it) }
    }

    private fun flushPendingShares(endpointId: String) {
        val manager = transferManagers[endpointId] ?: return
        val done = offeredFiles.getOrPut(endpointId) { mutableSetOf() }
        val snapshot = pendingShares.toList()
        for (file in snapshot) {
            if (file.id in done) continue
            done += file.id
            val metadata = shareMetadata(file) ?: continue
            viewModelScope.launch {
                try {
                    val transferId = offerFile(manager, endpointId, file, metadata)
                    transferOwners[transferId] = endpointId
                    Log.d(TAG, "Offered ${file.name} to $endpointId (transfer $transferId)")
                } catch (e: TransferProtocolException) {
                    Log.w(TAG, "Offer of ${file.name} to $endpointId failed: ${e.message}")
                }
            }
        }
    }

    /** Hashes the selected file and offers it over the link. */
    private suspend fun offerFile(
        manager: TransferManager,
        endpointId: String,
        file: BeamSelectedFile,
        metadata: FileMetadata,
    ): String {
        val resolver = getApplication<Application>().contentResolver
        val source =
            FileSource {
                resolver.openInputStream(file.uri)
                    ?: throw java.io.FileNotFoundException("Share stream unavailable: ${file.name}")
            }
        return manager.offer(metadata, source, endpointId)
    }

    /** Builds spec-compliant metadata; requires an openable, hashable file. */
    private fun shareMetadata(file: BeamSelectedFile): FileMetadata? {
        val resolver = getApplication<Application>().contentResolver
        val source =
            FileSource {
                resolver.openInputStream(file.uri)
                    ?: throw java.io.FileNotFoundException("Share stream unavailable: ${file.name}")
            }
        return try {
            val sha256 = source.sha256()
            FileMetadata(
                transferId = BeamIds.newTransferId(),
                fileId = BeamIds.newFileId(),
                name = file.name,
                mime = file.mimeType ?: "application/octet-stream",
                sizeBytes = file.sizeBytes,
                sha256 = sha256,
                chunkSize = FileMetadata.DEFAULT_CHUNK_SIZE_BYTES,
                chunkCount = FileMetadata.derivedChunkCount(file.sizeBytes, FileMetadata.DEFAULT_CHUNK_SIZE_BYTES),
            )
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Cannot hash ${file.name}: ${e.message}")
            null
        }
    }

    /** Collision-safe destination in app-scoped external Beam/ storage. */
    private fun publishDestination(fileName: String): File {
        val dir =
            File(
                getApplication<Application>().getExternalFilesDir(null)
                    ?: getApplication<Application>().filesDir,
                PUBLISH_DIR_NAME,
            )
        if (!dir.exists()) dir.mkdirs()
        val sanitized = FilenameSanitizer.sanitize(fileName).ifBlank { "file" }
        val dot = sanitized.lastIndexOf('.')
        val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val ext = if (dot > 0) sanitized.substring(dot) else ""
        var candidate = File(dir, sanitized)
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n += 1
        }
        return candidate
    }

    /** Maps a protocol snapshot onto the workspace transfer row. */
    private fun TransferSnapshot.toBeamTransfer(): BeamTransfer =
        BeamTransfer(
            id = transferId,
            fileName = fileName,
            direction =
                when (direction) {
                    TransferDirection.SENDING -> BeamTransferDirection.Sending
                    TransferDirection.RECEIVING -> BeamTransferDirection.Receiving
                },
            status =
                when (phase) {
                    is TransferPhase.Offered,
                    is TransferPhase.Accepted,
                    is TransferPhase.Transferring,
                    is TransferPhase.Verifying,
                    -> BeamTransferStatus.Active

                    is TransferPhase.Paused -> BeamTransferStatus.Paused

                    is TransferPhase.Completed -> BeamTransferStatus.Completed

                    is TransferPhase.Rejected,
                    is TransferPhase.Expired,
                    is TransferPhase.Cancelled,
                    is TransferPhase.Failed,
                    -> BeamTransferStatus.Failed
                },
            totalBytes = sizeBytes,
            progressFraction =
                if (sizeBytes > 0L) {
                    (bytesTransferred.toFloat() / sizeBytes).coerceIn(0f, 1f)
                } else {
                    1f
                },
            speedBytesPerSecond = bytesPerSecond.coerceAtLeast(0L),
            peerLabel = peerName,
        )

    /** Maps an offered snapshot onto the accept/reject prompt model. */
    private fun TransferSnapshot.toIncomingOffer(): BeamIncomingOffer =
        BeamIncomingOffer(
            transferId = transferId,
            fileName = fileName,
            sizeBytes = sizeBytes,
            peerLabel = peerName,
        )

    fun acceptIncomingOffer(offer: BeamIncomingOffer) {
        val manager = managerFor(offer.transferId) ?: return
        val destination = publishDestination(offer.fileName)
        viewModelScope.launch {
            try {
                manager.accept(offer.transferId, destination)
                Log.d(TAG, "Accepted offer ${offer.transferId} -> ${destination.name}")
            } catch (e: TransferProtocolException) {
                Log.w(TAG, "Accept failed for ${offer.transferId}: ${e.message}")
            }
        }
    }

    /** Rejects the pending offer with the given reason. */
    fun rejectIncomingOffer(
        offer: BeamIncomingOffer,
        reason: RejectReason = RejectReason.USER_REJECTED,
    ) {
        val manager = managerFor(offer.transferId) ?: return
        viewModelScope.launch {
            try {
                manager.reject(offer.transferId, reason)
                Log.d(TAG, "Rejected offer ${offer.transferId} (${reason.name})")
            } catch (e: TransferProtocolException) {
                Log.w(TAG, "Reject failed for ${offer.transferId}: ${e.message}")
            }
        }
    }

    /** Finds the manager that owns a transfer id, for user decisions. */
    private fun managerFor(transferId: String): TransferManager? =
        transferManagers.values.firstOrNull { manager -> manager.snapshot(transferId) != null }
            ?: run {
                Log.w(TAG, "No manager holds transfer $transferId")
                null
            }

    companion object {
        private const val TAG = "BeamFiles"

        /** Temp staging dir for incoming chunks, under app cache. */
        private const val TEMP_DIR_NAME = "beam-tmp"

        /** App-scoped external dir where verified files are published. */
        private const val PUBLISH_DIR_NAME = "Beam"

        /** Grace for a peer's handshake before the first share flush. */
        private val SHARE_FLUSH_GRACE = 2.seconds

        // TODO: Session-control signal.
        private const val MSG_BEAM_STARTED = "BEAM:STARTED"

        // Host-to-joiners signal that the beam was destroyed by its host.
        private const val MSG_BEAM_CLOSED = "BEAM:CLOSED"
    }
}
