package com.beam.app.protocol.transfer

import java.util.TreeMap

class RangeTracker {
    private val intervals = TreeMap<Long, Long>()

    /** Number of distinct chunks received. */
    var receivedCount: Int = 0
        private set

    /** Largest n such that chunks 0..n are received, or -1 when chunk 0 is missing. */
    var highestContiguous: Long = -1L
        private set

    fun add(index: Long): Boolean = addRange(index, index) != 0

    operator fun contains(index: Long): Boolean = intervals.floorEntry(index)?.value?.let { it >= index } ?: false

    /** Applies ranges without expanding them into individual chunk indexes. */
    fun addAll(ranges: List<LongRange>): Int {
        var added = 0
        for (range in ranges) {
            if (!range.isEmpty()) added += addRange(range.first, range.last)
        }
        return added
    }

    private fun addRange(
        first: Long,
        last: Long,
    ): Int {
        // Keep the Int count API, but reject unrepresentable counts rather than wrapping.
        val length = Math.addExact(Math.subtractExact(last, first), 1L)
        require(length <= Int.MAX_VALUE) { "Too many received chunks" }
        var start = first
        var end = last
        val previous = intervals.floorEntry(first)
        if (previous != null && touches(previous.value, first)) {
            if (previous.value >= last) return 0
            start = previous.key
        }

        var removedCount = 0L
        for ((rangeStart, rangeEnd) in intervals.tailMap(start, true)) {
            if (!touches(end, rangeStart)) break
            end = maxOf(end, rangeEnd)
            removedCount += rangeEnd - rangeStart + 1
        }
        val added = end - start + 1 - removedCount
        val count = receivedCount.toLong() + added
        require(count <= Int.MAX_VALUE) { "Too many received chunks" }

        intervals.subMap(start, true, end, true).clear()
        intervals[start] = end
        receivedCount = count.toInt()
        if (start < 0) {
            highestContiguous = -1L
        } else if (start == 0L && intervals.firstKey() == 0L) {
            highestContiguous = end
        }
        return added.toInt()
    }

    private fun touches(
        end: Long,
        start: Long,
    ): Boolean = start <= end || (end != Long.MAX_VALUE && start == end + 1)

    fun isComplete(chunkCount: Long): Boolean = receivedCount.toLong() == chunkCount

    /** Returns an independent snapshot of the already coalesced inclusive ranges. */
    fun coalescedRanges(): List<LongRange> = intervals.map { (start, end) -> start..end }
}
