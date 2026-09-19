package com.beam.app.protocol.transfer

/** Raised inside the transfer pipeline when a cancel fires. */
class TransferCancelledException(
    val error: TransferError,
) : Exception(error.detail.ifBlank { "Transfer cancelled (${error.code})" })
