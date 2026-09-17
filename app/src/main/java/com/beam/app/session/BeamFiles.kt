package com.beam.app.session

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class BeamTransferDirection {
    Sending,
    Receiving,
}

enum class BeamTransferStatus {
    Active,
    Paused,
    Completed,
    Failed,
}

/**
 * A file picked through the Storage Access Framework.
 */
data class BeamSelectedFile(
    val id: String,
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
)

data class SharedFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val senderName: String,
)

/** An offered file from a peer, awaiting the user's accept/reject decision. */
data class BeamIncomingOffer(
    val transferId: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val peerLabel: String = "",
)

data class BeamTransfer(
    val id: String,
    val fileName: String,
    val direction: BeamTransferDirection,
    val status: BeamTransferStatus,
    val totalBytes: Long,
    val progressFraction: Float,
    val speedBytesPerSecond: Long = 0,
    val peerLabel: String = "",
)

@Immutable
data class BeamFilesUiState(
    val isHost: Boolean = false,
    val beamName: String? = null,
    val beamCode: String? = null,
    val connectedPeers: List<BeamPeer> = emptyList(),
    val sharedFiles: List<SharedFile> = emptyList(),
    val outgoingTransfers: List<BeamTransfer> = emptyList(),
    val incomingTransfers: List<BeamTransfer> = emptyList(),
    /** Non-null while an offered file waits for the user's decision. */
    val incomingOffer: BeamIncomingOffer? = null,
    val selectedFileIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
)
