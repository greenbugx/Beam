package com.beam.app.protocol.session

/**
 * BEAM/MAJOR.MINOR protocol version handling.
 */
object BeamVersion {
    private val FORMAT = Regex("BEAM/(\\d+)\\.(\\d+)")

    /** Parses a version string like `BEAM/1.0`; returns null for anything else. */
    fun parse(version: String): Pair<Int, Int>? {
        val match = FORMAT.matchEntire(version.trim()) ?: return null
        val (major, minor) = match.destructured
        return major.toInt() to minor.toInt()
    }

    fun negotiate(
        local: List<String>,
        remote: List<String>,
    ): String? {
        val localParsed = local.mapNotNull { parse(it) }
        val remoteParsed = remote.mapNotNull { parse(it) }
        val commonMajors =
            localParsed.map { it.first }.toSet().intersect(remoteParsed.map { it.first }.toSet())
        val major = commonMajors.maxOrNull() ?: return null
        val localMinor = localParsed.filter { it.first == major }.maxOf { it.second }
        val remoteMinor = remoteParsed.filter { it.first == major }.maxOf { it.second }
        return "BEAM/$major.${minOf(localMinor, remoteMinor)}"
    }
}
