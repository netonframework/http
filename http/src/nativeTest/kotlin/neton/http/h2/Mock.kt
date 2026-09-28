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
        return inner.read(dst)
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
                io.write(fw.writeBuffer)
                if (payload != null && payload.size > 0) {
                    io.write(Buffer.wrap(payload))
                    fw.advance(payload.size)
                }
            }
            if (!fw.unsetFrame()) break
        }
    }

    suspend fun writePreface() {
        io.write(Buffer().also { it.writeBytes(PREFACE_BYTES) })
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
        io.write(Buffer().also { it.writeBytes(data) })
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

/** `mock::new()`: the library's transport and the test's handle. */
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

/** Runs a mock test on a reactor with a timeout, so a hang fails instead of blocking the suite. */
fun h2Test(timeoutMs: Long = 20_000, block: suspend CoroutineScope.() -> Unit) = runReactor {
    withTimeout(timeoutMs) { block() }
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
