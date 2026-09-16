package com.beam.app.protocol.manager

import com.beam.app.protocol.session.LinkSession
import com.beam.app.protocol.transfer.ChunkPlan
import com.beam.app.protocol.transfer.FileMetadata
import com.beam.app.protocol.transfer.RejectReason
import com.beam.app.protocol.transfer.TempSweep
import com.beam.app.protocol.transfer.TransferError
import com.beam.app.protocol.transfer.TransferErrorCode
import com.beam.app.protocol.transfer.TransferPhase
import com.beam.app.protocol.transfer.TransferProtocolException
import com.beam.app.protocol.transfer.TransferTimeouts
import com.beam.app.protocol.transfer.isTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class TransferManager(
    private val tempDir: File,
    private val scope: CoroutineScope,
    private val timeouts: TransferTimeouts = TransferTimeouts(),
    private val window: Int = ChunkPlan.DEFAULT_WINDOW_CHUNKS,
    private val clock: Clock = SystemClock,
    private val tickMillis: Long = PROGRESS_TICK_MILLIS,
) {
    private val guard = Any()
    private val links = mutableMapOf<String, TransferLink>()
    private val records = LinkedHashMap<String, Record>()
    private val _transfers = MutableStateFlow<List<TransferSnapshot>>(emptyList())

    val transfers: StateFlow<List<TransferSnapshot>> = _transfers

    private var ticker: Job? = null
    private var closed = false

    init {
        require(tickMillis > 0)
        sweepTemps()
        startTicker()
    }

    fun sweepTemps(activeTransferIds: Set<String> = activeIds()): Int = TempSweep.sweep(tempDir, activeTransferIds)

    internal fun attach(
        session: LinkSession,
        linkId: String,
        peerName: String = linkId,
    ): TransferLink {
        synchronized(guard) {
            check(!closed) { "Transfer manager is closed" }
            check(linkId !in links) { "Link $linkId is already attached" }
        }
        val link =
            TransferLink(session, linkId, peerName, tempDir, timeouts, window, scope) { event ->
                onLinkEvent(event)
            }
        synchronized(guard) { links[linkId] = link }
        link.start()
        return link
    }

    suspend fun detach(linkId: String) {
        val link = synchronized(guard) { links.remove(linkId) }
        link?.close()
    }

    internal fun link(linkId: String): TransferLink? = synchronized(guard) { links[linkId] }

    suspend fun offer(
        metadata: FileMetadata,
        source: FileSource,
        linkId: String,
    ): String {
        val invalid = metadata.validate()
        if (invalid.isNotEmpty()) {
            throw TransferProtocolException("Invalid metadata: ${invalid.joinToString()}")
        }
        val target = link(linkId) ?: throw TransferProtocolException("Unknown link $linkId")
        val transferId = target.offer(metadata, source)
        synchronized(guard) {
            records[transferId] =
                Record(
                    transferId = transferId,
                    linkId = linkId,
                    peerName = target.name,
                    fileName = metadata.name,
                    sizeBytes = metadata.sizeBytes,
                    direction = TransferDirection.SENDING,
                    rate = RateMeter(clock),
                )
        }
        refresh()
        return transferId
    }

    suspend fun accept(
        transferId: String,
        destination: File,
    ) {
        val record = record(transferId) ?: throw TransferProtocolException("Unknown transfer $transferId")
        val target = link(record.linkId) ?: throw TransferProtocolException("Link ${record.linkId} is gone")
        target.accept(transferId, destination)
        refresh()
    }

    suspend fun reject(
        transferId: String,
        reason: RejectReason,
    ) {
        val record = record(transferId) ?: throw TransferProtocolException("Unknown transfer $transferId")
        val target = link(record.linkId) ?: throw TransferProtocolException("Link ${record.linkId} is gone")
        target.reject(transferId, reason)
        refresh()
    }

    suspend fun cancel(
        transferId: String,
        error: TransferError = TransferError(TransferErrorCode.TRANSFER_CANCELLED, "Cancelled by user"),
    ) {
        val record = record(transferId) ?: return
        link(record.linkId)?.cancel(transferId, error)
        refresh()
    }

    suspend fun close() =
        withContext(NonCancellable) {
            if (closed) return@withContext
            closed = true
            ticker?.cancelAndJoin()
            val bound =
                synchronized(guard) {
                    val current = links.values.toList()
                    links.clear()
                    current
                }
            bound.forEach { it.close() }
        }

    fun snapshot(transferId: String): TransferSnapshot? = transfers.value.firstOrNull { it.transferId == transferId }

    fun refresh() {
        val snapshots =
            synchronized(guard) {
                records.values.map { record -> snapshotOf(record) }.reversed()
            }
        _transfers.value = snapshots
    }

    private fun snapshotOf(record: Record): TransferSnapshot {
        val phase = record.phase
        val bytes =
            when {
                phase is TransferPhase.Offered || phase is TransferPhase.Accepted -> 0L
                phase is TransferPhase.Verifying || phase is TransferPhase.Completed -> record.sizeBytes
                phase.isTerminal -> record.lastBytes
                else -> link(record.linkId)?.bytesTransferred(record.transferId) ?: record.lastBytes
            }
        val speed = record.rate.bytesPerSecond()
        val remaining = (record.sizeBytes - bytes).coerceAtLeast(0L)
        return TransferSnapshot(
            transferId = record.transferId,
            linkId = record.linkId,
            peerName = record.peerName,
            fileName = record.fileName,
            sizeBytes = record.sizeBytes,
            direction = record.direction,
            phase = phase,
            bytesTransferred = bytes,
            bytesPerSecond = speed,
            etaSeconds = if (speed > 0L && !phase.isTerminal) remaining / speed else null,
            error = record.error,
        )
    }

    private fun onLinkEvent(event: LinkEvent) {
        synchronized(guard) {
            when (event) {
                is LinkEvent.OfferReceived -> {
                    records[event.metadata.transferId] =
                        Record(
                            transferId = event.metadata.transferId,
                            linkId = event.linkId,
                            peerName = link(event.linkId)?.name ?: event.linkId,
                            fileName = event.metadata.name,
                            sizeBytes = event.metadata.sizeBytes,
                            direction = TransferDirection.RECEIVING,
                            rate = RateMeter(clock),
                        )
                }

                is LinkEvent.PhaseChanged -> {
                    records[event.transferId]?.let { record ->
                        record.phase = event.phase
                        record.error = event.error
                    }
                }
            }
        }
        refresh()
    }

    private fun startTicker() {
        ticker =
            scope.launch {
                while (true) {
                    delay(tickMillis.milliseconds)
                    sampleProgress()
                }
            }
    }

    private fun sampleProgress() {
        synchronized(guard) {
            for (record in records.values) {
                if (record.phase.isTerminal) continue
                val bytes = link(record.linkId)?.bytesTransferred(record.transferId) ?: record.lastBytes
                record.lastBytes = bytes
                record.rate.sample(bytes)
            }
        }
        refresh()
    }

    private fun record(transferId: String): Record? = synchronized(guard) { records[transferId] }

    private fun activeIds(): Set<String> =
        synchronized(guard) { links.values.flatMap { link -> link.activeTransferIds() }.toSet() }

    private class Record(
        val transferId: String,
        val linkId: String,
        val peerName: String,
        val fileName: String,
        val sizeBytes: Long,
        val direction: TransferDirection,
        val rate: RateMeter,
        var phase: TransferPhase = TransferPhase.Offered,
        var error: TransferError? = null,
        var lastBytes: Long = 0L,
    )

    private companion object {
        const val PROGRESS_TICK_MILLIS = 250L
    }
}
