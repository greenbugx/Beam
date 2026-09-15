package com.beam.app.protocol.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeamIdsTest {
    @Test
    fun `transferId is a UUID string`() {
        val id = BeamIds.newTransferId()
        assertEquals(36, id.length)
        org.junit.Assert.assertNotNull(java.util.UUID.fromString(id))
    }

    @Test
    fun `transferIds are unique`() {
        assertFalse(BeamIds.newTransferId() == BeamIds.newTransferId())
    }

    @Test
    fun `fileId is 16 lowercase hex chars`() {
        val id = BeamIds.newFileId()
        assertTrue(Regex("[0-9a-f]{16}").matches(id))
    }
}
