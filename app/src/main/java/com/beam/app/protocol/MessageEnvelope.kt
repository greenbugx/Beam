package com.beam.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class MessageEnvelope(
    @SerialName("v") val version: String,
    @SerialName("type") val type: String,
    @SerialName("mid") val messageId: String,
    @SerialName("sid") val sessionId: String,
    @SerialName("did") val deviceId: String,
    @SerialName("tid") val transferId: String? = null,
    @SerialName("ts") val timestampMillis: Long? = null,
    @SerialName("body") val body: JsonElement? = null,
) {
    val isSessionLevel: Boolean get() = transferId == null

    companion object {
        const val PROTOCOL_VERSION = "BEAM/1.0"

        /** Decodes an envelope from a CTRL payload (UTF-8 JSON). */
        fun decode(payload: String): MessageEnvelope =
            envelopeJson.decodeFromString(MessageEnvelope.serializer(), payload)
    }

    /** True when [body] carries real content (not null / JsonNull). */
    val hasBody: Boolean
        get() = body != null && body != JsonNull
}

private val envelopeJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

/** Encodes an envelope to its CTRL payload (UTF-8 JSON). */
fun MessageEnvelope.encode(): String = envelopeJson.encodeToString(MessageEnvelope.serializer(), this)
