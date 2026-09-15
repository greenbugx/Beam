package com.beam.app.protocol.transfer

import java.io.File

class RangesSidecar(
    private val tempDir: File,
    private val transferId: String,
) {
    private val file: File
        get() = File(tempDir, "$transferId.ranges")

    fun flush(ranges: List<LongRange>) {
        tempDir.mkdirs()
        file.writeText(ranges.joinToString(separator = "\n") { "${it.first}-${it.last}" } + "\n")
    }

    /** Parses a sidecar back into ranges
     *
     * empty when the file is absent or blank. */
    fun read(): List<LongRange> {
        if (!file.exists()) return emptyList()
        return file
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("-")
                val start = parts[0].toLong()
                val end = parts[1].toLong()
                start..end
            }
    }

    fun delete(): Boolean = file.delete()
}

object TempSweep {
    fun sweep(
        tempDir: File,
        activeTransferIds: Set<String>,
        retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        if (!tempDir.isDirectory) return 0
        var deleted = 0
        for (candidate in tempDir.listFiles().orEmpty()) {
            if (!candidate.isFile) continue
            val name = candidate.name
            val transferId =
                when {
                    name.endsWith(PART_SUFFIX) -> name.removeSuffix(PART_SUFFIX)
                    name.endsWith(RANGES_SUFFIX) -> name.removeSuffix(RANGES_SUFFIX)
                    else -> continue
                }
            val stale = nowMillis - candidate.lastModified() >= retentionMillis
            val orphaned = transferId !in activeTransferIds
            if (stale || orphaned) {
                if (candidate.delete()) deleted++
            }
        }
        return deleted
    }

    private const val PART_SUFFIX = ".part"
    private const val RANGES_SUFFIX = ".ranges"
    private const val DEFAULT_RETENTION_MILLIS = 24L * 60 * 60 * 1000
}
