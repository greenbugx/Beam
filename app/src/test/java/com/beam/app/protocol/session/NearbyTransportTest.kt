@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.beam.app.protocol.session

import com.beam.app.protocol.Frame
import com.beam.app.protocol.FrameCodec
import com.beam.app.protocol.FrameCodecKind
import com.beam.app.protocol.FrameType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeByteLink : NearbyByteLink {
    override val incoming = MutableSharedFlow<NearbyByteLink.NearbyLinkEvent>(extraBufferCapacity = 64)
    val sentBytes = mutableListOf<ByteArray>()
    var closedCount = 0
        private set

    override suspend fun sendBytes(bytes: ByteArray) {
        sentBytes += bytes
    }

    override fun close() {
        closedCount++
    }

    suspend fun deliver(bytes: ByteArray) = incoming.emit(NearbyByteLink.NearbyLinkEvent.BytesReceived(bytes))

    suspend fun disconnect() = incoming.emit(NearbyByteLink.NearbyLinkEvent.Disconnected)
}

private fun TestScope.startTransport(link: FakeByteLink): Pair<NearbyTransport, MutableList<TransportEvent>> {
    val transport = NearbyTransport(link, backgroundScope)
    val events = mutableListOf<TransportEvent>()
    backgroundScope.launch {
        transport.incoming.collect { events += it }
    }
    runCurrent()
    return transport to events
}

private fun ctrlFrame(type: String = "HELLO"): Frame =
    Frame(FrameType.CTRL, """{"v":"BEAM/1.0","type":"$type","mid":"m1","sid":"s","did":"d"}""".encodeToByteArray())

class NearbyTransportTest {
    @Test
    fun `send encodes the frame and hands one wire buffer to the link`() =
        runTest {
            val link = FakeByteLink()
            val (transport, _) = startTransport(link)

            val frame = ctrlFrame()
            transport.send(frame)
            runCurrent()

            assertEquals(1, link.sentBytes.size)
            assertEquals(frame, FrameCodec.decode(link.sentBytes[0]))
        }

    @Test
    fun `full payload in decodes to FrameReceived`() =
        runTest {
            val link = FakeByteLink()
            val (transport, events) = startTransport(link)

            link.deliver(FrameCodec.encode(ctrlFrame()))
            runCurrent()

            assertEquals(1, events.size)
            val event = events[0] as TransportEvent.FrameReceived
            assertEquals(ctrlFrame(), event.frame)
        }

    @Test
    fun `garbage payload surfaces FrameMalformed with codec kind`() =
        runTest {
            val link = FakeByteLink()
            val (transport, events) = startTransport(link)

            link.deliver(byteArrayOf(0x00, 0x00, 0x00, 0x7F, 0x01, 0x01))
            runCurrent()

            val event = events.single() as TransportEvent.FrameMalformed
            assertEquals(FrameCodecKind.TRUNCATED, event.kind)
            assertTrue(event.detail.isNotEmpty())
        }

    @Test
    fun `unknown frame type surfaces FrameMalformed`() =
        runTest {
            val link = FakeByteLink()
            val (transport, events) = startTransport(link)

            link.deliver(byteArrayOf(0, 0, 0, 0, 0x7F))
            runCurrent()

            val event = events.single() as TransportEvent.FrameMalformed
            assertEquals(FrameCodecKind.UNKNOWN_TYPE, event.kind)
        }

    @Test
    fun `frames arriving before subscription are replayed to the first collector`() =
        runTest {
            val link = FakeByteLink()
            val transport = NearbyTransport(link, backgroundScope)

            // Frame lands before anyone subscribes (connect → session-start gap).
            link.deliver(FrameCodec.encode(ctrlFrame()))
            runCurrent()

            val events = mutableListOf<TransportEvent>()
            backgroundScope.launch { transport.incoming.collect { events += it } }
            runCurrent()

            assertEquals(1, events.size)
        }

    @Test
    fun `link disconnect emits LinkLost exactly once`() =
        runTest {
            val link = FakeByteLink()
            val (transport, events) = startTransport(link)

            link.disconnect()
            runCurrent()
            link.disconnect()
            runCurrent()

            assertEquals(1, events.size)
            assertEquals(TransportEvent.LinkLost, events[0])
        }

    @Test
    fun `close disconnects the link and is idempotent`() =
        runTest {
            val link = FakeByteLink()
            val (transport, _) = startTransport(link)

            transport.close()
            transport.close()

            assertEquals(1, link.closedCount)
        }

    @Test
    fun `send after close is a silent no-op`() =
        runTest {
            val link = FakeByteLink()
            val (transport, _) = startTransport(link)

            transport.close()
            transport.send(ctrlFrame())

            assertEquals(0, link.sentBytes.size)
        }

    @Test
    fun `no events after close`() =
        runTest {
            val link = FakeByteLink()
            val (transport, events) = startTransport(link)

            transport.close()
            link.deliver(FrameCodec.encode(ctrlFrame()))
            link.disconnect()
            runCurrent()

            assertEquals(0, events.size)
        }
}

class NearbyLinkHubTest {
    @Test
    fun `one transport per endpoint id and bytes are demuxed by endpoint`() =
        runTest {
            val hub = NearbyLinkHub()
            val transportA = hub.transportFor("ep-A", FakeByteLink(), backgroundScope)
            val transportB = hub.transportFor("ep-B", FakeByteLink(), backgroundScope)
            assertSame(transportA, hub.transportFor("ep-A", FakeByteLink(), backgroundScope))
            assertFalse(transportA === transportB)

            val aEvents = mutableListOf<TransportEvent>()
            backgroundScope.launch { transportA.incoming.collect { aEvents += it } }
            val bEvents = mutableListOf<TransportEvent>()
            backgroundScope.launch { transportB.incoming.collect { bEvents += it } }

            hub.dispatchBytes("ep-A", FrameCodec.encode(ctrlFrame()))
            runCurrent()

            assertEquals(1, aEvents.size)
            assertEquals(0, bEvents.size)
        }

    @Test
    fun `disconnect removes the transport and emits LinkLost to its collector`() =
        runTest {
            val hub = NearbyLinkHub()
            val transport = hub.transportFor("ep-A", FakeByteLink(), backgroundScope)
            val events = mutableListOf<TransportEvent>()
            backgroundScope.launch { transport.incoming.collect { events += it } }

            hub.dispatchDisconnected("ep-A")
            runCurrent()

            assertEquals(TransportEvent.LinkLost, events.single())
            assertNull(hub.transportForOrNull("ep-A"))
            // A reconnect for the same endpoint id yields a fresh transport.
            assertTrue(hub.transportFor("ep-A", FakeByteLink(), backgroundScope) !== transport)
        }

    @Test
    fun `bytes for an unknown endpoint are dropped`() =
        runTest {
            val hub = NearbyLinkHub()
            hub.dispatchBytes("ep-ghost", byteArrayOf(1, 2, 3))
            runCurrent()
        }
}
