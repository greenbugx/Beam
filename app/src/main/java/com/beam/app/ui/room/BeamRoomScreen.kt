package com.beam.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beam.app.session.BeamSessionState
import com.beam.app.session.BeamSessionUiState
import com.beam.app.ui.components.BeamButtonStyle
import com.beam.app.ui.components.beamButton
import com.beam.app.ui.components.beamStatusText
import com.beam.app.ui.theme.BeamTheme

@Composable
fun beamRoomScreen(
    sessionState: BeamSessionUiState,
    onStartBeam: () -> Unit = {},
    onStopSession: () -> Unit = {},
) {
    val palette = BeamTheme.palette
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    val isHost = sessionState.room != null

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
                        if (isHost) {
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

                Spacer(modifier = Modifier.height(20.dp))

                if (sessionState.message != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    beamStatusText(
                        text = sessionState.message,
                        color =
                            if (sessionState.state == BeamSessionState.Failed) {
                                palette.error
                            } else {
                                palette.textMuted
                            },
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
                if (isHost) {
                    BasicText(
                        text = "SCAN TO JOIN",
                        style =
                            BeamTheme.typography.SectionLabel.copy(
                                color = palette.textMuted,
                            ),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        beamQrPlaceholder()
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    BasicText(
                        text =
                            "Scan the QR or Enter the code " +
                                "above to join.",
                        style =
                            BeamTheme.typography.Small.copy(
                                color = palette.textMuted,
                            ),
                    )

                    if (sessionState.connectedPeers.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))

                        BasicText(
                            text =
                                "Atleast one device must join to start the Beam.",
                            style =
                                BeamTheme.typography.Small.copy(
                                    color = palette.textMuted,
                                ),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
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

                    if (!isHost) {
                        Spacer(modifier = Modifier.height(10.dp))

                        BasicText(
                            text = "Waiting for the host to start...",
                            style =
                                BeamTheme.typography.Small.copy(
                                    color = palette.textMuted,
                                ),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isHost) {
                    beamButton(
                        text = "START BEAM",
                        onClick = onStartBeam,
                        style = BeamButtonStyle.Primary,
                        enabled = sessionState.connectedPeers.isNotEmpty(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }

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
private fun beamQrPlaceholder() {
    val palette = BeamTheme.palette

    // TODO: QR joining lands in M10; this reserves the spot in the lobby at
    // the square size a real QR code would occupy.
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(
                    color = palette.surface,
                    shape = RoundedCornerShape(24.dp),
                ).border(
                    width = 1.dp,
                    color = palette.border,
                    shape = RoundedCornerShape(24.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = "QR CODE",
                style =
                    BeamTheme.typography.SectionLabel.copy(
                        color = palette.textMuted,
                    ),
            )

            Spacer(modifier = Modifier.height(6.dp))

            BasicText(
                text = "Coming soon",
                style =
                    BeamTheme.typography.Small.copy(
                        color = palette.textMuted,
                    ),
            )
        }
    }
}
