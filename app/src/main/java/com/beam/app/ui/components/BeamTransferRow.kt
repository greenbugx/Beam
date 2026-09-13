package com.beam.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.beam.app.session.BeamTransfer
import com.beam.app.session.BeamTransferDirection
import com.beam.app.session.BeamTransferStatus
import com.beam.app.ui.theme.BeamTheme
import com.beam.app.util.BeamFormat

@Composable
fun beamTransferRow(
    transfer: BeamTransfer,
    modifier: Modifier = Modifier,
) {
    val palette = BeamTheme.palette

    val active = transfer.status == BeamTransferStatus.Active

    val progress by animateFloatAsState(
        targetValue = transfer.progressFraction.coerceIn(0f, 1f),
        animationSpec =
            spring(
                dampingRatio = 1f,
                stiffness = 380f,
            ),
        label = "beamTransferProgress",
    )

    val barColor =
        when (transfer.status) {
            BeamTransferStatus.Active -> palette.lime
            BeamTransferStatus.Paused -> palette.textMuted
            BeamTransferStatus.Completed -> palette.limeDark
            BeamTransferStatus.Failed -> palette.error
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            directionGlyph(
                direction = transfer.direction,
                status = transfer.status,
                modifier = Modifier.padding(end = 12.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = transfer.fileName,
                    style =
                        BeamTheme.typography.Body.copy(
                            color = palette.textPrimary,
                        ),
                    maxLines = 1,
                )

                BasicText(
                    text = transferSubtitle(transfer),
                    style =
                        BeamTheme.typography.Small.copy(
                            color =
                                when (transfer.status) {
                                    BeamTransferStatus.Failed -> palette.error
                                    BeamTransferStatus.Completed -> palette.limeDark
                                    else -> palette.textMuted
                                },
                        ),
                    maxLines = 1,
                )
            }

            if (active || transfer.status == BeamTransferStatus.Paused) {
                BasicText(
                    text = "${(transfer.progressFraction * 100).toInt()}%",
                    style =
                        BeamTheme.typography.Small.copy(
                            color = palette.textPrimary,
                        ),
                )
            }
        }

        if (active) {
            Spacer(modifier = Modifier.height(8.dp))

            beamProgressTrack(
                progress = progress,
                color = barColor,
            )
        }
    }
}

private fun transferSubtitle(transfer: BeamTransfer): String =
    when (transfer.status) {
        BeamTransferStatus.Active -> {
            val speed = BeamFormat.speed(transfer.speedBytesPerSecond)
            val eta = remainingEta(transfer)

            val action =
                when (transfer.direction) {
                    BeamTransferDirection.Sending -> {
                        if (transfer.peerLabel.isNotBlank()) {
                            "Sending to ${transfer.peerLabel}"
                        } else {
                            "Sending"
                        }
                    }

                    BeamTransferDirection.Receiving -> {
                        if (transfer.peerLabel.isNotBlank()) {
                            "Receiving from ${transfer.peerLabel}"
                        } else {
                            "Receiving"
                        }
                    }
                }

            if (eta == null) {
                "$action · $speed"
            } else {
                "$action · $speed · $eta"
            }
        }

        BeamTransferStatus.Paused -> {
            "Paused"
        }

        BeamTransferStatus.Failed -> {
            "Failed"
        }

        BeamTransferStatus.Completed -> {
            when (transfer.direction) {
                BeamTransferDirection.Sending -> "Sent"
                BeamTransferDirection.Receiving -> "Received"
            }
        }
    }

private fun remainingEta(transfer: BeamTransfer): String? {
    if (transfer.speedBytesPerSecond <= 0) return null

    val remainingBytes =
        (transfer.totalBytes * (1 - transfer.progressFraction)).toLong()

    val seconds = remainingBytes / transfer.speedBytesPerSecond

    if (seconds < 3) return null

    val minutes = seconds / 60

    return when {
        minutes >= 60 -> {
            val hours = minutes / 60
            val remMinutes = minutes % 60
            "${hours}h ${remMinutes}m left"
        }

        minutes >= 1 -> {
            "${minutes}m ${seconds % 60}s left"
        }

        else -> {
            "$seconds s left"
        }
    }
}

@Composable
private fun beamProgressTrack(
    progress: Float,
    color: Color,
) {
    val palette = BeamTheme.palette

    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(4.dp),
    ) {
        drawRoundRect(
            color = palette.surfaceElevated,
            cornerRadius = CornerRadius(size.height / 2),
        )

        if (progress > 0f) {
            drawRoundRect(
                color = color,
                size =
                    Size(
                        width = size.width * progress,
                        height = size.height,
                    ),
                cornerRadius = CornerRadius(size.height / 2),
            )
        }
    }
}

@Composable
private fun directionGlyph(
    direction: BeamTransferDirection,
    status: BeamTransferStatus,
    modifier: Modifier = Modifier,
) {
    val palette = BeamTheme.palette

    val color =
        when (status) {
            BeamTransferStatus.Failed -> palette.error
            BeamTransferStatus.Completed -> palette.limeDark
            BeamTransferStatus.Paused -> palette.textMuted
            BeamTransferStatus.Active -> palette.lime
        }

    Canvas(modifier = modifier.size(34.dp)) {
        val stroke = 1.6.dp.toPx()
        val radius = size.minDimension / 2 - stroke

        drawCircle(
            color = color.copy(alpha = 0.45f),
            radius = radius,
            style = Stroke(stroke),
        )

        val cx = center.x
        val cy = center.y
        val a = radius * 0.55f
        val tipOffset = a * 0.85f
        val baseOffset = a * 0.45f

        val (tipY, baseY) =
            if (direction == BeamTransferDirection.Sending) {
                cy - tipOffset to cy + baseOffset
            } else {
                cy + tipOffset to cy - baseOffset
            }

        val tip = Offset(cx, tipY)

        drawLine(
            color = color,
            start = Offset(cx - a, baseY),
            end = tip,
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )

        drawLine(
            color = color,
            start = Offset(cx + a, baseY),
            end = tip,
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
