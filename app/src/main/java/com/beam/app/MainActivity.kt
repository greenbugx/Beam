package com.beam.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beam.app.permissions.BeamPermissions
import com.beam.app.session.BeamFilesUiState
import com.beam.app.session.BeamSessionState
import com.beam.app.session.BeamSessionViewModel
import com.beam.app.ui.files.beamFilesScreen
import com.beam.app.ui.home.homeScreen
import com.beam.app.ui.join.beamJoinScreen
import com.beam.app.ui.nearby.beamNearbyCodeScreen
import com.beam.app.ui.nearby.beamNearbyScreen
import com.beam.app.ui.room.beamRoomScreen
import com.beam.app.ui.theme.beamTheme

class MainActivity : ComponentActivity() {
    private val beamSessionViewModel: BeamSessionViewModel
        by viewModels()

    private var joinCodeText by mutableStateOf("")

    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            if (BeamPermissions.isSatisfied(result)) {
                pendingAction?.invoke()
            } else {
                beamSessionViewModel.setPermissionDenied()
            }

            pendingAction = null
        }

    private val filePickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                beamSessionViewModel.onFilePicked(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            beamTheme {
                val uiState by beamSessionViewModel
                    .uiState
                    .collectAsStateWithLifecycle()

                val filesState by beamSessionViewModel
                    .filesUiState
                    .collectAsStateWithLifecycle()

                val inSession =
                    uiState.room != null ||
                        uiState.discoveredBeams.isNotEmpty() ||
                        uiState.connectedPeers.isNotEmpty() ||
                        uiState.state in
                        setOf(
                            BeamSessionState.Creating,
                            BeamSessionState.Advertising,
                            BeamSessionState.Discovering,
                            BeamSessionState.BeamFound,
                            BeamSessionState.Connecting,
                            BeamSessionState.Connected,
                        )

                val inBeamWorkspace =
                    uiState.state == BeamSessionState.Sharing

                when {
                    uiState.state == BeamSessionState.EnteringCode -> {
                        beamJoinScreen(
                            code = joinCodeText,
                            onCodeChange = { joinCodeText = it },
                            sessionState = uiState,
                            onFindBeam = {
                                beamSessionViewModel.findBeam(joinCodeText)
                            },
                            onOpenNearby = {
                                joinCodeText = ""
                                withNearbyPermissions {
                                    beamSessionViewModel.openNearbyBeams()
                                }
                            },
                            onCancel = {
                                joinCodeText = ""
                                beamSessionViewModel.stopSession()
                            },
                        )
                    }

                    uiState.state == BeamSessionState.NearbyBeams -> {
                        beamNearbyScreen(
                            state = uiState,
                            onJoinBeam = { endpointId ->
                                joinCodeText = ""
                                beamSessionViewModel.openCodeEntryFor(endpointId)
                            },
                            onCancel = beamSessionViewModel::stopSession,
                        )
                    }

                    uiState.state == BeamSessionState.EnteringNearbyCode -> {
                        beamNearbyCodeScreen(
                            code = joinCodeText,
                            onCodeChange = { joinCodeText = it },
                            sessionState = uiState,
                            onJoinBeam = {
                                beamSessionViewModel.findBeam(joinCodeText)
                            },
                            onBack = beamSessionViewModel::cancelNearbyCodeEntry,
                        )
                    }

                    inBeamWorkspace -> {
                        beamFilesScreen(
                            state = filesState,
                            onAddFiles = {
                                // Storage Access Framework picker.
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            onLeaveBeam = beamSessionViewModel::stopSession,
                            onAcceptOffer = beamSessionViewModel::acceptIncomingOffer,
                            onRejectOffer = beamSessionViewModel::rejectIncomingOffer,
                        )
                    }

                    inSession -> {
                        beamRoomScreen(
                            sessionState = uiState,
                            onStartBeam = beamSessionViewModel::startBeam,
                            onStopSession = beamSessionViewModel::stopSession,
                        )
                    }

                    else -> {
                        homeScreen(
                            sessionState = uiState,
                            onCreateBeam = {
                                withNearbyPermissions {
                                    beamSessionViewModel.createBeam()
                                }
                            },
                            onJoinBeam = {
                                withNearbyPermissions {
                                    beamSessionViewModel.openCodeEntry()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun withNearbyPermissions(action: () -> Unit) {
        val missing =
            BeamPermissions.missing {
                checkSelfPermission(it) ==
                    PackageManager.PERMISSION_GRANTED
            }

        if (missing.isEmpty()) {
            action()
            return
        }

        pendingAction = action

        permissionLauncher.launch(
            missing.toTypedArray(),
        )
    }
}
