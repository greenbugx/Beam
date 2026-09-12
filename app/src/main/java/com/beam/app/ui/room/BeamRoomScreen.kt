package com.beam.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beam.app.session.BeamSessionState
import com.beam.app.session.BeamSessionUiState
import com.beam.app.session.DiscoveredBeam
import com.beam.app.ui.components.BeamButtonStyle
import com.beam.app.ui.components.beamButton
import com.beam.app.ui.theme.BeamTheme

@Composable
fun beamRoomScreen(
    sessionState: BeamSessionUiState,
    onJoinBeam: (endpointId: String) -> Unit = {},
    onStopSession: () -> Unit = {},
) {
    val palette = BeamTheme.palette
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(palette.background),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = 24.dp,
                        end = 24.dp,
                        top = statusBarPadding.calculateTopPadding() + 18.dp,
                        bottom = 28.dp,
                    ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicText(
                    text =
                        if (sessionState.room != null) {
                            "YOUR BEAM CODE"
                        } else {
                            "JOIN A BEAM"
                        },
                    style =
                        BeamTheme.typography.SectionLabel.copy(
                            color = palette.textMuted,
                        ),
                )

                Spacer(modifier = Modifier.height(14.dp))

                sessionState.room?.let { room ->
                    BasicText(
                        text = room.code,
                        style =
                            BeamTheme.typography.Wordmark.copy(
                                color = palette.textPrimary,
                            ),
                    )
                }

                if (sessionState.message != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    val statusColor =
                        when (sessionState.state) {
                            BeamSessionState.Connected -> {
                                palette.lime
                            }

                            BeamSessionState.Failed -> {
                                palette.error
                            }

                            else -> {
                                palette.textMuted
                            }
                        }

                    BasicText(
                        text = "*  ${sessionState.message}",
                        style =
                            BeamTheme.typography.Small.copy(
                                color = statusColor,
                            ),
                    )
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            ) {
                val joinerConnected =
                    sessionState.room == null &&
                        sessionState.connectedPeers.isNotEmpty()

                if (sessionState.room == null && !joinerConnected) {
                    BasicText(
                        text = "NEARBY BEAMS",
                        style =
                            BeamTheme.typography.SectionLabel.copy(
                                color = palette.textMuted,
                            ),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (sessionState.discoveredBeams.isEmpty()) {
                        BasicText(
                            text = "Scanning for beams nearby...",
                            style =
                                BeamTheme.typography.Small.copy(
                                    color = palette.textMuted,
                                ),
                        )
                    } else {
                        sessionState.discoveredBeams.forEach { beam ->
                            beamRow(
                                beam = beam,
                                selected =
                                    beam.endpointId ==
                                        sessionState.selectedEndpointId,
                                connected =
                                    sessionState.connectedPeers.any {
                                        it.endpointId == beam.endpointId
                                    },
                                differentCode =
                                    beam.code != null &&
                                        sessionState.joinCode != null &&
                                        beam.code != sessionState.joinCode,
                                onJoinBeam = onJoinBeam,
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                if (sessionState.connectedPeers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))

                    BasicText(
                        text = "CONNECTED",
                        style =
                            BeamTheme.typography.SectionLabel.copy(
                                color = palette.textMuted,
                            ),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    sessionState.connectedPeers.forEach { peer ->
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = palette.surfaceElevated,
                                        shape = RoundedCornerShape(14.dp),
                                    ).padding(
                                        start = 18.dp,
                                        end = 18.dp,
                                        top = 14.dp,
                                        bottom = 14.dp,
                                    ),
                        ) {
                            BasicText(
                                text = peer.endpointName,
                                style =
                                    BeamTheme.typography.Body.copy(
                                        color = palette.textPrimary,
                                    ),
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                beamButton(
                    text = "STOP BEAM",
                    onClick = onStopSession,
                    style = BeamButtonStyle.Secondary,
                )

                Spacer(modifier = Modifier.height(34.dp))
            }
        }
    }
}

@Composable
private fun beamRow(
    beam: DiscoveredBeam,
    selected: Boolean,
    connected: Boolean,
    differentCode: Boolean,
    onJoinBeam: (endpointId: String) -> Unit,
) {
    val palette = BeamTheme.palette

    val hint =
        when {
            connected -> "Connected"
            selected -> "Connecting..."
            differentCode -> "Code ${beam.code} — not the one you entered"
            else -> "Tap to connect"
        }

    val hintColor =
        when {
            connected -> palette.lime
            selected -> palette.lime
            differentCode -> palette.warning
            else -> palette.textMuted
        }

    val borderColor =
        if (selected || connected) palette.lime else palette.border

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = palette.surface,
                    shape = RoundedCornerShape(18.dp),
                ).border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(18.dp),
                ).clickable {
                    onJoinBeam(beam.endpointId)
                }.padding(
                    start = 18.dp,
                    end = 18.dp,
                    top = 16.dp,
                    bottom = 16.dp,
                ),
    ) {
        Column {
            BasicText(
                text = beam.name,
                style =
                    BeamTheme.typography.Body.copy(
                        color = palette.textPrimary,
                    ),
            )

            BasicText(
                text = hint,
                style =
                    BeamTheme.typography.Small.copy(
                        color = hintColor,
                    ),
            )
        }
    }
}
