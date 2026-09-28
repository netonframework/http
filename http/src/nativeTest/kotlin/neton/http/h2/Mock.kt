package neton.http.h2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import neton.http.Request
import neton.http.h2.codec.Codec
import neton.http.h2.frame.Data
import neton.http.h2.frame.Frame
import neton.http.h2.frame.Settings
import neton.http.h2.proto.ProtoError
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoException
import neton.io.core.IoStream
import neton.io.core.StreamCapability
import neton.io.core.memoryStreamPair
import neton.io.net.runReactor
import kotlin.coroutines.resume
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// Test support ported from h2 0.4.19 `tests/h2-support/src/mock.rs` (the mock transport and its `Handle`), `util.rs`
// and `client_ext.rs`. The transport is a neton-io `memoryStreamPair`; the library's end is wrapped to keep the
// reference mock's behaviour: a write budget the test can set (`buffer_bytes`), a simulated unclean EOF
// (`close_without_notify`), and writes after the test's end is closed succeeding silently.

val PREFACE_BYTES: ByteArray = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".encodeToByteArray()

/** Shared state of a mock pair (`Inner`). */
class MockShared {
    /** Bytes the library may still write before its write waits (`tx_rem`). */
    var txRem: Long = Long.MAX_VALUE
    var txRemWaiter: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    var txWaiter: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    var handleClosed = false
    var unexpectedEof = false

    fun wakeTxRem() {
        val w = txRemWaiter ?: return
        txRemWaiter = null
        if (w.isActive) w.resume(Unit)
    }

    fun wakeTx() {
        val w = txWaiter ?: return
        txWaiter = null
        if (w.isActive) w.resume(Unit)
    }
}

/** The library's end of the mock (`Mock`). */
class MockIo(private val inner: IoStream, private val shared: MockShared) : IoStream {
    override val capabilities: Set<StreamCapability> get() = inner.capabilities

    override suspend fun read(dst: Buffer): Int {
        if (shared.unexpectedEof) throw UnexpectedEofException("Simulate an unexpected eof error")
        val n = inner.read(dst)
        // The reference's mock checks the flag on every poll, including the one after a parked read is woken.
        if (n < 0 && shared.unexpectedEof) throw UnexpectedEofException("Simulate an unexpected eof error")
        return n
    }

    override suspend fun write(src: Buffer): Int {
        val total = src.readableBytes
        while (src.readableBytes > 0) {
            if (shared.handleClosed) {
                // The reference's mock accepts writes after the test dropped its handle.
                src.skip(src.readableBytes)
                break
            }
            if (shared.txRem == 0L) {
                suspendCancellableCoroutine { c -> shared.txRemWaiter = c }
                continue
            }
            val n = minOf(shared.txRem, src.readableBytes.toLong()).toInt()
            val chunk = Buffer(n)
            chunk.writeBytes(src.backingArray(), src.readerIndex(), n)
            src.skip(n)
            try {
                inner.write(chunk)
            } catch (e: IoException) {
                if (!shared.handleClosed) throw e
            }
            if (shared.txRem != Long.MAX_VALUE) shared.txRem -= n
            shared.wakeTx()
        }
        return total
    }

    override suspend fun flush() = inner.flush()

    override fun close() = inner.close()

    override suspend fun shutdownOutput() {
        try {
            inner.shutdownOutput()
        } catch (e: IoException) {
            if (!shared.handleClosed) throw e
        }
    }
}

/** A frame given to the mock: a [Frame] or one of the `Frames` builders. */
fun toFrame(f: Any): Frame = when (f) {
    is Frame -> f
    is MockHeaders -> f.frame
    is MockData -> f.frame
    is MockPushPromise -> f.frame
    is MockGoAway -> f.frame
    is MockReset -> f.frame
    is MockSettings -> f.frame
    is MockPing -> f.frame
    else -> throw IllegalArgumentException("not a frame: $f")
}

/** `assert_frame_eq`. */
fun assertFrameEq(actual: Any, expected: Any) {
    val a = toFrame(actual)
    val e = toFrame(expected)
    if (a is Data && e is Data) assertEquals(e.payload.size, a.payload.size, "assert_frame_eq data payload len")
    assertEquals(e, a, "assert_frame_eq")
}

/** The test's end of the mock (`Handle`): reads and writes frames with a codec. */
class Handle(private val io: IoStream, val shared: MockShared) {
    val codec = Codec()
    private val readBuf = Buffer()
    private var eof = false

    /** `close_without_notify`: the library's next read fails with UnexpectedEof. */
    fun closeWithoutNotify() {
        shared.unexpectedEof = true
    }

    /** The next frame, or null at EOF (`Stream::poll_next`). @throws ProtoError for a codec error. */
    suspend fun next(): Frame? {
        while (true) {
            val f = if (eof) codec.decodeEof(readBuf) else codec.decode(readBuf)
            if (f != null || eof) return f
            if (io.read(readBuf) < 0) eof = true
        }
    }

    /** Sends a frame (`send`). */
    suspend fun send(frame: Frame) {
        check(codec.buffer(frame) == null)
        flush()
    }

    suspend fun sendFrame(frame: Any) = send(toFrame(frame))

    private suspend fun flush() {
        val fw = codec.writer
        while (true) {
            if (!fw.isEmpty) {
                val payload = fw.queuedPayload
                put(fw.writeBuffer)
                if (payload != null && payload.size > 0) {
                    put(Buffer.wrap(payload))
                    fw.advance(payload.size)
                }
            }
            if (!fw.unsetFrame()) break
        }
    }

    /**
     * Writes to the library. As in the reference's mock, a write always succeeds: once the library's end is gone the
     * bytes are dropped.
     */
    private suspend fun put(b: Buffer) {
        try {
            io.write(b)
        } catch (e: IoException) {
            b.skip(b.readableBytes)
        }
    }

    suspend fun writePreface() {
        put(Buffer().also { it.writeBytes(PREFACE_BYTES) })
    }

    /** Reads the client preface (`read_preface`). */
    suspend fun readPreface() {
        while (readBuf.readableBytes < PREFACE_BYTES.size) {
            if (io.read(readBuf) < 0) fail("EOF before preface")
        }
        val got = readBuf.readBytes(PREFACE_BYTES.size)
        assertTrue(got.contentEquals(PREFACE_BYTES), "preface")
    }

    /** `recv_frame`: the next frame equals [expected]. */
    suspend fun recvFrame(expected: Any) {
        val frame = next() ?: fail("unexpected EOF; expected ${toFrame(expected)}")
        assertFrameEq(frame, expected)
    }

    /** `recv_eof`. */
    suspend fun recvEof() {
        val frame = next()
        assertNull(frame, "expected EOF")
    }

    /** Writes raw bytes (`send_bytes`). */
    suspend fun sendBytes(data: ByteArray) {
        put(Buffer().also { it.writeBytes(data) })
    }

    /** Performs the handshake as the server of a client under test (`assert_client_handshake`). */
    suspend fun assertClientHandshake(settings: Any = Settings()): Settings {
        send(toFrame(settings))
        readPreface()
        val theirs = assertIs<Settings>(next() ?: fail("unexpected EOF"))
        send(Settings.ack())
        val ack = assertIs<Settings>(next())
        assertTrue(ack.isAck)
        return theirs
    }

    /** Performs the handshake as the client of a server under test (`assert_server_handshake`). */
    suspend fun assertServerHandshake(settings: Any = Settings()): Settings {
        writePreface()
        send(toFrame(settings))
        val theirs = assertIs<Settings>(next() ?: fail("unexpected EOF"))
        send(Settings.ack())
        val ack = assertIs<Settings>(next())
        assertTrue(ack.isAck)
        return theirs
    }

    suspend fun pingPong(payload: ByteArray) {
        sendFrame(Frames.ping(payload))
        recvFrame(Frames.ping(payload).pong())
    }

    /** Lets the library write exactly [num] more bytes, waiting until it did (`buffer_bytes`). */
    suspend fun bufferBytes(num: Int) {
        shared.txRem = num.toLong()
        shared.wakeTxRem()
        while (shared.txRem != 0L) suspendCancellableCoroutine { c -> shared.txWaiter = c }
        shared.txRem = Long.MAX_VALUE
        // The reference's pending write is retried the next time the connection task is woken (by a read, say); a
        // parked writer here needs the wake-up a writable transport would give it.
        shared.wakeTxRem()
    }

    /** Lifts the write budget (`unbounded_bytes`). */
    fun unboundedBytes() {
        shared.txRem = Long.MAX_VALUE
        shared.wakeTxRem()
    }

    /** Drops the handle: the library reads EOF; its writes are discarded (`Drop for Handle`). */
    fun close() {
        shared.handleClosed = true
        shared.wakeTxRem()
        io.close()
    }

    /** Frames of this handle's codec are limited to [size] (`codec_mut().set_max_recv_frame_size`). */
    fun setMaxRecvFrameSize(size: Int) = codec.setMaxRecvFrameSize(size)
}

/**
 * `mock::new()`: the library's transport and the test's handle.
 *
 * Memory-only (SPEC §6 exception): the reference's mock is an in-memory pipe whose bytes the library sees on its very
 * next poll, and the tests built on it assert exact frame orders that follow from that (a client's SETTINGS ACK
 * before its first HEADERS, every DATA frame the handle sent already received when `accept` returns, a clean EOF when
 * the handle is dropped with unread bytes). Over loopback TCP the library sees the handle's bytes only after a
 * reactor poll round, so those interleavings legitimately differ (and a close with unread bytes is a reset): run with
 * NETON_HTTP_TEST_TRANSPORT=tcp, 121 of these tests failed on that alone. The same library paths run over TCP in
 * the hyper-level HTTP/2 tests, TcpEndToEndTest and HammerTest.
 */
fun mockNew(): Pair<IoStream, Handle> {
    val (a, b) = memoryStreamPair(capacity = 64 * 1024 * 1024)
    val shared = MockShared()
    return MockIo(a, shared) to Handle(b, shared)
}

/** `mock::new_with_write_capacity(cap)`. */
fun mockNewWithWriteCapacity(cap: Int): Pair<IoStream, Handle> {
    val p = mockNew()
    p.second.shared.txRem = cap.toLong()
    return p
}

/**
 * A scripted transport (tokio-test's `io::Builder`, with h2-support's `MockH2::handshake`): the library must write
 * exactly the `write` chunks, in order, and reads the `read` chunks. A read waits until the writes scripted before it
 * are done; writes may run ahead of reads. Past the script, reads are EOF and writes fail. [assertDone] checks the
 * whole script was used (tokio-test's `Drop` check).
 */
class MockIoBuilder {
    private val actions = ArrayList<ScriptedIo.Action>()

    fun read(b: ByteArray) = apply { actions.add(ScriptedIo.Action(ScriptedIo.READ, b)) }

    fun write(b: ByteArray) = apply { actions.add(ScriptedIo.Action(ScriptedIo.WRITE, b)) }

    /** `wait(duration)`: reads and writes past this point wait [ms] once it is reached. */
    fun wait(ms: Long) = apply { actions.add(ScriptedIo.Action(ScriptedIo.WAIT, ByteArray(0), ms)) }

    /** `handshake`: the client preface and SETTINGS written; the server's SETTINGS and ACK read. */
    fun handshake() = handshakeReadSettings(Frames.SETTINGS)

    fun handshakeReadSettings(settings: ByteArray) =
        write(PREFACE_BYTES).write(Frames.SETTINGS).read(settings).read(Frames.SETTINGS_ACK)

    fun build(): ScriptedIo = ScriptedIo(actions)
}

class ScriptedIo(private val actions: MutableList<Action>) : IoStream {
    class Action(val kind: Int, val data: ByteArray, val waitMs: Long = 0) {
        var pos = 0
        var waited = false
        val done: Boolean get() = if (kind == WAIT) waited else pos == data.size
    }

    private var waiters = ArrayList<kotlinx.coroutines.CancellableContinuation<Unit>>()
    private var consumed = 0

    /** The first mismatch, reported again by [assertDone]. */
    var failure: Throwable? = null
        private set

    override val capabilities: Set<StreamCapability> get() = setOf(StreamCapability.HalfClose)

    private fun front(): Action? {
        while (actions.isNotEmpty() && actions[0].done) {
            actions.removeAt(0)
            consumed++
            wake()
        }
        return actions.firstOrNull()
    }

    /** A wait at the front of the script elapses. */
    private suspend fun settleWait(a: Action) {
        delay(a.waitMs)
        a.waited = true
    }

    override suspend fun read(dst: Buffer): Int {
        while (true) {
            val a = front() ?: return -1
            when (a.kind) {
                READ -> {
                    val n = a.data.size - a.pos
                    dst.writeBytes(a.data, a.pos, n)
                    a.pos += n
                    front()
                    return n
                }
                WAIT -> settleWait(a)
                else -> park()
            }
        }
    }

    override suspend fun write(src: Buffer): Int {
        val total = src.readableBytes
        while (true) {
            val f = front() ?: throw IoException("broken pipe: write past the script")
            if (f.kind == WAIT) {
                settleWait(f)
                continue
            }
            var blockedByWait = false
            for (a in actions) {
                if (src.readableBytes == 0) break
                if (a.kind == WAIT) {
                    blockedByWait = true
                    break
                }
                if (a.kind == READ || a.done) continue
                val n = minOf(src.readableBytes, a.data.size - a.pos)
                for (i in 0 until n) {
                    if (src.getByte(i) != a.data[a.pos + i]) {
                        val e = AssertionError(
                            "write buffer mismatch: expected ${a.data.drop(a.pos).take(n).map { it.toInt() and 0xff }}, " +
                                "got ${src.peekAll().take(n).map { it.toInt() and 0xff }}",
                        )
                        failure = failure ?: e
                        throw e
                    }
                }
                a.pos += n
                src.skip(n)
            }
            front()
            if (src.readableBytes == 0) return total
            if (!blockedByWait) {
                val e = AssertionError("unexpected write of ${src.readableBytes} bytes: ${src.peekAll().map { it.toInt() and 0xff }}")
                failure = failure ?: e
                throw e
            }
            // The rest goes after a wait further down the script: wait for the reads before it.
            park()
        }
    }

    override suspend fun flush() {}

    override suspend fun shutdownOutput() {}

    override fun close() {}

    private suspend fun park() {
        suspendCancellableCoroutine { c -> waiters.add(c) }
    }

    private fun wake() {
        if (waiters.isEmpty()) return
        val w = waiters
        waiters = ArrayList()
        for (c in w) if (c.isActive) c.resume(Unit)
    }

    /**
     * Waits until [count] scripted actions were consumed (for the reference's tests that poll the connection by hand
     * until it is idle before going on).
     */
    suspend fun awaitConsumed(count: Int) {
        while (consumed < count) {
            front()
            if (consumed >= count) break
            park()
        }
    }

    /** Every scripted read and write happened (tokio-test's `Drop` check). */
    fun assertDone() {
        failure?.let { throw it }
        val left = actions.filter { !it.done }
        assertTrue(left.isEmpty(), "There is still data left to ${if (left.firstOrNull()?.kind == READ) "read" else "write"}")
    }

    companion object {
        const val READ = 0
        const val WRITE = 1
        const val WAIT = 2
    }
}

/** `idle_ms`. */
suspend fun idleMs(ms: Long) = delay(ms)

/** `util::concat`: the whole body. */
suspend fun concat(body: RecvStream): ByteArray {
    val out = ArrayList<Byte>()
    while (true) {
        val chunk = body.data() ?: break
        for (i in 0 until chunk.size) out.add(chunk[i])
    }
    return out.toByteArray()
}

/**
 * `util::wait_for_capacity`: waits until [stream] has at least [target] capacity. Only after a non-0 capacity was
 * requested: until then, 0 is never reported.
 */
suspend fun waitForCapacity(stream: SendStream, target: Int): SendStream {
    while (true) {
        kotlin.test.assertNotNull(stream.awaitCapacity(), "poll_capacity returned None")
        val act = stream.capacity()
        kotlin.test.assertNotEquals(0, act)
        if (act >= target) return stream
    }
}

/** `util::yield_once`. */
suspend fun yieldOnce() = kotlinx.coroutines.yield()

/** `SendRequestExt::get`: a GET without body. */
fun neton.http.h2.client.SendRequest.get(uri: String): neton.http.h2.client.ResponseFuture {
    val req = Request.builder().uri(uri).body(Unit)
    val (fut, tx) = sendRequest(req, true)
    tx.close()
    return fut
}

/** A request with the builder's defaults and [uri]. */
fun request(method: String, uri: String): Request<Unit> = Request.builder().method(method).uri(uri).body(Unit)

fun bytes(s: String): Bytes = Bytes.copyOf(s.encodeToByteArray())

fun bytes(n: Int, b: Byte = 0): Bytes = Bytes.wrap(ByteArray(n) { b })

fun headerMapOf(vararg pairs: Pair<String, String>): HeaderMap<HeaderValue> {
    val m = HeaderMap<HeaderValue>()
    for ((k, v) in pairs) m.append(neton.http.header.HeaderName.fromStr(k), HeaderValue.fromStr(v))
    return m
}

/**
 * Runs a mock test on a reactor with a timeout, so a hang fails instead of blocking the suite. Coroutines the test
 * launched and left running (a connection nobody waits for) are cancelled once [block] returns, as the reference's
 * runtime drops its spawned tasks at the end of a test.
 */
fun h2Test(timeoutMs: Long = 20_000, block: suspend CoroutineScope.() -> Unit) = runReactor {
    withTimeout(timeoutMs) {
        kotlinx.coroutines.coroutineScope {
            block()
            coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() }
        }
    }
}

/** `poll_err!`: the next read fails. */
suspend fun Handle.pollErr(): ProtoError {
    try {
        val f = next()
        fail("expected error; actual=$f")
    } catch (e: ProtoError) {
        return e
    }
}
