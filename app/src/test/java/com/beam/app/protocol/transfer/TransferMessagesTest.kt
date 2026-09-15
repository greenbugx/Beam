package com.beam.app.protocol.transfer

import com.beam.app.protocol.MessageEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferMessagesTest {
    private fun metadata(): FileMetadata =
        FileMetadata(
            transferId = "11111111-2222-3333-4444-555555555555",
            fileId = "0011223344556677",
            name = "notes.txt",
            mime = "text/plain",
            sizeBytes = 4096,
            sha256 = "ab".repeat(32),
            chunkSize = 262144,
            chunkCount = 1,
        )

    @Test
    fun `offer body carries the metadata block and tid is set`() {
        val envelope =
            buildTransferEnvelope(
                type = TransferMessageTypes.OFFER,
                transferId = metadata().transferId,
                sessionId = "session",
                deviceId = "device",
                messageId = "00000001",
                body = metadata().toJsonElement(),
            )
        assertEquals("11111111-2222-3333-4444-555555555555", envelope.transferId)
        val decoded =
            envelope.body.decodeTransferBody(FileMetadata.serializer(), TransferMessageTypes.OFFER)
        assertEquals(metadata(), decoded)
    }

    @Test
    fun `accept body omits optional timestamp by default`() {
        val body = FileAcceptBody(transferId = "tid")
        val encoded = body.toJsonElement()
        assertTrue(encoded.toString().contains("\"transferId\":\"tid\""))
        val decoded = encoded.decodeTransferBody(FileAcceptBody.serializer(), TransferMessageTypes.ACCEPT)
        assertEquals("tid", decoded.transferId)
        assertNull(decoded.acceptedAt)
    }

    @Test
    fun `reject body carries the reason`() {
        val body = FileRejectBody(transferId = "tid", reason = RejectReason.BUSY)
        val decoded = body.toJsonElement().decodeTransferBody(FileRejectBody.serializer(), TransferMessageTypes.REJECT)
        assertEquals(RejectReason.BUSY, decoded.reason)
    }

    @Test(expected = TransferProtocolException::class)
    fun `missing body is rejected`() {
        val envelope =
            buildTransferEnvelope(
                type = TransferMessageTypes.ACCEPT,
                transferId = "tid",
                sessionId = "session",
                deviceId = "device",
                messageId = "00000001",
                body = null,
            )
        envelope.body.decodeTransferBody(FileAcceptBody.serializer(), TransferMessageTypes.ACCEPT)
    }
}
