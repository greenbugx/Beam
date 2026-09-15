package com.beam.app.protocol.transfer

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/** Raised inside the transfer pipeline when a cancel fires. */
class TransferCancelledException(
    val error: TransferError,
) : Exception(error.detail.ifBlank { "Transfer cancelled (${error.code})" })

class TransferCanceller {
    private val cancels = Channel<TransferError>(Channel.CONFLATED)

    fun cancel(error: TransferError) {
        cancels.trySend(error)
    }

    suspend fun <T> run(work: suspend () -> T): T =
        coroutineScope {
            select {
                async { work() }.onAwait { result -> result }
                cancels.onReceive { error -> throw TransferCancelledException(error) }
            }
        }
}
