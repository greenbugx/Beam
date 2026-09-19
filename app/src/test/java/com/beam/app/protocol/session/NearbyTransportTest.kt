@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.beam.app.protocol.session

import com.beam.app.protocol.ChunkHeader
import com.beam.app.protocol.Frame
import com.beam.app.protocol.FrameCodec
import com.beam.app.protocol.FrameCodecKind
import com.beam.app.protocol.FrameType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class FakeByteLink : NearbyByteLink {
    override val incoming = MutableSharedFlow<NearbyByteLink.NearbyLinkEvent>(extraBufferCapacity = 64)
    val sentBytes = mutableListOf<ByteArray>()
    var closedCount = 0
    var failSend = false

    override suspend fun sendBytes(bytes: ByteArray) {
        if (failSend) throw IOException("Submission failed")
        sentBytes += bytes
    }

    override fun close() {
        closedCount++
    }

    suspend fun disconnect() = incoming.emit(NearbyByteLink.NearbyLinkEvent.Disconnected)
}

private fun TestScope.startTransport(link: FakeByteLink): Pair<NearbyTransport, MutableList<TransportEvent>> {
    val transport = NearbyTransport(link, backgroundScope)
    val events = mutableListOf<TransportEvent>()
    backgroundScope.launch { transport.incoming.collect { events += it } }
    runCurrent()
    return transport to events
}

private fun ctrlFrame(type: String = "HELLO"): Frame =
    Frame(FrameType.CTRL, """{"v":"BEAM/1.1","type":"$type","mid":"m1","sid":"s","did":"d"}""".encodeToByteArray())

private fun fragments(
    bytes: ByteArray,
    sequence: Long = 0,
): List<ByteArray> =
    (bytes.indices step (NearbyFragmentCodec.DEFAULT_MAX_PAYLOAD - NearbyFragmentCodec.HEADER_SIZE)).map {
        NearbyFragmentCodec.fragment(bytes, sequence, it, NearbyFragmentCodec.DEFAULT_MAX_PAYLOAD)
    }

class NearbyTransportTest {
    @Test
    fun `normal chunks and maximum frames round trip through SDK sized fragments`() =
        runTest {
            for (size in listOf(256 * 1024 + ChunkHeader.SIZE, FrameCodec.MAX_DATA_PAYLOAD)) {
                val sendingLink = FakeByteLink()
                val sender = NearbyTransport(sendingLink, backgroundScope)
                val (receiver, events) = startTransport(FakeByteLink())
                val frame = Frame(FrameType.DATA, ByteArray(size) { (it % 251).toByte() })
                sender.send(frame)
                assertTrue(sendingLink.sentBytes.all { it.size <= sendingLink.maxPayloadSize })
                sendingLink.sentBytes.forEach(receiver::onInboundBytes)
                runCurrent()
                assertEquals(frame, (events.single() as TransportEvent.FrameReceived).frame)
            }
        }

    @Test
    fun `interleaved out of order fragments preserve complete frame order`() =
        runTest {
            val (transport, events) = startTransport(FakeByteLink())
            val first = Frame(FrameType.DATA, ByteArray(256 * 1024) { it.toByte() })
            val second = ctrlFrame("ACK")
            val firstFragments = fragments(FrameCodec.encode(first))
            fragments(FrameCodec.encode(second), 1).forEach(transport::onInboundBytes)
            firstFragments.reversed().forEach(transport::onInboundBytes)
            runCurrent()
            assertEquals(listOf(first, second), events.map { (it as TransportEvent.FrameReceived).frame })
        }

    @Test
    fun `frames before subscription remain in order`() =
        runTest {
            val transport = NearbyTransport(FakeByteLink(), backgroundScope)
            val frames = listOf(ctrlFrame(), ctrlFrame("ACK"))
            frames.forEachIndexed { index, frame ->
                fragments(FrameCodec.encode(frame), index.toLong()).forEach(transport::onInboundBytes)
            }
            val events = mutableListOf<TransportEvent>()
            backgroundScope.launch { transport.incoming.collect { events += it } }
            runCurrent()
            assertEquals(frames, events.map { (it as TransportEvent.FrameReceived).frame })
        }

    @Test
    fun `codec errors retain malformed frame policy inside valid envelope`() =
        runTest {
            val (transport, events) = startTransport(FakeByteLink())
            fragments(byteArrayOf(0, 0, 0, 0, 0x7F)).forEach(transport::onInboundBytes)
            runCurrent()
            assertEquals(FrameCodecKind.UNKNOWN_TYPE, (events.single() as TransportEvent.FrameMalformed).kind)
            assertFalse(transport.isClosed)
        }

    @Test
    fun `submission failure terminates link instead of waiting for ACK`() =
        runTest {
            val link = FakeByteLink().also { it.failSend = true }
            val (transport, events) = startTransport(link)
            transport.send(ctrlFrame())
            runCurrent()
            assertEquals(listOf(TransportEvent.LinkLost), events)
            assertTrue(transport.isClosed)
            assertEquals(1, link.closedCount)
        }

    @Test
    fun `bounded event queue overflow delivers terminal failure to late subscriber`() =
        runTest {
            val link = FakeByteLink()
            val transport = NearbyTransport(link, backgroundScope)
            repeat(64) { sequence ->
                fragments(FrameCodec.encode(ctrlFrame()), sequence.toLong()).forEach(transport::onInboundBytes)
            }
            val events = mutableListOf<TransportEvent>()
            backgroundScope.launch { transport.incoming.collect { events += it } }
            runCurrent()
            assertTrue(transport.isClosed)
            assertEquals(listOf(TransportEvent.LinkLost), events)
            assertEquals(1, link.closedCount)
        }

    @Test
    fun `incomplete frames cannot grow beyond reassembly byte budget`() =
        runTest {
            val (transport, events) = startTransport(FakeByteLink())
            val frame = FrameCodec.encode(Frame(FrameType.DATA, ByteArray(FrameCodec.MAX_DATA_PAYLOAD)))
            repeat(16) { sequence ->
                transport.onInboundBytes(
                    NearbyFragmentCodec.fragment(frame, sequence.toLong(), 0, NearbyFragmentCodec.DEFAULT_MAX_PAYLOAD),
                )
            }
            runCurrent()
            assertTrue(transport.isClosed)
            assertEquals(listOf(TransportEvent.LinkLost), events)
        }

    @Test
    fun `concurrent sends are whole ordered frames`() =
        runTest {
            val link = FakeByteLink()
            val transport = NearbyTransport(link, backgroundScope)
            val frames = listOf(Frame(FrameType.DATA, ByteArray(256 * 1024)), ctrlFrame("ACK"))
            frames.forEach { frame -> launch { transport.send(frame) } }
            runCurrent()
            val reassembler = NearbyFragmentCodec.Reassembler(link.maxPayloadSize)
            val received = mutableListOf<ByteArray>()
            link.sentBytes.forEach { reassembler.accept(it, received::add) }
            assertEquals(frames, received.map(FrameCodec::decode))
        }

    @Test
    fun `disconnect emits once and close is idempotent`() =
        runTest {
            val link = FakeByteLink()
            val (transport, events) = startTransport(link)
            link.disconnect()
            runCurrent()
            transport.onInboundLinkLost()
            transport.close()
            transport.send(ctrlFrame())
            runCurrent()
            assertEquals(listOf(TransportEvent.LinkLost), events)
            assertEquals(1, link.closedCount)
            assertTrue(link.sentBytes.isEmpty())
        }

    @Test
    fun `explicit close suppresses later events and sends`() =
        runTest {
            val link = FakeByteLink()
            val (transport, events) = startTransport(link)
            transport.close()
            transport.close()
            fragments(FrameCodec.encode(ctrlFrame())).forEach(transport::onInboundBytes)
            transport.send(ctrlFrame())
            runCurrent()
            assertTrue(events.isEmpty())
            assertTrue(link.sentBytes.isEmpty())
            assertEquals(1, link.closedCount)
        }
}

class NearbyLinkHubTest {
    @Test
    fun `per endpoint transports keep send routing and receive state independent`() =
        runTest {
            val hub = NearbyLinkHub()
            val linkA = FakeByteLink()
            val linkB = FakeByteLink()
            val a = hub.transportFor("A", linkA, backgroundScope)
            val b = hub.transportFor("B", linkB, backgroundScope)
            assertSame(a, hub.transportFor("A", linkA, backgroundScope))
            val aEvents = mutableListOf<TransportEvent>()
            val bEvents = mutableListOf<TransportEvent>()
            backgroundScope.launch { a.incoming.collect { aEvents += it } }
            backgroundScope.launch { b.incoming.collect { bEvents += it } }
            a.send(ctrlFrame())
            b.send(ctrlFrame("ACK"))
            linkA.sentBytes.forEach { hub.dispatchBytes("A", it) }
            linkB.sentBytes.forEach { hub.dispatchBytes("B", it) }
            runCurrent()
            assertEquals(ctrlFrame(), (aEvents.single() as TransportEvent.FrameReceived).frame)
            assertEquals(ctrlFrame("ACK"), (bEvents.single() as TransportEvent.FrameReceived).frame)
        }

    @Test
    fun `reconnect discards partial framing and old transport cannot close replacement`() =
        runTest {
            val hub = NearbyLinkHub()
            val oldLink = FakeByteLink()
            val old = hub.transportFor("A", oldLink, backgroundScope)
            val partial = FrameCodec.encode(Frame(FrameType.DATA, ByteArray(256 * 1024)))
            old.onInboundBytes(fragments(partial).first())
            hub.dispatchDisconnected("A")
            assertNull(hub.transportForOrNull("A"))
            val newLink = FakeByteLink()
            val replacement = hub.transportFor("A", newLink, backgroundScope)
            val events = mutableListOf<TransportEvent>()
            backgroundScope.launch { replacement.incoming.collect { events += it } }
            old.close()
            fragments(FrameCodec.encode(ctrlFrame())).forEach { hub.dispatchBytes("A", it) }
            runCurrent()
            assertFalse(old === replacement)
            assertEquals(0, newLink.closedCount)
            assertArrayEquals(ctrlFrame().payload, (events.single() as TransportEvent.FrameReceived).frame.payload)
        }
}
