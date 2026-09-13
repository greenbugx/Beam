package com.beam.app.ui.nearby

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beam.app.session.BeamSessionUiState
import com.beam.app.session.DiscoveredBeam
import com.beam.app.ui.components.BeamButtonStyle
import com.beam.app.ui.components.beamBrandMark
import com.beam.app.ui.components.beamButton
import com.beam.app.ui.components.beamStatusText
import com.beam.app.ui.theme.BeamTheme

@Composable
fun beamNearbyScreen(
    state: BeamSessionUiState,
    onJoinBeam: (endpointId: String) -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val palette = BeamTheme.palette
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val scrollState = rememberScrollState()

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
                beamBrandMark()

                Spacer(modifier = Modifier.height(26.dp))

                BasicText(
                    text = "Nearby Beams",
                    style =
                        BeamTheme.typography.Hero.copy(
                            color = palette.textPrimary,
                        ),
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (state.discoveredBeams.isEmpty()) {
                    beamStatusText(text = "Searching for nearby beams...")
                }

                Spacer(modifier = Modifier.height(24.dp))

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.discoveredBeams.forEach { beam ->
                        nearbyBeamRow(
                            beam = beam,
                            onClick = { onJoinBeam(beam.endpointId) },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
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

@Composable
private fun nearbyBeamRow(
    beam: DiscoveredBeam,
    onClick: () -> Unit,
) {
    val palette = BeamTheme.palette

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = palette.surface,
                    shape = RoundedCornerShape(18.dp),
                ).border(
                    width = 1.dp,
                    color = palette.border,
                    shape = RoundedCornerShape(18.dp),
                ).clickable(onClick = onClick)
                .padding(
                    start = 18.dp,
                    end = 18.dp,
                    top = 16.dp,
                    bottom = 16.dp,
                ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = beam.displayName ?: beam.name,
            style =
                BeamTheme.typography.Body.copy(
                    color = palette.textPrimary,
                ),
        )

        BasicText(
            text = "ENTER CODE TO JOIN",
            style =
                BeamTheme.typography.SectionLabel.copy(
                    color = palette.textMuted,
                ),
        )
    }
}
