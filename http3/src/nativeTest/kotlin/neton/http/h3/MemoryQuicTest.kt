package neton.http.h3

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import neton.http.h3.proto.Dir
import neton.http.h3.proto.Side
import neton.http.h3.quic.ConnectionErrorIncoming
import neton.http.h3.quic.RecvStream
import neton.http.h3.quic.StreamErrorIncoming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private suspend fun RecvStream.readAll(): String {
    val sb = StringBuilder()
    while (true) sb.append((read() ?: return sb.toString()).decodeToString())
}

// The in-memory QUIC double itself: the behaviours the HTTP/3 connection tests rely on.
class MemoryQuicTest {
    @Test
    fun bidiRoundTrip() = h3Test {
        val (client, server) = memoryQuicPair()
        val s = client.openBi()
        assertEquals(0L, s.sendId.value)
        s.write(bytes("hello"))
        s.finish()
        val peer = server.acceptBi()
        assertEquals(0L, peer.recvId.value)
        assertEquals("hello", peer.readAll())
        peer.write(bytes("world"))
        peer.finish()
        assertEquals("world", s.readAll())
        assertNull(s.stopped())
    }

    @Test
    fun streamVisibleOnFirstUseInIdOrder() = h3Test {
        val (client, server) = memoryQuicPair()
        val a = client.openUni()
        val b = client.openUni()
        assertEquals(2L, a.sendId.value)
        assertEquals(6L, b.sendId.value)
        assertEquals(Dir.Uni, b.sendId.dir)
        assertEquals(Side.Client, b.sendId.initiator)
        val accepted = async { server.acceptUni() }
        yield()
        assertFalse(accepted.isCompleted, "an unused stream is not visible")
        b.write(bytes("b"))
        // Using stream 6 opens stream 2 as well; they are accepted in ID order.
        assertEquals(2L, accepted.await().recvId.value)
        assertEquals(6L, server.acceptUni().recvId.value)
    }

    @Test
    fun boundedBufferBackpressure() = h3Test {
        val (client, server) = memoryQuicPair(streamCapacity = 8)
        val s = client.openUni()
        var written = false
        val writer = launch {
            s.write(bytes("0123456789abcdefghij"))
            written = true
        }
        val r = server.acceptUni()
        yield()
        assertFalse(written, "the writer waits for the reader")
        assertEquals("01234567", r.read()!!.decodeToString())
        yield()
        assertFalse(written, "8 more bytes fit, then the writer waits again")
        assertEquals("89abcdef", r.read()!!.decodeToString())
        assertEquals("ghij", r.read()!!.decodeToString())
        writer.join()
        assertTrue(written)
    }

    @Test
    fun resetAndStopSending() = h3Test {
        val (client, server) = memoryQuicPair()
        val s = client.openBi()
        s.write(bytes("x"))
        val peer = server.acceptBi()
        s.reset(0x10c)
        // What was received before the reset is still read, then the reset.
        assertEquals("x", peer.read()!!.decodeToString())
        assertEquals(0x10cL, assertFailsWith<StreamErrorIncoming.StreamTerminated> { peer.read() }.errorCode)
        assertNull(s.stopped())

        val stopped = async { peer.stopped() }
        s.stopSending(0x10b)
        assertEquals(0x10bL, stopped.await())
        assertEquals(0x10bL, assertFailsWith<StreamErrorIncoming.StreamTerminated> { peer.write(bytes("y")) }.errorCode)
    }

    @Test
    fun closeWithCode() = h3Test {
        val (client, server) = memoryQuicPair()
        val s = client.openBi()
        s.write(bytes("x"))
        val peer = server.acceptBi()
        val pendingRead = async { assertFailsWith<StreamErrorIncoming.ConnectionLost> { s.read() }.connectionError }
        server.close(Code.H3_NO_ERROR, ByteArray(0))
        assertEquals(ConnectionErrorIncoming.ApplicationClose(0x100), pendingRead.await())
        assertEquals(ConnectionErrorIncoming.ApplicationClose(0x100), assertFailsWith<ConnectionErrorIncoming> { client.acceptBi() })
        assertIs<ConnectionErrorIncoming.Undefined>(assertFailsWith<ConnectionErrorIncoming> { server.acceptUni() })
        assertIs<StreamErrorIncoming.ConnectionLost>(assertFailsWith<StreamErrorIncoming> { peer.write(bytes("y")) })
        assertEquals(Side.Server, client.link.closedBy)
        assertEquals(0x100L, client.link.closeCode)
    }

    @Test
    fun idleTimeout() = h3Test {
        val (client, server) = memoryQuicPair(this, idleTimeout = 50.milliseconds)
        val s = client.openUni()
        s.write(bytes("x"))
        server.acceptUni()
        assertIs<ConnectionErrorIncoming.Timeout>(assertFailsWith<ConnectionErrorIncoming> { server.acceptBi() })
        assertIs<ConnectionErrorIncoming.Timeout>(assertFailsWith<ConnectionErrorIncoming> { client.acceptBi() })
    }
}
