package com.beam.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.beam.app.ui.theme.BeamTheme

@Composable
fun beamAddFilesButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
            label = "beamAddFilesScale",
        )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(56.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    alpha = if (enabled) 1f else 0.35f
                }.background(
                    color = palette.lime,
                    shape = RoundedCornerShape(16.dp),
                ).clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            addFilesPlusGlyph(color = Color(0xFF0C0D0F))

            Spacer(modifier = Modifier.padding(start = 10.dp))

            BasicText(
                text = "ADD FILES",
                style =
                    BeamTheme.typography.Button.copy(
                        color = Color(0xFF0C0D0F),
                    ),
            )
        }
    }
}

@Composable
private fun addFilesPlusGlyph(color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val stroke = 2.dp.toPx()
        val a = size.minDimension * 0.5f

        drawLine(
            color = color,
            start = Offset(center.x - a, center.y),
            end = Offset(center.x + a, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )

        drawLine(
            color = color,
            start = Offset(center.x, center.y - a),
            end = Offset(center.x, center.y + a),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
