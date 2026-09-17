package com.beam.app.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.beam.app.session.BeamFilesUiState
import com.beam.app.session.BeamIncomingOffer
import com.beam.app.ui.components.BeamButtonStyle
import com.beam.app.ui.components.beamAddFilesButton
import com.beam.app.ui.components.beamBrandMark
import com.beam.app.ui.components.beamButton
import com.beam.app.ui.components.beamConnectionIndicator
import com.beam.app.ui.components.beamFileRow
import com.beam.app.ui.components.beamSectionLabel
import com.beam.app.ui.components.beamTransferRow
import com.beam.app.ui.theme.BeamTheme

private const val ITEM_APPEAR_FADE = 220
private const val ITEM_APPEAR_SLIDE = 280

@Composable
fun beamFilesScreen(
    state: BeamFilesUiState,
    onAddFiles: () -> Unit,
    onLeaveBeam: () -> Unit,
    onAcceptOffer: (BeamIncomingOffer) -> Unit,
    onRejectOffer: (BeamIncomingOffer) -> Unit,
) {
    val palette = BeamTheme.palette
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues()

    state.incomingOffer?.let { offer ->
        beamOfferDialog(
            offer = offer,
            onAccept = { onAcceptOffer(offer) },
            onReject = { onRejectOffer(offer) },
        )
    }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(palette.background),
        contentPadding =
            PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = statusBarPadding.calculateTopPadding() + 14.dp,
                bottom = navigationBarPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        item(key = "header") {
            Column {
                beamBrandMark()

                Spacer(modifier = Modifier.height(10.dp))

                BasicText(
                    text = state.beamName ?: "Beam",
                    style =
                        BeamTheme.typography.Hero.copy(
                            color = palette.textPrimary,
                        ),
                )

                Spacer(modifier = Modifier.height(6.dp))

                beamConnectionIndicator(
                    connected = state.connectedPeers.isNotEmpty(),
                    text =
                        when (val count = state.connectedPeers.size) {
                            0 -> {
                                if (state.isHost) {
                                    "Waiting for devices"
                                } else {
                                    "Connected"
                                }
                            }

                            1 -> {
                                "1 device connected"
                            }

                            else -> {
                                "$count devices connected"
                            }
                        },
                    pulse = state.isHost && state.connectedPeers.isEmpty(),
                )

                state.error?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))

                    BasicText(
                        text = "*  $error",
                        style =
                            BeamTheme.typography.Small.copy(
                                color = palette.error,
                            ),
                    )
                }
            }
        }

        item(key = "addFiles") {
            Spacer(modifier = Modifier.height(22.dp))

            beamAddFilesButton(onClick = onAddFiles)
        }

        item(key = "sharedHeader") {
            Spacer(modifier = Modifier.height(30.dp))

            beamSectionLabel("SHARED")
        }

        if (state.sharedFiles.isEmpty()) {
            item(key = "sharedEmpty") {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))

                    BasicText(
                        text =
                            "Nothing here yet.\n" +
                                "Add something to this Beam and it will appear here.",
                        style =
                            BeamTheme.typography.Small.copy(
                                color = palette.textMuted,
                            ),
                    )
                }
            }
        } else {
            itemsWithAppear(
                count = state.sharedFiles.size,
                keyFor = { state.sharedFiles[it].id },
            ) { index ->
                beamFileRow(file = state.sharedFiles[index])
            }
        }

        if (state.outgoingTransfers.isNotEmpty() ||
            state.incomingTransfers.isNotEmpty()
        ) {
            item(key = "transfersHeader") {
                Spacer(modifier = Modifier.height(30.dp))

                beamSectionLabel("TRANSFERS")
            }

            state.outgoingTransfers.forEach { transfer ->
                item(key = "out-${transfer.id}") {
                    Spacer(modifier = Modifier.height(16.dp))

                    beamAppearIn {
                        beamTransferRow(transfer = transfer)
                    }
                }
            }

            state.incomingTransfers.forEach { transfer ->
                item(key = "in-${transfer.id}") {
                    Spacer(modifier = Modifier.height(16.dp))

                    beamAppearIn {
                        beamTransferRow(transfer = transfer)
                    }
                }
            }
        }

        item(key = "footer") {
            Column {
                Spacer(modifier = Modifier.height(30.dp))

                beamButton(
                    text = "LEAVE BEAM",
                    onClick = onLeaveBeam,
                    style = BeamButtonStyle.Secondary,
                )
            }
        }
    }
}

private fun LazyListScope.itemsWithAppear(
    count: Int,
    keyFor: (index: Int) -> String,
    content: @Composable (index: Int) -> Unit,
) {
    items(
        count = count,
        key = keyFor,
    ) { index ->
        Spacer(modifier = Modifier.height(16.dp))

        beamAppearIn {
            content(index)
        }
    }
}

@Composable
private fun beamAppearIn(content: @Composable () -> Unit) {
    var appeared by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { appeared = true }

    AnimatedVisibility(
        visible = appeared,
        enter =
            fadeIn(tween(ITEM_APPEAR_FADE)) +
                slideInVertically(
                    animationSpec = tween(ITEM_APPEAR_SLIDE),
                    initialOffsetY = { it / 10 },
                ),
    ) {
        content()
    }
}

/** Accept/reject prompt for an offered file, shown while its offer is pending. */
@Composable
private fun beamOfferDialog(
    offer: BeamIncomingOffer,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val palette = BeamTheme.palette

    Dialog(onDismissRequest = onReject) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(palette.surfaceElevated),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                beamSectionLabel("INCOMING FILE")

                Spacer(modifier = Modifier.height(14.dp))

                BasicText(
                    text = offer.fileName,
                    style =
                        BeamTheme.typography.Hero.copy(
                            color = palette.textPrimary,
                        ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(6.dp))

                BasicText(
                    text = "${formatBytes(offer.sizeBytes)} · from ${offer.peerLabel}",
                    style =
                        BeamTheme.typography.Small.copy(
                            color = palette.textMuted,
                        ),
                )

                Spacer(modifier = Modifier.height(22.dp))

                beamButton(
                    text = "ACCEPT",
                    onClick = onAccept,
                    style = BeamButtonStyle.Primary,
                )

                Spacer(modifier = Modifier.height(10.dp))

                beamButton(
                    text = "DECLINE",
                    onClick = onReject,
                    style = BeamButtonStyle.Secondary,
                )
            }
        }
    }
}

/** Human-readable byte size for the offer prompt. */
private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576f)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024f)
        else -> "$bytes B"
    }
