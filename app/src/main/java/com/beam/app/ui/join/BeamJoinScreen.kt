package com.beam.app.ui.join

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beam.app.session.BeamSessionState
import com.beam.app.session.BeamSessionUiState
import com.beam.app.ui.components.BeamButtonStyle
import com.beam.app.ui.components.beamButton
import com.beam.app.ui.components.beamCodeInput
import com.beam.app.ui.components.beamStatusText
import com.beam.app.ui.theme.BeamTheme
import com.beam.app.util.BeamCode

@Composable
fun beamJoinScreen(
    code: String,
    onCodeChange: (String) -> Unit,
    sessionState: BeamSessionUiState,
    onFindBeam: () -> Unit,
    onOpenNearby: () -> Unit = {},
    onCancel: () -> Unit = {},
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
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicText(
                    text = "JOIN A BEAM",
                    style =
                        BeamTheme.typography.SectionLabel.copy(
                            color = palette.textMuted,
                        ),
                )

                Spacer(modifier = Modifier.height(14.dp))

                BasicText(
                    text = "Enter the code\nshown on the other device.",
                    style =
                        BeamTheme.typography.Hero.copy(
                            color = palette.textSecondary,
                        ),
                )

                Spacer(modifier = Modifier.height(34.dp))

                beamCodeInput(
                    code = code,
                    onCodeChange = onCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (sessionState.message != null) {
                    Spacer(modifier = Modifier.height(20.dp))

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
                modifier = Modifier.fillMaxWidth(),
            ) {
                beamButton(
                    text = "FIND BEAM",
                    onClick = onFindBeam,
                    style = BeamButtonStyle.Primary,
                    enabled = BeamCode.isValid(code),
                )

                Spacer(modifier = Modifier.height(12.dp))

                beamButton(
                    text = "SEARCH NEARBY",
                    onClick = onOpenNearby,
                    style = BeamButtonStyle.Secondary,
                )

                Spacer(modifier = Modifier.height(12.dp))

                beamButton(
                    text = "SCAN QR CODE",
                    onClick = {},
                    style = BeamButtonStyle.Secondary,
                    enabled = false,
                )

                Spacer(modifier = Modifier.height(12.dp))

                beamButton(
                    text = "BACK",
                    onClick = onCancel,
                    style = BeamButtonStyle.Secondary,
                )

                Spacer(modifier = Modifier.height(34.dp))
            }
        }
    }
}
