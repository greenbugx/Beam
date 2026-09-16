package com.beam.app.protocol.manager

internal class RateMeter(
    private val clock: Clock,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val samples = ArrayDeque<Sample>()

    /** Records the cumulative byte count at the current instant. */
    fun sample(bytes: Long) {
        val now = clock.nowMillis()
        samples.addLast(Sample(now, bytes))
        while (samples.size > 1 && now - samples.first().atMillis > windowMillis) {
            samples.removeFirst()
        }
    }

    /** Bytes per second across the window;
     *
     * 0 while the rate is not yet measurable. */
    fun bytesPerSecond(): Long {
        val first = samples.firstOrNull() ?: return 0L
        val last = samples.lastOrNull() ?: return 0L
        val elapsed = last.atMillis - first.atMillis
        val delta = last.bytes - first.bytes
        if (elapsed <= 0L || delta <= 0L) return 0L
        return delta * MILLIS_PER_SECOND / elapsed
    }

    fun reset() {
        samples.clear()
    }

    private data class Sample(
        val atMillis: Long,
        val bytes: Long,
    )

    private companion object {
        const val DEFAULT_WINDOW_MILLIS = 5_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
