package com.beam.app.protocol.transfer

import java.text.Normalizer

object FilenameSanitizer {
    /** Sanitizes [remoteName] for display and storage. */
    fun sanitize(remoteName: String): String {
        var name = Normalizer.normalize(remoteName, Normalizer.Form.NFC).removePrefix(BOM)
        name = name.split('/', '\\').last()
        name = name.replace(Regex("^[A-Za-z]:"), "")
        name = name.filter { !isControl(it) }
        name = name.trimStart('.', '~')
        if (name == RESERVED_PARENT || name == RESERVED_CURRENT) name = ""
        name = name.ifBlank { FALLBACK_NAME }
        name = applyWindowsReservation(name)
        name = truncate(name)
        return name.ifBlank { FALLBACK_NAME }
    }

    private fun isControl(c: Char): Boolean = c < ' ' || c in '\u007f'..'\u009f'

    private fun applyWindowsReservation(name: String): String {
        val stem = name.substringBefore('.', name)
        val reserved = RESERVED_NAMES.any { it.equals(stem, ignoreCase = true) }
        return if (reserved) "beam_$name" else name
    }

    /**
     * Truncates to [MAX_NAME_BYTES] UTF-8 bytes on a code-point boundary,
     * appending `~N` before the extension when truncation happens.
     */
    private fun truncate(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0
        val suffix = "$TRUNCATION_MARK$TRUNCATION_INDEX"
        val extension = if (hasExtension) name.substring(dot) else ""
        val base = if (hasExtension) name.substring(0, dot) else name
        val budget =
            MAX_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size - extension.toByteArray(Charsets.UTF_8).size
        val trimmed = base.takeWhileCodePoints(budget)
        return trimmed + suffix + extension
    }

    private fun String.takeWhileCodePoints(maxBytes: Int): String {
        var bytes = 0
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            val width =
                when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
            if (bytes + width > maxBytes) return substring(0, index)
            bytes += width
            index += Character.charCount(codePoint)
        }
        return this
    }

    private const val BOM = "\uFEFF"
    private const val FALLBACK_NAME = "file"
    private const val TRUNCATION_MARK = "~"
    private const val TRUNCATION_INDEX = 1
    private const val MAX_NAME_BYTES = 255
    private const val RESERVED_PARENT = ".."
    private const val RESERVED_CURRENT = "."
    private val RESERVED_NAMES = listOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
}
