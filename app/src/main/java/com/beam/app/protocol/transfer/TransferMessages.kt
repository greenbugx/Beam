package com.beam.app.protocol.transfer

import com.beam.app.protocol.MessageEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

object TransferMessageTypes {
    const val OFFER = "FILE_OFFER"
    const val ACCEPT = "FILE_ACCEPT"
    const val REJECT = "FILE_REJECT"
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
