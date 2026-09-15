package com.beam.app.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

object FrameCodec {
    /** Header size: 4-byte big-endian length + 1-byte type. */
    const val HEADER_SIZE = 5

    const val MAX_CONTROL_PAYLOAD = 64 * 1024
    const val MAX_CHUNK_SIZE = 1024 * 1024

    const val CHUNK_HEADER_SIZE = 28

    const val MAX_DATA_PAYLOAD = MAX_CHUNK_SIZE + CHUNK_HEADER_SIZE

    fun encode(frame: Frame): ByteArray {
        validatePayloadLimits(frame.type, frame.payload.size)
        val out = ByteArray(HEADER_SIZE + frame.payload.size)
        writeUnsignedInt(out, 0, frame.payload.size)
        out[4] = frame.type.value
        frame.payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** Decodes one frame from an in-memory wire buffer. */
    fun decode(bytes: ByteArray): Frame = decode(bytes, 0, bytes.size)

    /**
     * Decodes one frame from a region of [bytes] starting at [offset].
     *
     * The region must hold at least one complete frame;
     *
     * bytes after that frame are ignored, so call `decodeStream` for consecutive frames on a
     * real transport stream.
     *
     * @throws FrameCodecException if the region does not hold a valid frame.
     */
    fun decode(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Frame {
        if (length < HEADER_SIZE) {
            throw FrameCodecException(FrameCodecKind.TRUNCATED, "Expected at least $HEADER_SIZE bytes, got $length")
        }
        val payloadLength = readUnsignedInt(bytes, offset)
        if (payloadLength < 0) {
            throw FrameCodecException(
                FrameCodecKind.OVERSIZE,
                "Payload length ${payloadLength.toUInt()} exceeds any legal frame",
            )
        }
        val typeByte = bytes[offset + 4]
        val type =
            FrameType.from(typeByte)
                ?: throw FrameCodecException(
                    FrameCodecKind.UNKNOWN_TYPE,
                    "Unknown frame type 0x%02x".format(typeByte),
                )
        if (payloadLength > length - HEADER_SIZE) {
            throw FrameCodecException(
                FrameCodecKind.TRUNCATED,
                "Payload length $payloadLength exceeds remaining ${length - HEADER_SIZE}",
            )
        }
        validatePayloadLimits(type, payloadLength)
        return Frame(type, bytes.copyOfRange(offset + HEADER_SIZE, offset + HEADER_SIZE + payloadLength))
    }

    /** Max payload length accepted for [type]. */
    fun maxPayloadFor(type: FrameType): Int =
        when (type) {
            FrameType.CTRL -> MAX_CONTROL_PAYLOAD
            FrameType.DATA -> MAX_DATA_PAYLOAD
            FrameType.CLOSE -> 0
        }

    fun decodeStream(input: InputStream): Frame {
        val data = DataInputStream(input)
        var headerRead = false
        try {
            val payloadLength = data.readInt()
            headerRead = true
            if (payloadLength < 0) {
                throw FrameCodecException(FrameCodecKind.INVALID, "Negative payload length $payloadLength")
            }
            val typeByte = data.readByte()
            val type =
                FrameType.from(typeByte)
                    ?: throw FrameCodecException(
                        FrameCodecKind.UNKNOWN_TYPE,
                        "Unknown frame type 0x%02x".format(typeByte),
                    )
            validatePayloadLimits(type, payloadLength)
            val payload = ByteArray(payloadLength)
            data.readFully(payload)
            return Frame(type, payload)
        } catch (e: EOFException) {
            throw FrameCodecException(
                FrameCodecKind.TRUNCATED,
                if (headerRead) "Stream ended mid-payload" else "Stream ended before frame length",
                e,
            )
        } catch (e: FrameCodecException) {
            throw e
        } catch (e: IOException) {
            throw FrameCodecException(FrameCodecKind.IO, "I/O failure while reading frame", e)
        }
    }

    private fun validatePayloadLimits(
        type: FrameType,
        payloadLength: Int,
    ) {
        if (type == FrameType.CLOSE && payloadLength != 0) {
            throw FrameCodecException(FrameCodecKind.INVALID, "CLOSE frames must be empty, got $payloadLength bytes")
        }
        val max = maxPayloadFor(type)
        if (payloadLength > max) {
            throw FrameCodecException(FrameCodecKind.OVERSIZE, "$type payload $payloadLength exceeds max $max")
        }
    }

    private fun writeUnsignedInt(
        out: ByteArray,
        offset: Int,
        value: Int,
    ) {
        require(value >= 0) { "Length must be a non-negative u32, was $value" }
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun readUnsignedInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}

enum class FrameCodecKind {
    /** Stream or buffer ended before a complete frame. */
    TRUNCATED,

    /** Payload exceeds the per-type maximum. */
    OVERSIZE,

    /** Frame type byte is not a known [FrameType]. */
    UNKNOWN_TYPE,

    /** Length bounds violated (negative length) or CLOSE not empty. */
    INVALID,

    /** Underlying I/O failure (streaming decode only). */
    IO,
}

class FrameCodecException(
    val kind: FrameCodecKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
