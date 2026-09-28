package neton.http.h3

import neton.http.h3.proto.Frame
import neton.http.h3.proto.FrameError
import neton.http.h3.proto.FrameException
import neton.http.h3.proto.FrameType
import neton.http.h3.proto.VarInt
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun encoded(vararg frames: Frame): ByteArray = Buffer().also { b -> frames.forEach { it.encodeWithPayload(b) } }.readAll()

private fun data(s: String) = Frame.Data(Bytes.wrap(s.encodeToByteArray()))

/**
 * The reference's `FakeRecv`: hands out one queued chunk per read, then the end of the stream. [pollNext] and
 * [pollData] replay `FrameStream::poll_next` / `poll_data` over the sans-I/O [FrameStream]: each call first reads one
 * more chunk (`try_recv`), and `poll_next` keeps reading while a frame is incomplete.
 */
private class FakeRecv(vararg chunks: ByteArray) {
    private val chunks = ArrayDeque(chunks.toList())
    val stream = FrameStream()

    private fun tryRecv() {
        if (stream.isEos) return
        val c = chunks.removeFirstOrNull()
        if (c == null) stream.onEnd() else stream.onData(c)
    }

    fun pollNext(): Frame? {
        while (true) {
            tryRecv()
            val f = stream.nextFrame()
            if (f != null || stream.isEos) return f
        }
    }

    fun pollData(): Bytes? {
        if (!stream.hasData) return null
        tryRecv()
        return stream.nextData()
    }
}

// Tests of `src/frame.rs`: the 5 decoder tests and the 7 FrameStream tests, then the sans-I/O specifics.
class FrameStreamTest {
    // ---- Decoder ----

    @Test
    fun one_frame() {
        val buf = Buffer().also { it.writeBytes(encoded(Frame.headers("salut"))) }
        assertIs<Frame.Headers>(FrameDecoder().decode(buf))
    }

    @Test
    fun incomplete_frame() {
        val b = Buffer()
        Frame.headers("salut").encode(b)
        val bytes = b.readAll()
        val buf = Buffer().also { it.writeBytes(bytes, 0, bytes.size - 1) }
        assertNull(FrameDecoder().decode(buf))
    }

    @Test
    fun header_spread_multiple_buf() {
        val bytes = encoded(Frame.headers("salut"))
        val d = FrameDecoder()
        val buf = Buffer()
        // Cut between type and length.
        buf.writeBytes(bytes, 0, 1)
        assertNull(d.decode(buf))
        buf.writeBytes(bytes, 1, bytes.size - 1)
        assertIs<Frame.Headers>(d.decode(buf))
    }

    @Test
    fun varint_spread_multiple_buf() {
        val bytes = encoded(Frame.headers("salut".repeat(1024)))
        val d = FrameDecoder()
        val buf = Buffer()
        // Cut in the middle of the length's varint.
        buf.writeBytes(bytes, 0, 2)
        assertNull(d.decode(buf))
        buf.writeBytes(bytes, 2, bytes.size - 2)
        assertIs<Frame.Headers>(d.decode(buf))
    }

    @Test
    fun two_frames_then_incomplete() {
        val bytes = encoded(Frame.headers("header"), data("body"), Frame.headers("trailer"))
        val buf = Buffer().also { it.writeBytes(bytes, 0, bytes.size - 1) }
        val d = FrameDecoder()
        assertIs<Frame.Headers>(d.decode(buf))
        val f = d.decode(buf)
        assertIs<Frame.Data>(f)
        assertEquals(4, f.length)
        // The DATA payload stays in the buffer for the caller.
        buf.skip(4)
        assertNull(d.decode(buf))
    }

    // ---- FrameStream ----

    @Test
    fun poll_full_request() {
        val recv = FakeRecv(encoded(Frame.headers("header"), data("body"), Frame.headers("trailer")))
        assertIs<Frame.Headers>(recv.pollNext())
        assertEquals(4, (recv.pollNext() as Frame.Data).length)
        assertEquals(4, recv.pollData()!!.size)
        assertIs<Frame.Headers>(recv.pollNext())
    }

    @Test
    fun poll_next_incomplete_frame() {
        val bytes = encoded(Frame.headers("header"))
        val recv = FakeRecv(bytes.copyOf(bytes.size - 1))
        val e = assertFailsWith<FrameException> { recv.pollNext() }
        assertEquals(FrameError.UnexpectedEnd, e.error)
    }

    @Test
    fun poll_next_reamining_data() {
        val b = Buffer()
        VarInt.encode(FrameType.DATA, b)
        VarInt.encode(4, b)
        val recv = FakeRecv(b.readAll())
        assertEquals(4, (recv.pollNext() as Frame.Data).length)
        // There is still data to consume: poll_next panics in the reference.
        val e = assertFailsWith<IllegalStateException> { recv.pollNext() }
        assertTrue(e.message!!.startsWith("There is still data to read, please call nextData() until it returns null"))
    }

    @Test
    fun poll_data_split() {
        // ⚖️ The body arrives in two chunks; the reference hands them out as received (2 + 2 bytes). Here received
        // bytes share one buffer, so by the time the payload is read both chunks are there and come out together.
        val bytes = encoded(data("body"))
        val recv = FakeRecv(bytes.copyOf(bytes.size - 2), bytes.copyOfRange(bytes.size - 2, bytes.size))
        assertEquals(4, (recv.pollNext() as Frame.Data).length)
        val out = ArrayList<Byte>()
        while (true) {
            val d = recv.pollData() ?: break
            assertTrue(d.size in 1..4)
            out.addAll(d.toByteArray().toList())
        }
        assertContentEquals("body".encodeToByteArray(), out.toByteArray())
        // Chunk by chunk, when the transport delivers them between reads, the pieces are those chunks.
        val s = FrameStream()
        s.onData(bytes.copyOf(bytes.size - 2))
        assertEquals(4, (s.nextFrame() as Frame.Data).length)
        assertContentEquals("bo".encodeToByteArray(), s.nextData()!!.toByteArray())
        assertNull(s.nextData())
        s.onData(bytes.copyOfRange(bytes.size - 2, bytes.size))
        assertContentEquals("dy".encodeToByteArray(), s.nextData()!!.toByteArray())
        assertNull(s.nextData())
        assertTrue(!s.hasData)
    }

    @Test
    fun poll_data_unexpected_end() {
        val b = Buffer()
        VarInt.encode(FrameType.DATA, b)
        VarInt.encode(4, b)
        b.writeBytes("b".encodeToByteArray())
        val recv = FakeRecv(b.readAll())
        assertEquals(4, (recv.pollNext() as Frame.Data).length)
        val e = assertFailsWith<FrameException> { recv.pollData() }
        assertEquals(FrameError.UnexpectedEnd, e.error)
    }

    @Test
    fun poll_data_ignores_unknown_frames() {
        val b = Buffer()
        // Grease a little.
        VarInt.encode(FrameType.grease(), b)
        VarInt.encode(0, b)
        // Grease with some data.
        VarInt.encode(FrameType.grease(), b)
        VarInt.encode(6, b)
        b.writeBytes("grease".encodeToByteArray())
        data("body").encodeWithPayload(b)
        val recv = FakeRecv(b.readAll())
        assertEquals(4, (recv.pollNext() as Frame.Data).length)
        assertContentEquals("body".encodeToByteArray(), recv.pollData()!!.toByteArray())
    }

    @Test
    fun poll_data_eos_but_buffered_data() {
        val b = Buffer()
        VarInt.encode(FrameType.DATA, b)
        VarInt.encode(4, b)
        b.writeBytes("bo".encodeToByteArray())
        val recv = FakeRecv(b.readAll())
        assertEquals(4, (recv.pollNext() as Frame.Data).length)
        recv.stream.onData("dy".encodeToByteArray())
        // ⚖️ The reference returns "bo" then "dy" (its buffered chunks); here the buffered bytes come out together.
        // What the test checks holds: data buffered when the end of the stream is seen is still delivered.
        val first = recv.pollData()!!
        assertTrue(recv.stream.isEos)
        assertContentEquals("body".encodeToByteArray(), first.toByteArray())
        assertNull(recv.pollData())
        assertNull(recv.pollNext())
        assertTrue(recv.stream.isFinished)
    }

    // ---- Sans-I/O specifics ----

    @Test
    fun everyByteBoundaryGivesTheSameFrames() {
        val bytes = encoded(
            Frame.headers("header"), data("body of some length"), Frame.Grease, Frame.headers("x".repeat(300)),
            data(""), Frame.headers("trailer"),
        )
        for (cut in 0..bytes.size) {
            val s = FrameStream()
            val seen = ArrayList<String>()
            fun drain() {
                while (true) {
                    if (s.hasData) {
                        val d = s.nextData() ?: return
                        seen.add("d" + d.size)
                        continue
                    }
                    val f = s.nextFrame() ?: return
                    seen.add(if (f is Frame.Data) "D" + f.length else "H" + (f as Frame.Headers).block.size)
                }
            }
            s.onData(bytes, 0, cut)
            drain()
            s.onData(bytes, cut, bytes.size - cut)
            s.onEnd()
            drain()
            assertTrue(s.isFinished, "cut $cut")
            val frames = seen.filter { !it.startsWith("d") }
            assertEquals(listOf("H6", "D19", "H300", "D0", "H7"), frames, "cut $cut")
            assertEquals(19, seen.filter { it.startsWith("d") }.sumOf { it.substring(1).toInt() }, "cut $cut")
        }
    }

    @Test
    fun endInsideAnUnknownFrameIsUnexpectedEnd() {
        val s = FrameStream()
        val b = Buffer()
        VarInt.encode(FrameType.RESERVED, b)
        VarInt.encode(10, b)
        b.writeBytes(ByteArray(3))
        s.onData(b.readAll())
        assertNull(s.nextFrame())
        s.onEnd()
        assertEquals(FrameError.UnexpectedEnd, assertFailsWith<FrameException> { s.nextFrame() }.error)
    }

    @Test
    fun cleanEndIsFinished() {
        val s = FrameStream()
        s.onData(encoded(Frame.headers("h")))
        s.onEnd()
        assertIs<Frame.Headers>(s.nextFrame())
        assertNull(s.nextFrame())
        assertTrue(s.isFinished)
    }

    @Test
    fun largeDataPayloadIsStreamedNotBuffered() {
        val s = FrameStream()
        val b = Buffer()
        VarInt.encode(FrameType.DATA, b)
        VarInt.encode(10_000_000, b)
        s.onData(b.readAll())
        assertEquals(10_000_000L, (s.nextFrame() as Frame.Data).length)
        var total = 0L
        repeat(100) {
            s.onData(ByteArray(100_000))
            total += s.nextData()!!.size
            assertTrue(s.buffer.isEmpty)
        }
        assertEquals(10_000_000L, total)
        assertTrue(!s.hasData)
    }
}
