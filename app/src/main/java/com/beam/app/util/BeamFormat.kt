package com.beam.app.util

import java.util.Locale

object BeamFormat {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    fun fileSize(bytes: Long): String = quantity(bytes) + units[unitIndex(bytes)]

    fun speed(bytesPerSecond: Long): String = fileSize(bytesPerSecond) + "/s"

    private fun quantity(bytes: Long): String {
        var value = bytes.toDouble()
        var unit = 0

        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }

        if (unit == 0) return value.toInt().toString()

        // Values under ten read naturally with one decimal,
        // larger values stay compact.
        val pattern = if (value < 10) "%.1f" else "%.0f"

        return String.format(Locale.US, pattern, value)
    }

    private fun unitIndex(bytes: Long): Int {
        var value = bytes.toDouble()
        var unit = 0

        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }

        return unit
    }
}
