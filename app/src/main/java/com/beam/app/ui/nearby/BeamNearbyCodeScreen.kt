package com.beam.app.ui.nearby

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beam.app.session.BeamSessionState
import com.beam.app.session.BeamSessionUiState
import com.beam.app.ui.components.BeamButtonStyle
import com.beam.app.ui.components.beamBrandMark
import com.beam.app.ui.components.beamButton
import com.beam.app.ui.components.beamStatusText
import com.beam.app.ui.theme.BeamTheme
import com.beam.app.util.BeamCode

@Composable
fun beamNearbyCodeScreen(
    code: String,
    onCodeChange: (String) -> Unit,
    sessionState: BeamSessionUiState,
    onJoinBeam: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = BeamTheme.palette
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val caretTransition = rememberInfiniteTransition(label = "beamCaret")
    val caretAlpha by
        caretTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(450),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "beamCaretAlpha",
        )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
                    text = "Enter the code\nshown on the other device.",
                    style =
                        BeamTheme.typography.Hero.copy(
                            color = palette.textSecondary,
                        ),
                )

                Spacer(modifier = Modifier.height(34.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(BeamCode.CODE_LENGTH) { index ->
                            nearbyCodeCell(
                                char = code.getOrNull(index),
                                isActive = focused && code.length == index,
                                caretAlpha = caretAlpha,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    BasicTextField(
                        value = code,
                        onValueChange = { onCodeChange(BeamCode.sanitize(it)) },
                        textStyle = TextStyle(color = Color.Transparent),
                        cursorBrush = SolidColor(Color.Transparent),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false,
                            ),
                        singleLine = true,
                        interactionSource = interactionSource,
                        modifier =
                            Modifier
                                .matchParentSize()
                                .focusRequester(focusRequester),
                    )
                }

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
                    text = "JOIN BEAM",
                    onClick = onJoinBeam,
                    style = BeamButtonStyle.Primary,
                    enabled = BeamCode.isValid(code),
                )

                Spacer(modifier = Modifier.height(12.dp))

                beamButton(
                    text = "BACK",
                    onClick = onBack,
                    style = BeamButtonStyle.Secondary,
                )

                Spacer(modifier = Modifier.height(34.dp))
            }
        }
    }
}

@Composable
private fun nearbyCodeCell(
    char: Char?,
    isActive: Boolean,
    caretAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val palette = BeamTheme.palette

    val borderColor = if (isActive) palette.lime else palette.border

    val background =
        if (char != null) palette.surfaceElevated else palette.surface

    Box(
        modifier =
            modifier
                .height(56.dp)
                .background(
                    color = background,
                    shape = RoundedCornerShape(14.dp),
                ).border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(14.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (char != null) {
            BasicText(
                text = char.toString(),
                style =
                    BeamTheme.typography.Hero.copy(
                        color = palette.textPrimary,
                    ),
            )
        } else if (isActive) {
            Box(
                modifier =
                    Modifier
                        .width(2.dp)
                        .height(24.dp)
                        .alpha(caretAlpha)
                        .background(
                            color = palette.lime,
                            shape = RoundedCornerShape(1.dp),
                        ),
            )
        }
    }
}
