package com.beam.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beam.app.session.SharedFile
import com.beam.app.ui.theme.BeamTheme
import com.beam.app.util.BeamFormat

private data class FileTypeVisual(
    val color: Color,
    val label: String,
    val labelColor: Color? = null,
)

@Composable
private fun fileTypeVisual(mimeType: String?): FileTypeVisual {
    val palette = BeamTheme.palette

    val neutral =
        FileTypeVisual(
            color = palette.surfaceElevated,
            label = "FILE",
        )

    val lower = mimeType?.lowercase() ?: return neutral

    return when {
        lower.startsWith("video/") -> {
            FileTypeVisual(
                color = palette.lime,
                label = "VID",
                labelColor = Color(0xFF0C0D0F),
            )
        }

        lower.startsWith("image/") -> {
            FileTypeVisual(
                color = palette.lime,
                label = "IMG",
                labelColor = Color(0xFF0C0D0F),
            )
        }

        lower.startsWith("audio/") -> {
            FileTypeVisual(
                color = palette.lime,
                label = "AUD",
                labelColor = Color(0xFF0C0D0F),
            )
        }

        lower.startsWith("text/") ||
            lower.contains("pdf") ||
            lower.contains("word") ||
            lower.contains("document") -> {
            FileTypeVisual(
                color = palette.surfaceElevated,
                label = "DOC",
            )
        }

        lower.contains("zip") ||
            lower.contains("rar") ||
            lower.contains("compressed") ||
            lower.contains("tar") -> {
            FileTypeVisual(
                color = palette.surfaceElevated,
                label = "ARC",
            )
        }

        lower.endsWith("apk") ||
            lower.contains("android") -> {
            FileTypeVisual(
                color = palette.surfaceElevated,
                label = "APP",
            )
        }

        else -> {
            neutral
        }
    }
}

@Composable
fun beamFileRow(
    file: SharedFile,
    modifier: Modifier = Modifier,
) {
    val palette = BeamTheme.palette
    val visual = fileTypeVisual(file.mimeType)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        fileTypeGlyph(
            visual = visual,
            modifier = Modifier.padding(end = 14.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = file.name,
                style =
                    BeamTheme.typography.Body.copy(
                        color = palette.textPrimary,
                    ),
                maxLines = 1,
            )

            BasicText(
                text =
                    buildString {
                        append(BeamFormat.fileSize(file.sizeBytes))

                        if (file.senderName.isNotBlank()) {
                            append("\nShared by ")
                            append(file.senderName)
                        }
                    },
                style =
                    BeamTheme.typography.Small.copy(
                        color = palette.textMuted,
                    ),
            )
        }
    }
}

@Composable
private fun fileTypeGlyph(
    visual: FileTypeVisual,
    modifier: Modifier = Modifier,
) {
    val palette = BeamTheme.palette

    val isMedia = visual.color == palette.lime

    Box(
        modifier =
            modifier
                .size(42.dp)
                .background(
                    color = visual.color,
                    shape = RoundedCornerShape(12.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (isMedia) {
            Canvas(modifier = Modifier.size(16.dp)) {
                val inset = Stroke(1.2.dp.toPx()).width

                drawCircle(
                    color = visual.labelColor ?: palette.textPrimary,
                    radius = size.minDimension / 4,
                )

                if (visual.label == "VID") {
                    drawLine(
                        color = visual.labelColor ?: palette.textPrimary,
                        start = Offset(size.width * 0.78f, size.height * 0.35f),
                        end = Offset(size.width * 0.78f, size.height * 0.65f),
                        strokeWidth = inset,
                    )
                }
            }
        } else {
            BasicText(
                text = visual.label,
                style =
                    TextStyle(
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textMuted,
                    ),
            )
        }
    }
}
