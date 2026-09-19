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

    @Test
    fun `cumulative acknowledgments count only new chunks`() {
        val tracker = RangeTracker()
        for (end in 99L..99_999L step 100) {
            assertEquals(100, tracker.addAll(listOf(0L..end)))
        }
        assertEquals(0, tracker.addAll(listOf(0L..99_999L)))
        assertEquals(100_000, tracker.receivedCount)
        assertEquals(99_999L, tracker.highestContiguous)
        assertEquals(listOf(0L..99_999L), tracker.coalescedRanges())
    }

    @Test
    fun `out of order overlapping runs bridge multiple gaps`() {
        val tracker = RangeTracker()
        assertEquals(9, tracker.addAll(listOf(8L..10L, 0L..2L, 4L..6L)))
        assertEquals(2L, tracker.highestContiguous)
        assertEquals(2, tracker.addAll(listOf(2L..9L, 4L..6L, 7L..3L)))
        assertEquals(11, tracker.receivedCount)
        assertEquals(10L, tracker.highestContiguous)
        assertEquals(listOf(0L..10L), tracker.coalescedRanges())
        assertTrue(7L in tracker)
        assertFalse(11L in tracker)
    }

    @Test
    fun `ranges at maximum index do not wrap adjacency`() {
        val tracker = RangeTracker()
        assertTrue(tracker.add(Long.MAX_VALUE))
        assertFalse(tracker.add(Long.MAX_VALUE))
        assertEquals(2, tracker.addAll(listOf((Long.MAX_VALUE - 2)..Long.MAX_VALUE)))
        assertTrue(tracker.add(0))
        assertEquals(4, tracker.receivedCount)
        assertEquals(0L, tracker.highestContiguous)
        assertEquals(listOf(0L..0L, (Long.MAX_VALUE - 2)..Long.MAX_VALUE), tracker.coalescedRanges())
    }

    @Test
    fun `count limit rejects overflow without corrupting received ranges`() {
        val tracker = RangeTracker()
        val end = Int.MAX_VALUE.toLong() - 1
        assertEquals(Int.MAX_VALUE, tracker.addAll(listOf(0L..end)))
        assertFalse(tracker.add(end))
        try {
            tracker.add(end + 1)
            org.junit.Assert.fail("Expected unrepresentable count to be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(Int.MAX_VALUE, tracker.receivedCount)
            assertEquals(end, tracker.highestContiguous)
            assertEquals(listOf(0L..end), tracker.coalescedRanges())
        }
    }

    @Test
    fun `out of order ranges preserve short final chunk byte geometry`() {
        val tracker = RangeTracker()
        val plan = ChunkPlan(sizeBytes = 1000, chunkSize = 256)
        tracker.add(3)
        assertEquals(232L, plan.bytesIn(tracker.coalescedRanges()))
        tracker.addAll(listOf(0L..1L, 3L..3L))
        assertEquals(744L, plan.bytesIn(tracker.coalescedRanges()))
        tracker.add(2)
        assertEquals(1000L, plan.bytesIn(tracker.coalescedRanges()))
        assertEquals(0L, ChunkPlan(0, 256).bytesIn(listOf(0L..0L)))
    }
}
