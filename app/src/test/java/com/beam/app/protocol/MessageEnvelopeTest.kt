package com.beam.app.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEnvelopeTest {
    private val envelope =
        MessageEnvelope(
            version = "BEAM/1.0",
            type = "FILE_OFFER",
            messageId = "A-0042",
            sessionId = "BS-7F3K9Q",
            deviceId = "11f2",
            transferId = "9c1f",
            timestampMillis = 1730000000000,
            body = buildJsonObject { put("name", "video.mp4") },
        )

    @Test
    fun `round trips every field`() {
        val decoded = MessageEnvelope.decode(envelope.encode())

        assertEquals(envelope, decoded)
    }

    @Test
    fun `omits null optional fields from the wire`() {
        val sessionLevel =
            envelope.copy(transferId = null, timestampMillis = null, body = null)
        val json = sessionLevel.encode()

        assertFalse(json.contains("tid"))
        assertFalse(json.contains("ts"))
        assertFalse(json.contains("body"))
        assertTrue(sessionLevel.isSessionLevel)
        assertFalse(envelope.isSessionLevel)
    }

    @Test
    fun `carries version and sender identity verbatim`() {
        val decoded = MessageEnvelope.decode(envelope.encode())

        assertEquals("BEAM/1.0", decoded.version)
        assertEquals("A-0042", decoded.messageId)
        assertEquals("BS-7F3K9Q", decoded.sessionId)
        assertEquals("11f2", decoded.deviceId)
        assertEquals("9c1f", decoded.transferId)
    }

    @Test
    fun `ignores unknown fields per the section 8 policy`() {
        val withExtra =
            """{
                "v": "BEAM/1.0",
                "type": "SESSION_HELLO",
                "mid": "A-0001",
                "sid": "BS-7F3K9Q",
                "did": "11f2",
                "supportedVersions": ["BEAM/1.0", "BEAM/1.1"],
                "unknownFutureField": 7
            }"""
        val decoded = MessageEnvelope.decode(withExtra)

        assertEquals("SESSION_HELLO", decoded.type)
        assertNull(decoded.transferId)
        assertNull(decoded.body)
    }

    @Test
    fun `body is opaque and preserved verbatim`() {
        val decoded = MessageEnvelope.decode(envelope.encode())

        assertTrue(decoded.hasBody)
        assertEquals(envelope.body, decoded.body)
    }

    @Test
    fun `rejects malformed json`() {
        org.junit.Assert.assertThrows(Exception::class.java) {
            MessageEnvelope.decode("{not json")
        }
    }
}
