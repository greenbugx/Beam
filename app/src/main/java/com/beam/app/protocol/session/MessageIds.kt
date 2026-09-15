package com.beam.app.protocol.session

import java.util.concurrent.atomic.AtomicLong

class MessageIdGenerator(
    startAt: Long = 0,
) {
    private val counter = AtomicLong(startAt)

    fun next(): String = counter.incrementAndGet().toString().padStart(MID_WIDTH, '0')

    private companion object {
        const val MID_WIDTH = 8
    }
}

class MidTracker(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val seen = ArrayDeque<String>()

    /** Returns true when [mid] was already seen on this link. */
    @Synchronized
    fun isDuplicate(mid: String): Boolean {
        if (seen.contains(mid)) return true
        if (seen.size >= capacity) seen.removeFirst()
        seen.addLast(mid)
        return false
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
