package com.beam.app.protocol.logging

import com.beam.app.protocol.MessageEnvelope
import com.beam.app.protocol.session.SessionCloseReason
import com.beam.app.protocol.transfer.FilenameSanitizer
import com.beam.app.protocol.transfer.TransferErrorCode
import com.beam.app.protocol.transfer.TransferPhase
import java.security.MessageDigest
import java.util.Locale

enum class LogCategory {
    SESSION,
    TRANSFER,
    ERROR,
}

enum class LogDirection {
    /** Received from the peer. */
    IN,

    /** Sent to the peer. */
    OUT,

    /** Local lifecycle, no message involved. */
    LOCAL,
}

data class ProtocolLogEvent(
    val category: LogCategory,
    val direction: LogDirection = LogDirection.LOCAL,
    /** Raw session id; redacted to a fingerprint because ours embeds the Beam code. */
    val sessionId: String? = null,
    /** Raw device id; non-secret, so it is only shortened. */
    val deviceId: String? = null,
    val messageType: String? = null,
    val messageId: String? = null,
    val transferId: String? = null,
    val phase: TransferPhase? = null,
    /** Session-level state label, e.g. `ACTIVE`, `LIVE`, `CLOSED`. */
    val state: String? = null,
    val ranges: List<LongRange> = emptyList(),
    val bytesTransferred: Long? = null,
    val bytesTotal: Long? = null,
    val errorCode: TransferErrorCode? = null,
    val closeReason: SessionCloseReason? = null,
    val expectedHash: String? = null,
    val actualHash: String? = null,
    val fileName: String? = null,
    val detail: String? = null,
) {
    /** True when this event carries a full-digest comparison. */
    val carriesFullHashes: Boolean get() = errorCode == TransferErrorCode.HASH_MISMATCH

    fun toLogLine(): String {
        val fields = mutableListOf("dir=${direction.name}")
        sessionId?.let { fields += "sid=${ProtocolLogRedaction.fingerprint(it)}" }
        deviceId?.let { fields += "did=${ProtocolLogRedaction.short(it)}" }
        messageType?.let { fields += "MSG=$it" }
        messageId?.let { fields += "mid=$it" }
        transferId?.let { fields += "tid=${ProtocolLogRedaction.short(it)}" }
        renderState()?.let { fields += "state=$it" }
        if (ranges.isNotEmpty()) fields += "chunks=${ProtocolLogRedaction.ranges(ranges)}"
        renderBytes()?.let { fields += "bytes=$it" }
        fileName?.let { fields += "name=${ProtocolLogRedaction.name(it)}" }
        closeReason?.let { fields += "reason=$it" }
        errorCode?.let { fields += it.name }
        renderHashes()?.let { fields += it }
        detail?.let { fields += "detail=${ProtocolLogRedaction.paths(it)}" }

        val category = category.name.padEnd(CATEGORY_WIDTH)
        return "${MessageEnvelope.PROTOCOL_VERSION} | $category | ${fields.joinToString(" | ")}"
    }

    private fun renderState(): String? = phase?.let { it::class.simpleName?.uppercase(Locale.ROOT) } ?: state

    private fun renderBytes(): String? =
        when {
            bytesTransferred == null && bytesTotal == null -> null
            bytesTransferred == null -> ProtocolLogRedaction.bytes(bytesTotal ?: 0L)
            bytesTotal == null -> ProtocolLogRedaction.bytes(bytesTransferred)
            else -> "${ProtocolLogRedaction.bytes(bytesTransferred)}/${ProtocolLogRedaction.bytes(bytesTotal)}"
        }

    private fun renderHashes(): String? {
        val expected = expectedHash ?: return null
        val actual = actualHash ?: return null
        val render: (String) -> String =
            if (carriesFullHashes) {
                { it }
            } else {
                { ProtocolLogRedaction.hash(it) }
            }
        return "expected=${render(expected)} actual=${render(actual)}"
    }

    private companion object {
        const val CATEGORY_WIDTH = 8
    }
}

internal object ProtocolLogRedaction {
    /** Cap on rendered chunk ranges, so one line can never balloon. */
    const val MAX_RANGES = 8

    /**
     * 8-hex SHA-256 fingerprint.
     *
     * Used for the session id: ours is `BS-<beam code>`, so logging it raw would
     * publish the very code that authorizes a join. A fingerprint keeps log
     * correlation without being reversible into the code.
     */
    fun fingerprint(value: String): String = sha256Hex(value).take(FINGERPRINT_CHARS)

    /** Short prefix + ellipsis, for identifiers that carry no secret. */
    fun short(value: String): String = if (value.length <= SHORT_CHARS) value else value.take(SHORT_CHARS) + ELLIPSIS

    /** Abbreviated digest, for every line except the mismatch comparison. */
    fun hash(value: String): String = if (value.length <= SHORT_CHARS) value else value.take(SHORT_CHARS) + ELLIPSIS

    /** Filenames are logged only in sanitized form - never as a path. */
    fun name(value: String): String = FilenameSanitizer.sanitize(value)

    /** Replaces path-like tokens so no filesystem location reaches a log. */
    fun paths(text: String): String = PATH_LIKE.replace(text, REDACTED_PATH)

    fun ranges(ranges: List<LongRange>): String {
        val shown =
            ranges.take(MAX_RANGES).joinToString(separator = ",", prefix = "[", postfix = "]") {
                "[${it.first},${it.last}]"
            }
        val hidden = ranges.size - MAX_RANGES
        return if (hidden > 0) "$shown+$hidden" else shown
    }

    fun bytes(value: Long): String {
        if (value < KIB) return "${value}B"
        var scaled = value.toDouble() / KIB
        var unit = 0
        while (scaled >= KIB && unit < UNITS.lastIndex) {
            scaled /= KIB
            unit += 1
        }
        return String.format(Locale.ROOT, "%.1f%s", scaled, UNITS[unit])
    }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val FINGERPRINT_CHARS = 8
    private const val SHORT_CHARS = 8
    private const val ELLIPSIS = "…"
    private const val KIB = 1024
    private const val REDACTED_PATH = "<path>"
    private val UNITS = listOf("KB", "MB", "GB", "TB")
    private val PATH_LIKE = Regex("""\S*/\S*""")
}
