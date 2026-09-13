package com.beam.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beam.app.ui.theme.BeamTheme

@Composable
fun beamSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        style =
            BeamTheme.typography.SectionLabel.copy(
                color = BeamTheme.palette.textMuted,
            ),
        modifier = modifier,
    )
}

@Composable
fun beamStatusText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = BeamTheme.palette.textMuted,
) {
    BasicText(
        text = text.uppercase(),
        style =
            BeamTheme.typography.SectionLabel.copy(
                color = color,
                letterSpacing = 1.2.sp,
            ),
        modifier = modifier,
    )
}

@Composable
fun beamConnectionIndicator(
    connected: Boolean,
    text: String,
    pulse: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = BeamTheme.palette

    val color by animateColorAsState(
        targetValue = if (connected) palette.lime else palette.textMuted,
        animationSpec = tween(300),
        label = "beamConnectionColor",
    )

    val pulseTransition = rememberInfiniteTransition(label = "beamPulse")
    val pulseAlpha by
        pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(900),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "beamPulseAlpha",
        )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(7.dp)
                    .alpha(if (pulse) pulseAlpha else 1f)
                    .background(color = color, shape = CircleShape),
        )

        Spacer(modifier = Modifier.padding(start = 8.dp))

        BasicText(
            text = text,
            style =
                BeamTheme.typography.Small.copy(
                    color = palette.textSecondary,
                ),
        )
    }
}

@Composable
fun beamBrandMark(
    modifier: Modifier = Modifier,
    accentColor: Color = BeamTheme.palette.lime,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicText(
            text = "BEAM",
            style =
                BeamTheme.typography.SectionLabel.copy(
                    color = BeamTheme.palette.textPrimary,
                    letterSpacing = 2.4.sp,
                ),
        )

        Spacer(modifier = Modifier.padding(start = 3.dp))

        Box(
            modifier =
                Modifier
                    .padding(bottom = 1.dp)
                    .size(4.dp)
                    .background(
                        color = accentColor,
                        shape = CircleShape,
                    ),
        )
    }
}
