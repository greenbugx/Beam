package com.beam.app.protocol.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilenameSanitizerTest {
    @Test
    fun `traversal attempts cannot escape the destination directory`() {
        val destDir = File("/tmp/beam-dest").canonicalFile
        for (remote in listOf("../../file", "..\\..\\file", "/absolute", "C:\\absolute", "..", "/")) {
            val resolved = File(destDir, FilenameSanitizer.sanitize(remote))
            assertTrue("'$remote' escaped", resolved.canonicalPath.startsWith(destDir.canonicalPath))
        }
    }

    @Test
    fun `embedded separators are discarded keeping the last segment`() {
        assertEquals("passwd", FilenameSanitizer.sanitize("../../etc/passwd"))
        assertEquals("evil.exe", FilenameSanitizer.sanitize("C:\\evil.exe"))
        assertEquals("absolute", FilenameSanitizer.sanitize("/absolute"))
    }

    @Test
    fun `control chars leading dots and empty results are handled`() {
        assertEquals("hidden.txt", FilenameSanitizer.sanitize("\u0000.hidden.txt"))
        assertEquals("file", FilenameSanitizer.sanitize(""))
        assertEquals("file", FilenameSanitizer.sanitize("..."))
        assertEquals("file", FilenameSanitizer.sanitize("\uFEFF\u0001"))
    }

    @Test
    fun `windows reserved names are renamed`() {
        assertEquals("beam_CON", FilenameSanitizer.sanitize("CON"))
        assertEquals("beam_NUL.txt", FilenameSanitizer.sanitize("NUL.txt"))
        assertEquals("console.txt", FilenameSanitizer.sanitize("console.txt"))
    }

    @Test
    fun `overlong names truncate on a code point boundary with tilde marker`() {
        val ascii = "a".repeat(300) + ".txt"
        val sanitized = FilenameSanitizer.sanitize(ascii)
        assertTrue(sanitized.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(sanitized.endsWith("~1.txt"))

        /* 4 UTF-8 bytes per code point.
         * 200 of them = 800 bytes.
         */
        val emoji = "\uD83D\uDE00".repeat(200)
        val sanitizedEmoji = FilenameSanitizer.sanitize(emoji)
        assertTrue(sanitizedEmoji.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(sanitizedEmoji.endsWith("~1"))
        assertEquals(63 * 4, sanitizedEmoji.dropLast(2).toByteArray(Charsets.UTF_8).size)
    }
}
