package com.beam.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beam.app.permissions.BeamPermissions
import com.beam.app.session.BeamSessionViewModel
import com.beam.app.ui.home.homeScreen
import com.beam.app.ui.theme.beamTheme

class MainActivity : ComponentActivity() {
    private val beamSessionViewModel: BeamSessionViewModel
        by viewModels()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            beamTheme {
                val uiState by beamSessionViewModel
                    .uiState
                    .collectAsStateWithLifecycle()

                homeScreen(
                    sessionState = uiState,
                    onCreateBeam = {
                        withNearbyPermissions {
                            beamSessionViewModel.createBeam()
                        }
                    },
                    onJoinBeam = {
                        withNearbyPermissions {
                            beamSessionViewModel.joinBeam()
                        }
                    },
                )
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
