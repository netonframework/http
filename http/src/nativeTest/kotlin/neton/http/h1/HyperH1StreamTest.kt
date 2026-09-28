package neton.http.h1

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import neton.http.Body
import neton.http.Frame
import neton.http.FullBody
import neton.http.Response
import neton.http.StatusCode
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.core.monotonicNanos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// hyper 1.11.1 `tests/h1_flush_before_yield.rs`, `tests/h1_shutdown_while_buffered.rs`, `tests/unbuffered_stream.rs`,
// `tests/ready_on_poll_stream.rs` and their fixture `tests/h1_server/`. hyper's streams act on individual `poll_*`
// calls; here each is the coroutine equivalent (a `Pending` that is later ready is a suspension that later resumes).

/** hyper `h1_flush_before_yield.rs`. */
class H1FlushBeforeYieldTest {
    /**
     * hyper `PendOnceThenEnd`: one data frame, then `Pending`, then end-of-stream on the very next poll. hyper's
     * `Pending` arranges no wake-up (it stands for readiness changing between two write polls); the coroutine
     * equivalent is a suspension that resumes at once with the end.
     */
    private class PendOnceThenEnd : Body {
        private var polls = 0
        override suspend fun nextFrame(): Frame? {
            polls++
            return when (polls) {
                1 -> Frame.Data(bytesOf("hello"))
                else -> { yield(); null }
            }
        }
    }

    @Test
    fun h1ServerFlushesEndOfBodyBufferedByWriteRecheck() = hyperTest {
        val (listener, port) = listenLocal()
        spawn {
            val socket = listener.accept().closeAtEnd()
            runCatching { Http1ServerConfig().serveConnection(socket) { Response(PendOnceThenEnd() as Body) }.serve() }
        }
        val client = connectLocal(port)
        client.writeAll("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")

        // The whole response must arrive, terminating chunk included.
        val received = Buffer()
        val read = withTimeoutOrNull(5_000) {
            while (true) {
                if (client.read(received) < 0) break
                if (s(received.peekAll()).endsWith("\r\n0\r\n\r\n")) break
            }
        }
        val got = s(received.peekAll())
        assertTrue(read != null, "response never completed; got $got")
        assertTrue(got.endsWith("\r\n0\r\n\r\n"), "missing terminating chunk; got $got")
        assertTrue(got.contains("hello"), "missing body content; got $got")
    }
}

/** hyper `h1_shutdown_while_buffered.rs`. */
class H1ShutdownWhileBufferedTest {
    private class PendingStreamStatistics {
        var bytesWritten = 0L
        var totalAttempted = 0L
        var shutdownCalledWithBuffered = false
        var bufferedAtShutdown = 0L
    }

    /**
     * hyper `PendingStream`: the first write sends only [writeChunkSize] bytes, then every write stays pending forever;
     * a flush with bytes still buffered stays pending; a shutdown records whether bytes were still buffered. (Here a
     * write must write everything, so the first one writes its prefix and then stays pending.)
     */
    private class PendingStream(inner: IoStream, private val writeChunkSize: Int, val stats: PendingStreamStatistics) : ForwardingStream(inner) {
        private var writeCount = 0

        override suspend fun write(src: Buffer): Int {
            writeCount++
            stats.totalAttempted += src.readableBytes
            if (writeCount == 1) {
                // First write: partial only
                val partial = minOf(src.readableBytes, writeChunkSize)
                val part = Buffer(partial).also { it.writeBytes(src.backingArray(), src.readerIndex(), partial) }
                src.skip(partial)
                stats.bytesWritten += inner.write(part)
            }
            // Block all further writes to simulate pending buffer
            awaitCancellation()
        }

        override suspend fun flush() {
            if (stats.totalAttempted - stats.bytesWritten > 0) awaitCancellation()
            inner.flush()
        }

        override suspend fun shutdownOutput() {
            val buffered = stats.totalAttempted - stats.bytesWritten
            if (buffered > 0) {
                stats.shutdownCalledWithBuffered = true
                stats.bufferedAtShutdown = buffered
            }
            inner.shutdownOutput()
        }
    }

    // Test doesn't necessarily check that the connections ended successfully but mainly that shutdown wasn't called
    // with data still remaining within the connection's buffer.
    @Test
    fun testNoPrematureShutdownWhileBuffered() = hyperTest {
        val (listener, port) = listenLocal()
        val stats = PendingStreamStatistics()
        val server = spawn {
            val stream = listener.accept().closeAtEnd()
            val pendingStream = PendingStream(stream, 212_992, stats)
            // hyper writes through a stream without vectored writes: its flatten strategy (one buffer).
            Http1ServerConfig(writev = false).serveConnection(pendingStream) {
                // Larger Full response than write_chunk_size
                Response(FullBody(Bytes.copyOf(ByteArray(500_000) { 'X'.code.toByte() })) as Body)
            }.serve()
        }
        // Client sends request
        spawn {
            val stream = connectLocal(port)
            stream.writeAll("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n")
            stream.writeAll("A\r\nHello World\r\n")
            stream.writeAll("0\r\n\r\n")
            stream.flush()
            // keep connection open
            delay(2_000)
        }
        // Wait for completion
        withTimeoutOrNull(900) { runCatching { server.await() } }
        assertFalse(
            stats.shutdownCalledWithBuffered,
            "shutdown() called with ${stats.bufferedAtShutdown} bytes still buffered (wrote ${stats.bytesWritten} of ${stats.totalAttempted} bytes)",
        )
    }
}

// ---- `tests/h1_server/`: the fixture shared by `unbuffered_stream.rs` and `ready_on_poll_stream.rs` ---------------

/**
 * hyper `StreamReadHalf`: reads what the client sends through a channel; a closed channel is EOF. (hyper keeps what
 * does not fit the caller's buffer for the next read; a neton-io read buffer grows, so a message is always taken whole.)
 */
internal class StreamReadHalf(private val readRx: Channel<ByteArray>) {
    suspend fun read(dst: Buffer): Int {
        val r = readRx.receiveCatching()
        val data = r.getOrNull() ?: return -1
        dst.writeBytes(data)
        return data.size
    }
}

/** hyper `fixture::Client`: the other ends of the two channels. */
internal class FixtureClient(val rx: Channel<ByteArray>, val tx: Channel<ByteArray>)

/** hyper `fixture::TestConfig`. */
internal class FixtureConfig(val totalChunks: Int = 16, val chunkSize: Int = 64 * 1024, val chunkTimeoutMillis: Long)

/** hyper `fixture::run`: a 1 MiB response in 64 KiB frames; each chunk must reach the client within the timeout. */
internal suspend fun HyperScope.runFixture(server: IoStream, client: FixtureClient, config: FixtureConfig) {
    val totalChunks = config.totalChunks
    val chunkSize = config.chunkSize
    val service = HttpService {
        val bytes = ByteArray(chunkSize)
        val body = StreamBody(List(totalChunks) { bytes })
        Response.builder()
            .status(StatusCode.OK)
            .header("content-type", "application/octet-stream")
            .header("content-length", (totalChunks * chunkSize).toString())
            .body(body as Body)
    }
    // The streams have no read timeout: the (⚖️) header timeouts are off, as hyper's are without a timer.
    val httpBuilder = Http1ServerConfig(maxBufSize = chunkSize, headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0)
    val serverTask = spawn { httpBuilder.serveConnection(server, service).serve() }

    client.tx.send("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".encodeToByteArray())

    var bytesReceived = 0
    val allData = Buffer()
    while (true) {
        val chunk = withTimeoutOrNull(config.chunkTimeoutMillis) { client.rx.receiveCatching() }
            ?: error("Chunk timeout: chunk took longer than ${config.chunkTimeoutMillis} ms")
        val bytes = chunk.getOrNull() ?: break
        bytesReceived += bytes.size
        allData.writeBytes(bytes)
    }
    // Clean up
    serverTask.await()

    // Parse HTTP response to find body start
    val all = allData.readAll()
    var bodyStart = 0
    for (i in 0..all.size - 4) {
        if (all[i] == '\r'.code.toByte() && all[i + 1] == '\n'.code.toByte() && all[i + 2] == '\r'.code.toByte() && all[i + 3] == '\n'.code.toByte()) {
            bodyStart = i + 4
            break
        }
    }
    val bodyBytes = bytesReceived - bodyStart
    assertEquals(
        config.totalChunks * config.chunkSize, bodyBytes,
        "Expected ${config.totalChunks * config.chunkSize} body bytes, got $bodyBytes (total received: $bytesReceived, headers: $bodyStart)",
    )
}

private const val WRITE_DELAY_MILLIS = 100L

private fun nowMillis(): Long = monotonicNanos() / 1_000_000

/**
 * hyper `UnbufferedStream`: a write is accepted at once and sent to the client, but the next write waits until
 * [WRITE_DELAY_MILLIS] after it; flush and shutdown are always ready. (hyper also fails a write that is "hot polled"
 * more than four times while waiting; a suspended write here is resumed once, when its delay has passed.)
 */
private class UnbufferedStream(private val readHalf: StreamReadHalf, private val writeTx: Channel<ByteArray>) : IoStream {
    private var pendingWriteUntil = 0L

    override suspend fun read(dst: Buffer): Int = readHalf.read(dst)

    override suspend fun write(src: Buffer): Int {
        if (pendingWriteUntil != 0L) {
            delay(pendingWriteUntil - nowMillis())
            pendingWriteUntil = 0
        }
        val buf = src.readAll()
        writeTx.send(buf)
        pendingWriteUntil = nowMillis() + WRITE_DELAY_MILLIS
        return buf.size
    }

    override suspend fun flush() {}
    override suspend fun shutdownOutput() {}
    override fun close() { writeTx.close() }

    companion object {
        /** hyper `UnbufferedStream::new_pair`. */
        fun newPair(): Pair<UnbufferedStream, FixtureClient> {
            val clientToServer = Channel<ByteArray>(Channel.UNLIMITED)
            val serverToClient = Channel<ByteArray>(Channel.UNLIMITED)
            return UnbufferedStream(StreamReadHalf(clientToServer), serverToClient) to FixtureClient(serverToClient, clientToServer)
        }
    }
}

/** hyper `unbuffered_stream.rs`. */
class UnbufferedStreamTest {
    @Test
    fun bodyTest() = hyperTest {
        val (server, client) = UnbufferedStream.newPair()
        runFixture(server, client, FixtureConfig(chunkTimeoutMillis = WRITE_DELAY_MILLIS * 2))
    }
}

/**
 * hyper `ReadyOnPollStream`: a write waits for the previous write's delay, then is sent at once and starts a new
 * [WRITE_DELAY_MILLIS] delay; every odd flush (until all 16 chunks are out) waits for that delay, simulating a flush
 * that only completes on a second poll.
 */
private class ReadyOnPollStream(private val readHalf: StreamReadHalf, private val writeTx: Channel<ByteArray>) : IoStream {
    private var pendingWriteUntil = 0L
    private var flushCount = 0

    override suspend fun read(dst: Buffer): Int = readHalf.read(dst)

    override suspend fun write(src: Buffer): Int {
        if (pendingWriteUntil != 0L) delay(pendingWriteUntil - nowMillis())
        pendingWriteUntil = nowMillis() + WRITE_DELAY_MILLIS
        val buf = src.readAll()
        writeTx.send(buf)
        return buf.size
    }

    override suspend fun flush() {
        flushCount++
        if (pendingWriteUntil == 0L) return
        // We require two flushes to complete each chunk, simulating a success at the end of the old poll loop. After
        // all chunks are written, we always succeed on flush to allow for finish.
        val totalChunks = 16
        if (flushCount % 2 != 0 && flushCount < totalChunks * 2) delay(pendingWriteUntil - nowMillis())
        pendingWriteUntil = 0
    }

    override suspend fun shutdownOutput() {}
    override fun close() { writeTx.close() }

    companion object {
        /** hyper `ReadyOnPollStream::new_pair`. */
        fun newPair(): Pair<ReadyOnPollStream, FixtureClient> {
            val clientToServer = Channel<ByteArray>(Channel.UNLIMITED)
            val serverToClient = Channel<ByteArray>(Channel.UNLIMITED)
            return ReadyOnPollStream(StreamReadHalf(clientToServer), serverToClient) to FixtureClient(serverToClient, clientToServer)
        }
    }
}

/** hyper `ready_on_poll_stream.rs`. */
class ReadyOnPollStreamTest {
    @Test
    fun bodyTest() = hyperTest {
        val (server, client) = ReadyOnPollStream.newPair()
        runFixture(server, client, FixtureConfig(chunkTimeoutMillis = WRITE_DELAY_MILLIS * 2))
    }
}
