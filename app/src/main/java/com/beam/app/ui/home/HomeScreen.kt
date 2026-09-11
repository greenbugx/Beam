package com.beam.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import com.beam.app.R
import com.beam.app.ui.components.BeamButton
import com.beam.app.ui.components.BeamButtonStyle
import com.beam.app.ui.theme.BeamTheme

@Composable
fun HomeScreen(
    onCreateBeam: () -> Unit = {},
    onJoinBeam: () -> Unit = {}
) {
    val palette = BeamTheme.palette
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = statusBarPadding.calculateTopPadding() + 18.dp,
                    bottom = 28.dp
                ),
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(R.drawable.beam_logo),
                    contentDescription = "Beam",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(56.dp)
                        .offset(x = (-16).dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                BasicText(
                    text = "BEAM",
                    style = BeamTheme.typography.Wordmark.copy(
                        color = palette.textPrimary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                BasicText(
                    text = "Send anything.\nNearby. Fast.",
                    style = BeamTheme.typography.Hero.copy(
                        color = palette.textSecondary
                    )
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                BeamButton(
                    text = "BEAM IT TO THEM",
                    onClick = onCreateBeam,
                    style = BeamButtonStyle.Primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                BeamButton(
                    text = "BEAM IT TO ME",
                    onClick = onJoinBeam,
                    style = BeamButtonStyle.Secondary
                )

                Spacer(modifier = Modifier.height(34.dp))
            }
        }
    }
}