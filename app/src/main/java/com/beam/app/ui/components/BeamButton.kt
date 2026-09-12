package com.beam.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.beam.app.ui.theme.BeamTheme

enum class BeamButtonStyle {
    Primary,
    Secondary,
}

@Composable
fun beamButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BeamButtonStyle = BeamButtonStyle.Primary,
    enabled: Boolean = true,
) {
    val palette = BeamTheme.palette
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value

    val scale =
        animateFloatAsState(
            targetValue = if (pressed && enabled) 0.985f else 1f,
            animationSpec =
                spring(
                    dampingRatio = 0.78f,
                    stiffness = 700f,
                ),
            label = "beamButtonScale",
        )

    val isPrimary = style == BeamButtonStyle.Primary

    val background =
        if (isPrimary) palette.lime else palette.surface

    val foreground =
        if (isPrimary) Color(0xFF0C0D0F) else palette.textPrimary

    val borderColor =
        if (isPrimary) Color.Transparent else palette.border

    val disabledAlpha = if (enabled) 1f else 0.35f

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(60.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    alpha = disabledAlpha
                }.background(
                    color = background,
                    shape = RoundedCornerShape(18.dp),
                ).border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(18.dp),
                ).clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style =
                BeamTheme.typography.Button.copy(
                    color = foreground,
                ),
        )
    }
}
