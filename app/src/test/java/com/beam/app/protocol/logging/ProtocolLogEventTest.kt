package com.beam.app.protocol.logging

import com.beam.app.protocol.transfer.TransferErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SESSION_ID = "sess-log"
private const val DEVICE_ID = "dev-log-a"

private class RecordingLogger : ProtocolLogger {
    val lines = mutableListOf<String>()

    override fun log(event: ProtocolLogEvent) {
        lines += event.toLogLine()
    }
}

class ProtocolLogEventTest {
    private fun errorEvent(
        errorCode: TransferErrorCode,
        detail: String? = null,
        expectedHash: String? = null,
        actualHash: String? = null,
        transferId: String? = null,
    ) = ProtocolLogEvent(
        category = LogCategory.ERROR,
        sessionId = SESSION_ID,
        deviceId = DEVICE_ID,
        transferId = transferId,
        errorCode = errorCode,
        detail = detail,
        expectedHash = expectedHash,
        actualHash = actualHash,
    )

    @Test
    fun `session id is fingerprinted, never raw`() {
        val line = errorEvent(TransferErrorCode.CONNECTION_LOST, detail = "boom").toLogLine()

        assertTrue(line, line.startsWith("BEAM/1.1 | ERROR    | dir=LOCAL"))
        val sid = line.substringAfter("sid=").substringBefore(" |")
        assertEquals(8, sid.length)
        assertTrue(sid, sid.all { it.isDigit() || it in 'a'..'f' })
        assertTrue(line, !line.contains("sess-log"))
    }

    @Test
    fun `device id is shortened, never secret material is printed`() {
        val line = errorEvent(TransferErrorCode.CONNECTION_LOST, detail = "boom").toLogLine()

        val did = line.substringAfter("did=").substringBefore(" |")
        assertEquals("dev-log-…", did)
        assertTrue(line, !line.contains("dev-log-a"))
    }

    @Test
    fun `hash mismatch is the only line carrying full digests`() {
        val expected = "ab".repeat(32)
        val actual = "cd".repeat(32)

        val mismatch =
            errorEvent(
                TransferErrorCode.HASH_MISMATCH,
                transferId = "tid-full",
                expectedHash = expected,
                actualHash = actual,
            ).toLogLine()
        assertTrue(mismatch, mismatch.contains("expected=$expected actual=$actual"))

        val other =
            errorEvent(
                TransferErrorCode.CONNECTION_LOST,
                transferId = "tid-short",
                expectedHash = expected,
                actualHash = actual,
            ).toLogLine()
        assertTrue(other, !other.contains(expected))
        assertTrue(other, other.contains("expected=${expected.take(8)}… actual=${actual.take(8)}…"))
    }

    @Test
    fun `paths are redacted from details`() {
        val line =
            errorEvent(
                TransferErrorCode.CONNECTION_LOST,
                detail = "write failed: /data/user/0/com.beam.app/files/Beam/note.txt",
            ).toLogLine()

        assertTrue(line, !line.contains("/data/user/0"))
        assertTrue(line, line.contains("detail=write failed: <path>"))
    }

    @Test
    fun `ranges are capped and byte counters humanized`() {
        val ranges = (0L until 12L).map { index -> (index * 10)..(index * 10 + 9) }
        val line =
            ProtocolLogEvent(
                category = LogCategory.TRANSFER,
                transferId = "tid-ranges",
                ranges = ranges,
                bytesTransferred = 3_145_728,
                bytesTotal = 10_485_760,
            ).toLogLine()

        val chunks = line.substringAfter("chunks=").substringBefore(" |")
        assertTrue(chunks, chunks.endsWith("+4"))
        assertEquals(7, chunks.split(",[").size - 1)
        assertTrue(line, line.contains("bytes=3.0MB/10.0MB"))
    }

    @Test
    fun `device id never reaches an ERROR line through the link context`() {
        val recording = RecordingLogger()
        val context = LinkLogContext(recording, SESSION_ID, DEVICE_ID)

        context.error(
            errorCode = TransferErrorCode.INVALID_MESSAGE,
            direction = LogDirection.IN,
            detail = "test",
        )

        assertEquals(1, recording.lines.size)
        val line = recording.lines.single()
        assertTrue(line, line.startsWith("BEAM/1.1 | ERROR"))
        assertTrue(line, line.contains("dir=IN"))
        assertTrue(line, !line.contains("dev-log"))
        assertTrue(line, !line.contains("sess-log"))
    }
}
