package com.beam.app.protocol.transfer

class RangeTracker {
    private val present = sortedSetOf<Long>()

    fun add(index: Long): Boolean = present.add(index)

    /** Applies coalesced [ranges]. */
    fun addAll(ranges: List<LongRange>): Int =
        ranges.sumOf { range -> (range.first..range.last).count { add(it) } }

    /** Number of distinct chunks received. */
    val receivedCount: Int
        get() = present.size

    /** Largest n such that chunks 0..n are all received;
     *
     * -1 when chunk 0 is missing. */
    val highestContiguous: Long
        get() {
            var expected = 0L
            for (index in present) {
                if (index != expected) break
                expected++
            }
            return expected - 1
        }

    fun isComplete(chunkCount: Long): Boolean = receivedCount.toLong() == chunkCount

    /** Merges adjacent/overlapping runs into closed inclusive ranges. */
    fun coalescedRanges(): List<LongRange> {
        val ranges = mutableListOf<LongRange>()
        var index: Long? = present.firstOrNull() ?: return ranges
        while (index != null) {
            val start = index
            var end = start
            while (present.contains(end + 1)) end++
            ranges += start..end
            index = present.higher(end)
        }
        return ranges
    }
}
