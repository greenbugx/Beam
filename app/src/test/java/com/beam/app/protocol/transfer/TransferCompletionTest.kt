package com.beam.app.protocol.transfer

import com.beam.app.protocol.ChunkHeader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** Known SHA-256 test vectors, verified against SHA256SUM. */
private const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

/** Minimal TransferWire recorder for completer tests. */
private class CompletionWire : TransferWire {
    val verified = mutableListOf<TransferVerifiedBody>()
    val verifyFailed = mutableListOf<VerifyFailedBody>()

    override suspend fun sendStart(body: TransferStartBody) = Unit

    override suspend fun sendChunk(
        header: ChunkHeader,
        payload: ByteArray,
    ) = Unit

    override suspend fun sendAck(body: ChunkAckBody) = Unit

    override suspend fun sendEnd(body: TransferEndBody) = Unit

    override suspend fun sendVerified(body: TransferVerifiedBody) {
        verified += body
    }

    override suspend fun sendVerifyFailed(body: VerifyFailedBody) {
        verifyFailed += body
    }
}

class FileVerifierTest {
    @Test
    fun `known vectors hash correctly`() {
        assertEquals(ABC_SHA256, FileVerifier.hash(ByteArrayInputStream("abc".toByteArray())))
        assertEquals(EMPTY_SHA256, FileVerifier.hash(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `file hash matches stream hash across buffer boundaries`() {
        val bytes = ByteArray(200_000) { it.toByte() }
        val streamed = FileVerifier.hash(ByteArrayInputStream(bytes))
        val file = Files.createTempFile("beam", "bin").toFile().apply { writeBytes(bytes) }
        val fromFile = FileVerifier.hash(file).also { file.delete() }
        assertEquals(streamed, fromFile)
    }
}

class RangesSidecarTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `round trips coalesced ranges`() {
        val sidecar = RangesSidecar(tmp.root, "tid-1")
        sidecar.flush(listOf(0L..4L, 10L..12L))
        assertEquals(listOf(0L..4L, 10L..12L), sidecar.read())
    }

    @Test
    fun `missing sidecar reads empty and delete reports absence`() {
        val sidecar = RangesSidecar(tmp.root, "tid-2")
        assertEquals(emptyList<LongRange>(), sidecar.read())
        assertFalse(sidecar.delete())
    }

    @Test
    fun `delete removes the sidecar`() {
        val sidecar = RangesSidecar(tmp.root, "tid-3")
        sidecar.flush(listOf(0L..0L))
        assertTrue(sidecar.delete())
        assertFalse(File(tmp.root, "tid-3.ranges").exists())
    }
}

class TempSweepTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun part(
        tid: String,
        bytes: Int = 10,
    ): File = tmp.newFile("$tid.part").apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `deletes orphaned artifacts keeps active ones`() {
        val orphan = part("gone")
        val active = part("busy")
        val activeSidecar = tmp.newFile("busy.ranges").apply { writeText("0-1\n") }
        tmp.newFile("notes.txt").writeText("unrelated")

        val deleted = TempSweep.sweep(tmp.root, activeTransferIds = setOf("busy"), retentionMillis = Long.MAX_VALUE)

        assertEquals(1, deleted)
        assertFalse(orphan.exists())
        assertTrue(active.exists())
        assertTrue(activeSidecar.exists())
        assertTrue(File(tmp.root, "notes.txt").exists())
    }

    @Test
    fun `stale artifacts are swept even when active`() {
        val active = part("busy", bytes = 5)
        val deleted = TempSweep.sweep(tmp.root, activeTransferIds = setOf("busy"), retentionMillis = 0)
        assertEquals(1, deleted)
        assertFalse(active.exists())
    }

    @Test
    fun `missing directory sweeps nothing`() {
        assertEquals(0, TempSweep.sweep(File(tmp.root, "nope"), activeTransferIds = emptySet()))
    }

    @Test
    fun `directories named like temp artifacts are ignored`() {
        val dir = tmp.newFolder("tricky.part")
        assertEquals(0, TempSweep.sweep(tmp.root, activeTransferIds = emptySet(), retentionMillis = 0))
        assertTrue(dir.exists())
    }
}

class TransferCompleterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var tempDir: File
    private lateinit var wire: CompletionWire

    private fun metadata(
        sizeBytes: Long,
        sha256: String,
    ): FileMetadata =
        FileMetadata(
            transferId = "11111111-2222-3333-4444-555555555555",
            fileId = "0011223344556677",
            name = "notes.txt",
            mime = "text/plain",
            sizeBytes = sizeBytes,
            sha256 = sha256,
            chunkSize = 64 * 1024,
            chunkCount = FileMetadata.derivedChunkCount(sizeBytes, 64 * 1024),
        )

    private fun writePart(content: ByteArray): File {
        val part = File(tempDir, "11111111-2222-3333-4444-555555555555.part")
        part.writeBytes(content)
        return part
    }

    private fun newCompleter(md: FileMetadata): TransferCompleter = TransferCompleter(md, tempDir, wire)

    @Before
    fun setUp() {
        tempDir = tmp.newFolder("temp")
        wire = CompletionWire()
    }

    @Test
    fun `matching hash publishes atomically and sends verified`() =
        runBlocking {
            val content = "abc".toByteArray()
            val part = writePart(content)
            val destination = File(tmp.root, "out/notes.txt")

            val status = newCompleter(metadata(content.size.toLong(), ABC_SHA256)).complete(destination)

            assertEquals(CompletionStatus.PUBLISHED, status)
            assertEquals("abc", destination.readText())
            assertFalse(part.exists())
            assertFalse(File(tempDir, "11111111-2222-3333-4444-555555555555.ranges").exists())
            assertEquals(1, wire.verified.size)
            assertEquals(
                "11111111-2222-3333-4444-555555555555",
                wire.verified.single().transferId,
            )
            assertEquals(ABC_SHA256, wire.verified.single().sha256)
        }

    @Test
    fun `mismatched hash deletes temp and sends verify failed`() =
        runBlocking {
            val content = "abd".toByteArray()
            val part = writePart(content)
            val destination = File(tmp.root, "notes.txt")

            val status = newCompleter(metadata(content.size.toLong(), ABC_SHA256)).complete(destination)

            assertEquals(CompletionStatus.VERIFY_FAILED, status)
            assertFalse(destination.exists())
            assertFalse(part.exists())
            assertEquals(1, wire.verifyFailed.size)
            assertEquals(ABC_SHA256, wire.verifyFailed.single().expectedSha256)
            assertEquals(
                FileVerifier.hash(ByteArrayInputStream(content)),
                wire.verifyFailed.single().actualSha256,
            )
            assertTrue(wire.verified.isEmpty())
        }

    @Test
    fun `empty file publishes with the empty-string hash`() =
        runBlocking {
            val part = writePart(ByteArray(0))
            val destination = File(tmp.root, "empty.bin")

            val status = newCompleter(metadata(0, EMPTY_SHA256)).complete(destination)

            assertEquals(CompletionStatus.PUBLISHED, status)
            assertTrue(destination.exists() && destination.length() == 0L)
            assertFalse(part.exists())
        }

    @Test
    fun `missing temp file throws storage exception`() =
        runBlocking {
            try {
                newCompleter(metadata(3, ABC_SHA256)).complete(File(tmp.root, "x"))
                fail("expected TransferStorageException")
            } catch (expected: TransferStorageException) {
            }
        }
}
