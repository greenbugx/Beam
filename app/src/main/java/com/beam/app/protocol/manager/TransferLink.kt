package com.beam.app.protocol.manager

import com.beam.app.protocol.MessageEnvelope
import com.beam.app.protocol.session.LinkSession
import com.beam.app.protocol.session.LinkState
import com.beam.app.protocol.session.SessionCapabilities
import com.beam.app.protocol.session.SessionCloseReason
import com.beam.app.protocol.session.SessionEvent
import com.beam.app.protocol.transfer.ChunkAckBody
import com.beam.app.protocol.transfer.ChunkReceiver
import com.beam.app.protocol.transfer.ChunkSender
import com.beam.app.protocol.transfer.CompletionStatus
import com.beam.app.protocol.transfer.FileMetadata
import com.beam.app.protocol.transfer.FileRejectBody
import com.beam.app.protocol.transfer.FilenameSanitizer
import com.beam.app.protocol.transfer.OfferDecisionCache
import com.beam.app.protocol.transfer.ReceiveEndStatus
import com.beam.app.protocol.transfer.RejectReason
import com.beam.app.protocol.transfer.TransferCancelBody
import com.beam.app.protocol.transfer.TransferCancelledException
import com.beam.app.protocol.transfer.TransferCompleter
import com.beam.app.protocol.transfer.TransferEndBody
import com.beam.app.protocol.transfer.TransferError
import com.beam.app.protocol.transfer.TransferErrorBody
import com.beam.app.protocol.transfer.TransferErrorCode
import com.beam.app.protocol.transfer.TransferMessageTypes
import com.beam.app.protocol.transfer.TransferPhase
import com.beam.app.protocol.transfer.TransferProtocolException
import com.beam.app.protocol.transfer.TransferStartBody
import com.beam.app.protocol.transfer.TransferStorageException
import com.beam.app.protocol.transfer.TransferTimeoutException
import com.beam.app.protocol.transfer.TransferTimeouts
import com.beam.app.protocol.transfer.TransferVerifiedBody
import com.beam.app.protocol.transfer.VerifyFailedBody
import com.beam.app.protocol.transfer.decodeTransferBody
import com.beam.app.protocol.transfer.isTerminal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

internal sealed interface LinkEvent {
    val linkId: String

    data class OfferReceived(
        override val linkId: String,
        val metadata: FileMetadata,
    ) : LinkEvent

    data class PhaseChanged(
        override val linkId: String,
        val transferId: String,
        val phase: TransferPhase,
        val error: TransferError?,
    ) : LinkEvent
}

private class ReceiverBundle(
    val receiver: ChunkReceiver,
    val completer: TransferCompleter,
    val destination: File,
)

internal class TransferLink(
    private val session: LinkSession,
    val linkId: String,
    private var peerName: String,
    private val tempDir: File,
    private val timeouts: TransferTimeouts,
    private val window: Int,
    scope: CoroutineScope,
    private val onEvent: (LinkEvent) -> Unit,
) {
    private class Watchdog(
        val wake: Channel<Unit> = Channel(Channel.CONFLATED),
        val job: Job,
    )

    private val job = Job(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + job)
    private val senders = mutableMapOf<String, ChunkSender>()
    private val receivers = mutableMapOf<String, ReceiverBundle>()
    private val pendingOffers = mutableMapOf<String, FileMetadata>()
    private val decisions = OfferDecisionCache()
    private val phases = mutableMapOf<String, TransferPhase>()
    private val errors = mutableMapOf<String, TransferError>()
    private val timers = mutableMapOf<String, Job>()
    private val watchdogs = mutableMapOf<String, Watchdog>()

    private var capabilities: Set<String> = emptySet()

    private var invalidBodies = 0

    private var closed = false

    val name: String get() = peerName

    /** Starts routing this link's session traffic into the transfer layer. */
    fun start() {
        scope.launch {
            for (event in session.events) {
                handle(event)
            }
        }
        scope.launch {
            session.state.first { it is LinkState.Closed }
            onLinkClosed()
        }
    }

    /** Joins owned workers before releasing engines. The caller owns the session. */
    suspend fun close() =
        withContext(NonCancellable) {
            if (closed) return@withContext
            closed = true
            job.cancelAndJoin()
            for (id in activeTransferIds()) {
                val error = TransferError(TransferErrorCode.TRANSFER_CANCELLED, "Transfer link closed")
                if (phases[id] == TransferPhase.Accepted || phases[id] == TransferPhase.Verifying) {
                    // These phases have no user-cancel edge; terminate with a local failure.
                    receivers[id]?.receiver?.onLocalFailure(TransferErrorCode.CONNECTION_LOST, "Transfer link closed")
                    senders[id]?.onLocalFailure(TransferErrorCode.CONNECTION_LOST, "Transfer link closed")
                    errors[id] = TransferError(TransferErrorCode.CONNECTION_LOST, "Transfer link closed")
                    syncPhase(id)
                } else {
                    cancel(id, error)
                }
            }
            receivers.values.forEach { it.receiver.abandon() }
            timers.clear()
            watchdogs.clear()
            senders.clear()
            receivers.clear()
            pendingOffers.clear()
        }

    fun supportsChunking(): Boolean = capabilities.contains(SessionCapabilities.CHUNKING)

    fun phaseOf(transferId: String): TransferPhase? = phases[transferId]

    fun errorOf(transferId: String): TransferError? = errors[transferId]

    fun bytesTransferred(transferId: String): Long =
        senders[transferId]?.bytesAcked ?: receivers[transferId]?.receiver?.bytesReceived ?: 0L

    fun activeTransferIds(): Set<String> = phases.filterValues { !it.isTerminal }.keys.toSet()

    fun pendingOfferIds(): Set<String> = pendingOffers.keys.toSet()

    fun metadataOf(transferId: String): FileMetadata? = pendingOffers[transferId]

    suspend fun offer(
        metadata: FileMetadata,
        source: FileSource,
    ): String {
        if (!supportsChunking()) {
            throw TransferProtocolException("Peer does not advertise CHUNKING (Section 9)")
        }
        val active = activeTransferIds().firstOrNull()
        if (active != null) {
            throw TransferProtocolException("Link $linkId already has an active transfer ($active)")
        }
        val wire = LinkTransferWire(session, metadata.transferId)
        val sender = ChunkSender(metadata, { source.open() }, wire, window, timeouts)
        senders[metadata.transferId] = sender
        phases[metadata.transferId] = TransferPhase.Offered
        wire.sendOffer(metadata)
        startTimer(metadata.transferId, timeouts.offerMillis) {
            sender.onOfferExpired()
            if (!sender.state.isTerminal) {
                errors[metadata.transferId] =
                    TransferError(TransferErrorCode.TRANSFER_TIMEOUT, "Offer window elapsed with no answer")
            }
            syncPhase(metadata.transferId)
        }
        emit(metadata.transferId)
        return metadata.transferId
    }

    suspend fun accept(
        transferId: String,
        destination: File,
    ) {
        val metadata =
            pendingOffers.remove(transferId)
                ?: throw TransferProtocolException("No pending offer $transferId")
        cancelTimer(transferId)
        decisions.record(transferId, OfferDecisionCache.Decision.ACCEPTED)
        val wire = LinkTransferWire(session, transferId)
        val receiver =
            ChunkReceiver(metadata, tempDir, wire) {
                errors.remove(transferId)
                watchdogs[transferId]?.wake?.trySend(Unit)
            }
        receivers[transferId] =
            ReceiverBundle(
                receiver = receiver,
                completer = TransferCompleter(metadata, tempDir, wire, state = receiver.state),
                destination = destination,
            )
        phases[transferId] = TransferPhase.Accepted
        wire.sendAccept()
        if (receiver.state.phase !is TransferPhase.Accepted) return
        startTimer(transferId, timeouts.acceptToStartMillis) {
            if (receiver.state.phase !is TransferPhase.Accepted) return@startTimer
            receiver.onAcceptToStartExpired()
            errors[transferId] =
                TransferError(TransferErrorCode.TRANSFER_TIMEOUT, "No TRANSFER_START within the accept-to-start window")
            syncPhase(transferId)
        }
        emit(transferId)
    }

    suspend fun reject(
        transferId: String,
        reason: RejectReason,
    ) {
        if (pendingOffers.remove(transferId) == null) return
        cancelTimer(transferId)
        decisions.record(transferId, OfferDecisionCache.Decision.REJECTED, reason)
        phases[transferId] = TransferPhase.Rejected
        errors[transferId] = TransferError(errorCodeFor(reason), "Rejected: ${reason.name}")
        emit(transferId)
        LinkTransferWire(session, transferId).sendReject(reason)
    }

    suspend fun cancel(
        transferId: String,
        error: TransferError,
    ) {
        if (phases[transferId]?.isTerminal != false) return
        if (pendingOffers.remove(transferId) != null) {
            cancelTimer(transferId)
            phases[transferId] = TransferPhase.Cancelled
            errors[transferId] = error
            decisions.record(transferId, OfferDecisionCache.Decision.REJECTED)
            emit(transferId)
            runCatching {
                LinkTransferWire(session, transferId).sendCancel(
                    TransferCancelBody(transferId, error.code, error.detail.ifBlank { null }),
                )
            }
            return
        }
        senders[transferId]?.cancel(error)
        receivers[transferId]?.receiver?.cancel(error)
        val phase = senders[transferId]?.state?.phase ?: receivers[transferId]?.receiver?.state?.phase
        if (phase != TransferPhase.Cancelled) return
        pendingOffers.remove(transferId)
        cancelTimer(transferId)
        errors[transferId] = error
        syncPhase(transferId)
    }

    private suspend fun handle(event: SessionEvent) {
        try {
            dispatch(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handleRoutingFailure(event, e)
        }
    }

    private suspend fun dispatch(event: SessionEvent) {
        when (event) {
            is SessionEvent.HandshakeCompleted -> {
                capabilities = event.negotiatedCapabilities
                peerName = event.remote.deviceName
            }

            is SessionEvent.ControlReceived -> {
                handleControl(event.envelope)
            }

            is SessionEvent.ChunkReceived -> {
                val transferId = event.header.transferId.toString()
                val receiver = receivers[transferId]?.receiver
                if (receiver != null &&
                    (
                        receiver.state.phase is TransferPhase.Transferring ||
                            (receiver.state.phase is TransferPhase.Paused && watchdogs.containsKey(transferId))
                    )
                ) {
                    receiver.onChunk(event.header, event.payload)
                    syncPhase(transferId)
                }
            }

            else -> {
                Unit
            }
        }
    }

    private suspend fun handleControl(envelope: MessageEnvelope) {
        if (envelope.type !in TransferMessageTypes.ALL) return
        val transferId = envelope.transferId ?: return
        if (envelope.type != TransferMessageTypes.OFFER && phases[transferId]?.isTerminal == true) return
        when (envelope.type) {
            TransferMessageTypes.OFFER -> {
                handleOffer(envelope)
            }

            TransferMessageTypes.ACCEPT -> {
                handleAccept(transferId)
            }

            TransferMessageTypes.REJECT -> {
                handleReject(envelope, transferId)
            }

            TransferMessageTypes.START -> {
                handleStart(envelope, transferId)
            }

            TransferMessageTypes.ACK -> {
                senders[transferId]?.onAck(
                    envelope.body.decodeTransferBody(ChunkAckBody.serializer(), envelope.type),
                )
                syncPhase(transferId)
            }

            TransferMessageTypes.END -> {
                handleEnd(envelope, transferId)
            }

            TransferMessageTypes.VERIFIED -> {
                senders[transferId]?.onVerified(
                    envelope.body.decodeTransferBody(TransferVerifiedBody.serializer(), envelope.type),
                )
                syncPhase(transferId)
            }

            TransferMessageTypes.VERIFY_FAILED -> {
                senders[transferId]?.onVerifyFailed(
                    envelope.body.decodeTransferBody(VerifyFailedBody.serializer(), envelope.type),
                )
                errors[transferId] = TransferError(TransferErrorCode.HASH_MISMATCH, "Receiver reported a mismatch")
                syncPhase(transferId)
            }

            TransferMessageTypes.CANCEL -> {
                handleCancel(envelope, transferId)
            }

            TransferMessageTypes.ERROR -> {
                handleError(envelope, transferId)
            }
        }
    }

    private suspend fun handleOffer(envelope: MessageEnvelope) {
        val offered = envelope.body.decodeTransferBody(FileMetadata.serializer(), envelope.type)
        val transferId = offered.transferId
        val cached = decisions.decisionFor(transferId)
        if (cached != null) {
            val wire = LinkTransferWire(session, transferId)
            if (cached == OfferDecisionCache.Decision.ACCEPTED) {
                wire.sendAccept()
            } else {
                wire.sendReject(checkNotNull(decisions.rejectionReasonFor(transferId)))
            }
            return
        }
        if (pendingOffers.containsKey(transferId)) return
        val metadata = offered.copy(name = FilenameSanitizer.sanitize(offered.name))
        val invalid = metadata.validate()
        if (invalid.isNotEmpty()) {
            refuseOffer(
                transferId,
                RejectReason.INVALID_METADATA,
                TransferErrorCode.INVALID_METADATA,
                invalid.joinToString(),
            )
            return
        }
        if (!supportsChunking()) {
            refuseOffer(
                transferId,
                RejectReason.UNSUPPORTED,
                TransferErrorCode.UNSUPPORTED_FEATURE,
                "Peer lacks CHUNKING",
            )
            return
        }
        if (activeTransferIds().isNotEmpty()) {
            refuseOffer(transferId, RejectReason.BUSY, TransferErrorCode.RESOURCE_EXHAUSTED, "Link is busy")
            return
        }
        pendingOffers[transferId] = metadata
        phases[transferId] = TransferPhase.Offered
        startTimer(transferId, timeouts.offerMillis) {
            if (pendingOffers.remove(transferId) != null) {
                decisions.record(transferId, OfferDecisionCache.Decision.REJECTED, RejectReason.EXPIRED)
                LinkTransferWire(session, transferId).sendReject(RejectReason.EXPIRED)
                phases[transferId] = TransferPhase.Expired
                errors[transferId] =
                    TransferError(TransferErrorCode.TRANSFER_TIMEOUT, "Offer window elapsed with no answer")
                emit(transferId)
            }
        }
        onEvent(LinkEvent.OfferReceived(linkId, metadata))
    }

    private suspend fun refuseOffer(
        transferId: String,
        reason: RejectReason,
        code: TransferErrorCode,
        detail: String,
    ) {
        decisions.record(transferId, OfferDecisionCache.Decision.REJECTED, reason)
        LinkTransferWire(session, transferId).sendReject(reason)
        phases[transferId] = TransferPhase.Rejected
        errors[transferId] = TransferError(code, detail)
        emit(transferId)
    }

    private fun handleAccept(transferId: String) {
        val sender = senders[transferId] ?: return
        if (sender.state.phase !is TransferPhase.Offered) return
        cancelTimer(transferId)
        launchSend(sender, transferId)
    }

    private suspend fun handleReject(
        envelope: MessageEnvelope,
        transferId: String,
    ) {
        val body = envelope.body.decodeTransferBody(FileRejectBody.serializer(), envelope.type)
        cancelTimer(transferId)
        senders[transferId]?.onOfferRejected()
        errors[transferId] = TransferError(errorCodeFor(body.reason), "Rejected: ${body.reason.name}")
        syncPhase(transferId)
    }

    private suspend fun handleStart(
        envelope: MessageEnvelope,
        transferId: String,
    ) {
        val bundle = receivers[transferId] ?: return
        if (bundle.receiver.state.phase !is TransferPhase.Accepted) return
        bundle.receiver.onStart(envelope.body.decodeTransferBody(TransferStartBody.serializer(), envelope.type))
        cancelTimer(transferId)
        startReceiverWatchdog(transferId)
        syncPhase(transferId)
    }

    private suspend fun handleEnd(
        envelope: MessageEnvelope,
        transferId: String,
    ) {
        val bundle = receivers[transferId] ?: return
        val body = envelope.body.decodeTransferBody(TransferEndBody.serializer(), envelope.type)
        if (bundle.receiver.onEnd(body) != ReceiveEndStatus.READY_TO_VERIFY) {
            syncPhase(transferId)
            return
        }
        stopReceiverWatchdog(transferId)
        if (bundle.completer.complete(bundle.destination) == CompletionStatus.VERIFY_FAILED) {
            errors[transferId] = TransferError(TransferErrorCode.HASH_MISMATCH, "Verification failed")
        }
        syncPhase(transferId)
    }

    private suspend fun handleCancel(
        envelope: MessageEnvelope,
        transferId: String,
    ) {
        val body = envelope.body.decodeTransferBody(TransferCancelBody.serializer(), envelope.type)
        val hadEngine = senders.containsKey(transferId) || receivers.containsKey(transferId)
        stopReceiverWatchdog(transferId)
        senders[transferId]?.onPeerCancel(body)
        receivers[transferId]?.receiver?.onPeerCancel(body)
        val error = TransferError(body.code, body.detail ?: "Peer cancelled")
        if (!settled(transferId)) {
            senders[transferId]?.terminate(error)
            receivers[transferId]?.receiver?.terminate(error)
        }
        errors[transferId] = error
        pendingOffers.remove(transferId)
        cancelTimer(transferId)
        if (!hadEngine) {
            decisions.record(transferId, OfferDecisionCache.Decision.REJECTED)
            phases[transferId] = TransferPhase.Cancelled
        }
        syncPhase(transferId)
    }

    private suspend fun handleError(
        envelope: MessageEnvelope,
        transferId: String,
    ) {
        val body = envelope.body.decodeTransferBody(TransferErrorBody.serializer(), envelope.type)
        stopReceiverWatchdog(transferId)
        senders[transferId]?.onPeerError(body)
        receivers[transferId]?.receiver?.onPeerError(body)
        val error = TransferError(body.code, body.detail ?: "Peer reported an error")
        // Offered senders have no FatalError edge of their own
        if (!settled(transferId)) {
            senders[transferId]?.terminate(error)
            receivers[transferId]?.receiver?.terminate(error)
        }
        errors[transferId] = error
        if (pendingOffers.remove(transferId) != null) {
            decisions.record(transferId, OfferDecisionCache.Decision.REJECTED)
            phases[transferId] = TransferPhase.Failed
        }
        cancelTimer(transferId)
        syncPhase(transferId)
    }

    private fun settled(transferId: String): Boolean =
        senders[transferId]?.state?.isTerminal == true ||
            receivers[transferId]?.receiver?.state?.isTerminal == true

    private suspend fun onLinkClosed() {
        if (closed) return
        for (transferId in activeTransferIds()) {
            stopReceiverWatchdog(transferId)
            senders[transferId]?.onLinkLost()
            receivers[transferId]?.receiver?.onLinkLost()
            errors[transferId] = TransferError(TransferErrorCode.CONNECTION_LOST, "Link lost")
            syncPhase(transferId)
            startTimer(transferId, timeouts.reconnectWindowMillis) {
                senders[transferId]?.onReconnectWindowExpired()
                receivers[transferId]?.receiver?.onReconnectWindowExpired()
                errors[transferId] =
                    TransferError(TransferErrorCode.CONNECTION_LOST, "Link lost and the reconnect window elapsed")
                syncPhase(transferId)
            }
        }
    }

    private suspend fun handleRoutingFailure(
        event: SessionEvent,
        failure: Throwable,
    ) {
        val transferId = transferIdOf(event) ?: return
        if (failure is TransferProtocolException) {
            invalidBodies += 1
            if (invalidBodies >= MAX_INVALID_BODIES) {
                session.close(SessionCloseReason.INVALID_MESSAGE)
            }
            return
        }
        val code =
            when (failure) {
                is TransferStorageException -> TransferErrorCode.INSUFFICIENT_STORAGE
                is TransferTimeoutException -> TransferErrorCode.TRANSFER_TIMEOUT
                else -> TransferErrorCode.INTERNAL_ERROR
            }
        val detail = failure.message ?: "Transfer failed"
        errors[transferId] = TransferError(code, detail)
        senders[transferId]?.onLocalFailure(code, detail)
        receivers[transferId]?.receiver?.onLocalFailure(code, detail)
        syncPhase(transferId)
    }

    private fun transferIdOf(event: SessionEvent): String? =
        when (event) {
            is SessionEvent.ControlReceived -> event.envelope.transferId
            is SessionEvent.ChunkReceived -> event.header.transferId.toString()
            else -> null
        }

    private fun launchSend(
        sender: ChunkSender,
        transferId: String,
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                sender.run()
            } catch (e: TransferCancelledException) {
                errors.putIfAbsent(transferId, e.error)
            } catch (e: TransferTimeoutException) {
                errors[transferId] =
                    TransferError(TransferErrorCode.TRANSFER_TIMEOUT, e.message ?: "Transfer timed out")
            } catch (e: Throwable) {
                errors[transferId] = TransferError(TransferErrorCode.INTERNAL_ERROR, e.message ?: "Transfer failed")
            } finally {
                syncPhase(transferId)
            }
        }
    }

    private fun startReceiverWatchdog(transferId: String) {
        stopReceiverWatchdog(transferId)
        val receiver = receivers[transferId]?.receiver ?: return
        val wake = Channel<Unit>(Channel.CONFLATED)
        val worker =
            scope.launch(start = CoroutineStart.LAZY) {
                var consecutiveExpiries = 0
                while (receiver.state.phase == TransferPhase.Transferring ||
                    receiver.state.phase == TransferPhase.Paused
                ) {
                    try {
                        timeouts.inactivity("No receiver chunk progress") { wake.receive() }
                        consecutiveExpiries = 0
                    } catch (e: TransferTimeoutException) {
                        // Progress queued at the deadline wins over a stale expiry.
                        if (wake.tryReceive().isSuccess) {
                            consecutiveExpiries = 0
                            continue
                        }
                        if (receiver.state.isTerminal || receiver.state.phase == TransferPhase.Verifying) return@launch
                        consecutiveExpiries++
                        val detail =
                            "No receiver chunk progress ($consecutiveExpiries consecutive inactivity expiries)"
                        errors[transferId] = TransferError(TransferErrorCode.TRANSFER_TIMEOUT, detail)
                        if (consecutiveExpiries >= timeouts.maxConsecutiveInactivity) {
                            // Remove ownership before syncPhase so it does not cancel this error send.
                            watchdogs.remove(transferId)
                            receiver.onLocalFailure(TransferErrorCode.TRANSFER_TIMEOUT, detail)
                            syncPhase(transferId)
                            return@launch
                        }
                        receiver.onInactivityExpired()
                        syncPhase(transferId)
                    }
                }
            }
        watchdogs[transferId] = Watchdog(wake, worker)
        worker.start()
    }

    private fun stopReceiverWatchdog(transferId: String) {
        watchdogs.remove(transferId)?.job?.cancel()
    }

    private fun startTimer(
        transferId: String,
        millis: Long,
        action: suspend () -> Unit,
    ) {
        timers.remove(transferId)?.cancel()
        timers[transferId] =
            scope.launch {
                delay(millis.milliseconds)
                action()
                timers.remove(transferId)
            }
    }

    private fun cancelTimer(transferId: String) {
        timers.remove(transferId)?.cancel()
    }

    private fun syncPhase(transferId: String) {
        val enginePhase = senders[transferId]?.state?.phase ?: receivers[transferId]?.receiver?.state?.phase
        if (enginePhase != null) phases[transferId] = enginePhase
        if (enginePhase?.isTerminal == true || enginePhase == TransferPhase.Verifying) {
            stopReceiverWatchdog(transferId)
        }
        emit(transferId)
    }

    private fun emit(transferId: String) {
        val phase = phases[transferId] ?: return
        onEvent(LinkEvent.PhaseChanged(linkId, transferId, phase, errors[transferId]))
    }

    private fun errorCodeFor(reason: RejectReason): TransferErrorCode =
        when (reason) {
            RejectReason.USER_REJECTED, RejectReason.DUPLICATE_SUSPECTED -> TransferErrorCode.TRANSFER_REJECTED
            RejectReason.BUSY, RejectReason.RESOURCE_EXHAUSTED -> TransferErrorCode.RESOURCE_EXHAUSTED
            RejectReason.INSUFFICIENT_STORAGE -> TransferErrorCode.INSUFFICIENT_STORAGE
            RejectReason.INVALID_METADATA -> TransferErrorCode.INVALID_METADATA
            RejectReason.UNSUPPORTED -> TransferErrorCode.UNSUPPORTED_FEATURE
            RejectReason.EXPIRED -> TransferErrorCode.TRANSFER_TIMEOUT
        }

    private companion object {
        const val MAX_INVALID_BODIES = 2
    }
}
