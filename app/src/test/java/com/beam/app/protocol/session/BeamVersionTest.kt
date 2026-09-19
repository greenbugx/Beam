package com.beam.app.protocol.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeamVersionTest {
    @Test
    fun `parses valid versions`() {
        assertEquals(BeamVersion.parse("BEAM/1.1"), 1 to 1)
        assertEquals(BeamVersion.parse("BEAM/12.34"), 12 to 34)
    }

    @Test
    fun `rejects malformed versions`() {
        assertNull(BeamVersion.parse("BEAM/1"))
        assertNull(BeamVersion.parse("beam/1.0"))
        assertNull(BeamVersion.parse("HTTP/1.1"))
        assertNull(BeamVersion.parse(""))
    }

    @Test
    fun `no common major returns null`() {
        assertNull(BeamVersion.negotiate(listOf("BEAM/1.1"), listOf("BEAM/2.0")))
    }

    @Test
    fun `same major negotiates the lower minor`() {
        assertEquals("BEAM/1.2", BeamVersion.negotiate(listOf("BEAM/1.5"), listOf("BEAM/1.2")))
        assertEquals("BEAM/1.2", BeamVersion.negotiate(listOf("BEAM/1.2"), listOf("BEAM/1.5")))
    }

    @Test
    fun `highest common major wins`() {
        assertEquals("BEAM/2.1", BeamVersion.negotiate(listOf("BEAM/1.1", "BEAM/2.3"), listOf("BEAM/2.1")))
    }

    @Test
    fun `unparseable remote versions refuse negotiation`() {
        assertNull(BeamVersion.negotiate(listOf("BEAM/1.1"), listOf("garbage")))
    }

    @Test
    fun `oversized numeric components are rejected instead of crashing`() {
        assertNull(BeamVersion.parse("BEAM/99999999999.0"))
        assertNull(BeamVersion.parse("BEAM/1.99999999999"))
        assertNull(BeamVersion.negotiate(listOf("BEAM/1.1"), listOf("BEAM/99999999999.0")))
    }

    @Test
    fun `upper digit bound still parses`() {
        assertEquals(BeamVersion.parse("BEAM/999999999.1"), 999_999_999 to 1)
    }
}

class MessageIdTest {
    @Test
    fun `counter increases monotonically and is padded`() {
        val generator = MessageIdGenerator()
        assertEquals("00000001", generator.next())
        assertEquals("00000002", generator.next())
        assertEquals("00001000", MessageIdGenerator(startAt = 999).next())
    }

    @Test
    fun `tracker flags duplicates once`() {
        val tracker = MidTracker()
        assertFalse(tracker.isDuplicate("m1"))
        assertTrue(tracker.isDuplicate("m1"))
        assertFalse(tracker.isDuplicate("m2"))
    }

    @Test
    fun `tracker stays bounded`() {
        val tracker = MidTracker(capacity = 4)
        repeat(10) { tracker.isDuplicate(it.toString()) }
        // Long-evicted mid is no longer remembered.
        assertFalse(tracker.isDuplicate("0"))
        // Recent mids still are.
        assertTrue(tracker.isDuplicate("9"))
    }

    @Test
    fun `tracker keeps the Section 25 recent window of 64 mids`() {
        val tracker = MidTracker()
        repeat(64) { assertFalse(tracker.isDuplicate(it.toString())) }
        assertTrue(tracker.isDuplicate("0"))
        assertFalse(tracker.isDuplicate("64")) // new mid evicts the oldest
        assertFalse(tracker.isDuplicate("0"))
        assertTrue(tracker.isDuplicate("64"))
    }
}
