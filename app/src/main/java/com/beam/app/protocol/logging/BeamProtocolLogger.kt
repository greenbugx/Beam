package com.beam.app.protocol.logging

import android.util.Log
import com.beam.app.protocol.transfer.TransferErrorCode
import java.util.concurrent.atomic.AtomicBoolean

object BeamProtocolLogger : ProtocolLogger {
    private const val TAG = "BeamProto"

    private const val KEEP = 4

    private val sampled = AtomicBoolean(false)
    private val malformedLines = mutableMapOf<String, ArrayDeque<String>>()
    private val guard = Any()

    override fun log(event: ProtocolLogEvent) {
        val line = event.toLogLine()
        Log.d(TAG, line)
        if (event.errorCode == TransferErrorCode.INVALID_MESSAGE) {
            retain(event.sessionId ?: "", line)
        }
        sampled.set(true)
    }

    /** Rendered M-events per session, for post-mortem diagnostics. */
    fun recentMalformed(sessionId: String): List<String> =
        synchronized(guard) {
            malformedLines[sessionId]?.toList().orEmpty()
        }

    /** True once any protocol event has been logged. */
    fun hasLogged(): Boolean = sampled.get()

    private fun retain(
        sessionId: String,
        line: String,
    ) {
        synchronized(guard) {
            val deque = malformedLines.getOrPut(sessionId) { ArrayDeque(KEEP) }
            if (deque.size == KEEP) deque.removeFirst()
            deque.addLast(line)
        }
    }
}
