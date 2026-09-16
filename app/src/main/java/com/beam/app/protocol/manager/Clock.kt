package com.beam.app.protocol.manager

fun interface Clock {
    fun nowMillis(): Long
}

/** Wall-clock source used in production. */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
