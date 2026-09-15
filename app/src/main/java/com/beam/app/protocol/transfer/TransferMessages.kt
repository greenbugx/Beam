package com.beam.app.protocol.transfer

import com.beam.app.protocol.MessageEnvelope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

object TransferMessageTypes {
    const val OFFER = "FILE_OFFER"
    const val ACCEPT = "FILE_ACCEPT"
    const val REJECT = "FILE_REJECT"
    const val START = "TRANSFER_START"
    const val ACK = "CHUNK_ACK"
    const val END = "TRANSFER_END"
    const val VERIFIED = "TRANSFER_VERIFIED"
    const val VERIFY_FAILED = "VERIFY_FAILED"
}

/** Reasons carried by FILE_REJECT. */
enum class RejectReason {
    USER_REJECTED,
    BUSY,
    INSUFFICIENT_STORAGE,
    DUPLICATE_SUSPECTED,
    INVALID_METADATA,
    UNSUPPORTED,
    RESOURCE_EXHAUSTED,
}

/** Body of FILE_ACCEPT. */
@Serializable
data class FileAcceptBody(
    @SerialName("transferId") val transferId: String,
    @SerialName("acceptedAt") val acceptedAt: Long? = null,
)

/** Body of FILE_REJECT. */
@Serializable
data class FileRejectBody(
    @SerialName("transferId") val transferId: String,
    @SerialName("reason") val reason: RejectReason,
)

@Serializable(with = IndexRangeSerializer::class)
data class IndexRange(
    val start: Long,
    val endInclusive: Long,
) {
    init {
        require(start >= 0 && endInclusive >= start) { "Invalid range [$start,$endInclusive]" }
    }

    fun toLongRange(): LongRange = start..endInclusive

    companion object {
        fun of(range: LongRange): IndexRange = IndexRange(range.first, range.last)
    }
}

object IndexRangeSerializer : KSerializer<IndexRange> {
    private val delegate = ListSerializer(Long.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: IndexRange,
    ) {
        delegate.serialize(encoder, listOf(value.start, value.endInclusive))
    }

    override fun deserialize(decoder: Decoder): IndexRange {
        val pair = delegate.deserialize(decoder)
        require(pair.size == RANGE_ARITY) { "Chunk range needs $RANGE_ARITY elements, got ${pair.size}" }
        return IndexRange(pair[0], pair[1])
    }

    private const val RANGE_ARITY = 2
}

/** Body of TRANSFER_START. */
@Serializable
data class TransferStartBody(
    @SerialName("transferId") val transferId: String,
    @SerialName("chunkSize") val chunkSize: Int,
    @SerialName("chunkCount") val chunkCount: Long,
    @SerialName("startIndex") val startIndex: Long,
)

/** Body of CHUNK_ACK. */
@Serializable
data class ChunkAckBody(
    @SerialName("transferId") val transferId: String,
    @SerialName("received") val received: List<IndexRange>,
    @SerialName("highestContiguous") val highestContiguous: Long,
)

/** Body of TRANSFER_END. */
@Serializable
data class TransferEndBody(
    @SerialName("transferId") val transferId: String,
    @SerialName("bytesSent") val bytesSent: Long,
)

/** Body of TRANSFER_VERIFIED. */
@Serializable
data class TransferVerifiedBody(
    @SerialName("transferId") val transferId: String,
    @SerialName("sha256") val sha256: String,
)

/** Body of VERIFY_FAILED. */
@Serializable
data class VerifyFailedBody(
    @SerialName("transferId") val transferId: String,
    @SerialName("expectedSha256") val expectedSha256: String,
    @SerialName("actualSha256") val actualSha256: String,
)

internal val transferJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

internal fun FileMetadata.toJsonElement(): JsonElement =
    transferJson.encodeToJsonElement(FileMetadata.serializer(), this)

internal fun FileAcceptBody.toJsonElement(): JsonElement =
    transferJson.encodeToJsonElement(FileAcceptBody.serializer(), this)

internal fun FileRejectBody.toJsonElement(): JsonElement =
    transferJson.encodeToJsonElement(FileRejectBody.serializer(), this)

internal fun TransferStartBody.toJsonElement(): JsonElement =
    transferJson.encodeToJsonElement(TransferStartBody.serializer(), this)

internal fun ChunkAckBody.toJsonElement(): JsonElement =
    transferJson.encodeToJsonElement(ChunkAckBody.serializer(), this)

internal fun TransferEndBody.toJsonElement(): JsonElement =
    transferJson.encodeToJsonElement(TransferEndBody.serializer(), this)

internal fun TransferVerifiedBody.toJsonElement(): JsonElement =
    transferJson.encodeToJsonElement(TransferVerifiedBody.serializer(), this)

internal fun VerifyFailedBody.toJsonElement(): JsonElement =
    transferJson.encodeToJsonElement(VerifyFailedBody.serializer(), this)

internal fun buildTransferEnvelope(
    type: String,
    transferId: String,
    sessionId: String,
    deviceId: String,
    messageId: String,
    body: JsonElement?,
): MessageEnvelope =
    MessageEnvelope(
        version = MessageEnvelope.PROTOCOL_VERSION,
        type = type,
        messageId = messageId,
        sessionId = sessionId,
        deviceId = deviceId,
        transferId = transferId,
        body = body,
    )

/**
 * Decodes a typed transfer body from an envelope's `body`.
 *
 * @throws TransferProtocolException when the body is missing or malformed.
 */
internal fun <T> JsonElement?.decodeTransferBody(
    serializer: kotlinx.serialization.KSerializer<T>,
    messageType: String,
): T {
    if (this == null || this is JsonNull) {
        throw TransferProtocolException("$messageType body missing")
    }
    return try {
        transferJson.decodeFromJsonElement(serializer, this)
    } catch (e: SerializationException) {
        throw TransferProtocolException("$messageType body invalid")
    } catch (e: IllegalArgumentException) {
        throw TransferProtocolException("$messageType body invalid")
    }
}

class TransferProtocolException(
    detail: String,
) : Exception(detail)
