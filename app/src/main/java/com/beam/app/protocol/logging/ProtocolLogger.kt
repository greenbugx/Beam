package com.beam.app.protocol.logging

fun interface ProtocolLogger {
    fun log(event: ProtocolLogEvent)
}

/** Discards every event; the default, so tests and embeddings stay silent. */
object NoopProtocolLogger : ProtocolLogger {
    override fun log(event: ProtocolLogEvent) = Unit
}
