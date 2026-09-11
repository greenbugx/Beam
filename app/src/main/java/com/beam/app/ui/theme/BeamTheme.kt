package com.beam.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class BeamPalette(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val lime: Color,
    val limeDark: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val error: Color,
    val warning: Color,
    val border: Color
)

private val LocalBeamPalette = staticCompositionLocalOf {
    BeamPalette(
        background = BeamColors.Background,
        surface = BeamColors.Surface,
        surfaceElevated = BeamColors.SurfaceElevated,
        lime = BeamColors.Lime,
        limeDark = BeamColors.LimeDark,
        textPrimary = BeamColors.TextPrimary,
        textSecondary = BeamColors.TextSecondary,
        textMuted = BeamColors.TextMuted,
        error = BeamColors.Error,
        warning = BeamColors.Warning,
        border = BeamColors.Border
    )
}

object BeamTheme {
    val palette: BeamPalette
        @Composable
        get() = LocalBeamPalette.current

    val typography: BeamTypography
        get() = BeamTypography
}

@Composable
fun BeamTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalBeamPalette provides BeamPalette(
            background = BeamColors.Background,
            surface = BeamColors.Surface,
            surfaceElevated = BeamColors.SurfaceElevated,
            lime = BeamColors.Lime,
            limeDark = BeamColors.LimeDark,
            textPrimary = BeamColors.TextPrimary,
            textSecondary = BeamColors.TextSecondary,
            textMuted = BeamColors.TextMuted,
            error = BeamColors.Error,
            warning = BeamColors.Warning,
            border = BeamColors.Border
        ),
        content = content
    )
}
