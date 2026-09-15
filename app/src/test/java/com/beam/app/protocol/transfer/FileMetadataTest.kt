package com.beam.app.protocol.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMetadataTest {
    private fun valid(
        sizeBytes: Long = 4096L,
        chunkSize: Int = FileMetadata.DEFAULT_CHUNK_SIZE_BYTES,
    ): FileMetadata =
        FileMetadata(
            transferId = "11111111-2222-3333-4444-555555555555",
            fileId = "0011223344556677",
            name = "notes.txt",
            mime = "text/plain",
            sizeBytes = sizeBytes,
            sha256 = "ab".repeat(32),
            chunkSize = chunkSize,
            chunkCount = FileMetadata.derivedChunkCount(sizeBytes, chunkSize),
        )

    @Test
    fun `valid metadata passes the gate`() {
        assertTrue(valid().validate().isEmpty())
    }

    @Test
    fun `empty file is one empty chunk`() {
        assertEquals(1L, FileMetadata.derivedChunkCount(0, 262_144))
        assertTrue(valid(sizeBytes = 0).validate().isEmpty())
        assertEquals(0L, valid(sizeBytes = 0).lastChunkLength)
    }

    @Test
    fun `size and chunk count rules`() {
        assertTrue(MetadataError.SIZE_NEGATIVE in valid(sizeBytes = -1).validate())
        assertTrue(
            MetadataError.SIZE_TOO_LARGE in valid(sizeBytes = FileMetadata.MAX_FILE_SIZE_BYTES + 1).validate(),
        )
        assertTrue(
            MetadataError.CHUNK_COUNT_MISMATCH in
                valid().copy(chunkCount = 99).validate(),
        )
        assertTrue(
            MetadataError.CHUNK_COUNT_TOO_LARGE in
                valid().copy(chunkCount = FileMetadata.MAX_CHUNK_COUNT + 1).validate(),
        )
    }

    @Test
    fun `chunk size boundaries`() {
        assertTrue(MetadataError.CHUNK_SIZE_TOO_SMALL in valid(chunkSize = 64 * 1024 - 1).validate())
        assertTrue(MetadataError.CHUNK_SIZE_TOO_LARGE in valid(chunkSize = 1024 * 1024 + 1).validate())
        assertTrue(valid(chunkSize = 64 * 1024).validate().isEmpty())
        assertTrue(valid(chunkSize = 1024 * 1024).validate().isEmpty())
    }

    @Test
    fun `string field rules`() {
        assertTrue(MetadataError.SHA256_MALFORMED in valid().copy(sha256 = "AB".repeat(32)).validate())
        assertTrue(MetadataError.SHA256_MALFORMED in valid().copy(sha256 = "ab".repeat(31)).validate())
        assertTrue(MetadataError.MIME_TOO_LONG in valid().copy(mime = "x".repeat(128)).validate())
        assertTrue(MetadataError.TRANSFER_ID_INVALID in valid().copy(transferId = "").validate())
        assertTrue(MetadataError.FILE_ID_INVALID in valid().copy(fileId = "").validate())
        // A name that sanitizes to exactly 255 chars still passes.
        val longName = "n".repeat(255) + ".txt"
        assertFalse(MetadataError.NAME_INVALID in valid().copy(name = longName).validate())
    }

    @Test
    fun `round trips through json`() {
        val json = transferJson.encodeToJsonElement(FileMetadata.serializer(), valid())
        val decoded = transferJson.decodeFromJsonElement(FileMetadata.serializer(), json)
        assertEquals(valid(), decoded)
    }
}
