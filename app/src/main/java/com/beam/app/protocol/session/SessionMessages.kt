package com.beam.app.protocol.session

import com.beam.app.protocol.Frame
import com.beam.app.protocol.FrameType
import com.beam.app.protocol.MessageEnvelope
import com.beam.app.protocol.encode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Role of a device inside one Beam session. */
enum class SessionRole {
    HOST,
    PEER,
}

/** Capability strings exchanged inside SESSION_HELLO. */
object SessionCapabilities {
    /** Chunked data path. */
    const val CHUNKING = "CHUNKING"

    /** Concurrent transfers on one link. */
    const val MULTI_TRANSFER = "MULTI_TRANSFER"
}

enum class SessionCloseReason {
    USER_LEFT,
    HOST_ENDED,
    UNSUPPORTED_VERSION,
    AUTH_FAILED,
    INVALID_MESSAGE,
    INTERNAL_ERROR,
}

object SessionMessageTypes {
    const val HELLO = "SESSION_HELLO"
    const val READY = "SESSION_READY"
    const val CLOSE = "SESSION_CLOSE"
}

/** A received control message violated the protocol. */
class SessionProtocolException(
    val closeReason: SessionCloseReason,
    detail: String,
) : Exception(detail)

/** Body of SESSION_HELLO.  */
@Serializable
data class SessionHelloBody(
    @SerialName("supportedVersions") val supportedVersions: List<String>,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceName") val deviceName: String,
    @SerialName("role") val role: SessionRole,
    @SerialName("sessionId") val sessionId: String,
    @SerialName("capabilities") val capabilities: List<String>,
    @SerialName("beamCode") val beamCode: String,
)

/** Body of SESSION_READY. */
@Serializable
data class SessionReadyBody(
    @SerialName("agreedVersion") val agreedVersion: String,
)

/** Body of SESSION_CLOSE; never stack traces. */
@Serializable
data class SessionCloseBody(
    @SerialName("reason") val reason: SessionCloseReason,
    @SerialName("detail") val detail: String? = null,
)

internal val sessionJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

internal fun SessionHelloBody.toJsonElement(): JsonElement =
    sessionJson.encodeToJsonElement(SessionHelloBody.serializer(), this)

internal fun SessionReadyBody.toJsonElement(): JsonElement =
    sessionJson.encodeToJsonElement(SessionReadyBody.serializer(), this)

internal fun SessionCloseBody.toJsonElement(): JsonElement =
    sessionJson.encodeToJsonElement(SessionCloseBody.serializer(), this)

/**
 * Decodes a typed session body from an envelope's [MessageEnvelope.body].
 *
 * @throws SessionProtocolException with [SessionCloseReason.INVALID_MESSAGE] when the body is missing or malformed.
 */
internal fun <T> JsonElement?.decodeSessionBody(
    serializer: KSerializer<T>,
    messageType: String,
): T {
    if (this == null || this is JsonNull) {
        throw SessionProtocolException(SessionCloseReason.INVALID_MESSAGE, "$messageType body missing")
    }
    return try {
        sessionJson.decodeFromJsonElement(serializer, this)
    } catch (e: SerializationException) {
        throw SessionProtocolException(SessionCloseReason.INVALID_MESSAGE, "$messageType body invalid")
    } catch (e: IllegalArgumentException) {
        throw SessionProtocolException(SessionCloseReason.INVALID_MESSAGE, "$messageType body invalid")
    }
}

internal fun buildSessionEnvelope(
    type: String,
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
        body = body,
    )

/** Wraps an envelope into the CTRL frame. */
internal fun MessageEnvelope.toCtrlFrame(): Frame = Frame(FrameType.CTRL, encode().toByteArray(Charsets.UTF_8))
