package com.beam.app.protocol.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlanTest {
    @Test
    fun `derives offsets and lengths with a short last chunk`() {
        val plan = ChunkPlan(sizeBytes = 1000, chunkSize = 256)
        assertEquals(4, plan.chunkCount)
        assertEquals(256L, plan.length(0))
        assertEquals(256L, plan.length(1))
        assertEquals(256L, plan.length(2))
        assertEquals(232L, plan.length(3))
        assertEquals(768L, plan.offset(3))
        assertTrue(plan.isLastChunk(3))
        assertFalse(plan.isLastChunk(2))
    }

    @Test
    fun `empty file is one empty chunk`() {
        val plan = ChunkPlan(sizeBytes = 0, chunkSize = 256)
        assertEquals(1, plan.chunkCount)
        assertEquals(0L, plan.length(0))
        assertTrue(plan.isLastChunk(0))
    }

    @Test
    fun `exact multiple has no short chunk`() {
        val plan = ChunkPlan(sizeBytes = 512, chunkSize = 256)
        assertEquals(2, plan.chunkCount)
        assertEquals(256L, plan.length(1))
        assertTrue(plan.isLastChunk(1))
    }
}

class RangeTrackerTest {
    @Test
    fun `coalesces runs and tracks highest contiguous`() {
        val tracker = RangeTracker()
        listOf(0L, 1L, 2L, 5L, 7L, 8L).forEach { tracker.add(it) }
        assertEquals(
            listOf(0L..2L, 5L..5L, 7L..8L),
            tracker.coalescedRanges(),
        )
        assertEquals(2L, tracker.highestContiguous)
        assertEquals(6, tracker.receivedCount)
        assertTrue(tracker.isComplete(6))
        assertFalse(tracker.isComplete(9))
    }

    @Test
    fun `duplicate adds are idempotent`() {
        val tracker = RangeTracker()
        assertTrue(tracker.add(3))
        assertFalse(tracker.add(3))
        assertEquals(1, tracker.receivedCount)
    }

    @Test
    fun `bulk ranges report only new chunks`() {
        val tracker = RangeTracker()
        tracker.add(1)
        val newly = tracker.addAll(listOf(0L..2L, 4L..4L))
        assertEquals(3, newly)
        assertEquals(4, tracker.receivedCount)
        assertEquals(2L, tracker.highestContiguous)
    }
}
