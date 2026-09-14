package com.beam.app.protocol

class Frame(
    val type: FrameType,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = type.hashCode() * 31 + payload.contentHashCode()

    override fun toString(): String = "Frame(type=$type, payloadBytes=${payload.size})"
}

enum class FrameType(
    val value: Byte,
) {
    /** Envelope JSON (UTF-8) */
    CTRL(0x01),

    /** Chunk header (28 bytes, fixed) + raw chunk bytes. */
    DATA(0x02),

    /** Immediate link close, empty payload. */
    CLOSE(0x03),
    ;

    companion object {
        fun from(value: Byte): FrameType? = entries.firstOrNull { it.value == value }
    }
}
