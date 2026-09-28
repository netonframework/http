package neton.http.h2

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import neton.http.Body
import neton.http.EmptyBody
import neton.http.Frame
import neton.http.FullBody
import neton.http.HttpError
import neton.http.Method
import neton.http.OnUpgrade
import neton.http.Request
import neton.http.Response
import neton.http.SizeHint
import neton.http.StatusCode
import neton.http.h1.ChannelBody
import neton.http.h1.HttpService
import neton.http.h1.bytesOf
import neton.http.h1.collect
import neton.http.h1.concat
import neton.http.h1.connectLocal
import neton.http.h1.headerMapOf
import neton.http.h1.hyperTest
import neton.http.h1.listenLocal
import neton.http.h1.readOnce
import neton.http.h1.readToEnd
import neton.http.h1.s
import neton.http.h1.writeAll
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.http.upgradeOn
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.http.testStreamPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * hyper 1.11.1 `tests/client.rs` (module `conn`), the HTTP/2 tests, test for test (names are the Rust names in
 * camelCase). hyper's client and server are [Http2ClientConfig] / [Http2ServerConfig]; the raw h2 servers of the
 * reference are the h2 server of this package (`neton.http.h2.server`), whose connection is driven by `run()` (the
 * reference's `accept` / `poll_closed`). tokio's `duplex(1024)` is a [neton.http.testStreamPair] of 1,024 bytes (loopback TCP with NETON_HTTP_TEST_TRANSPORT=tcp).
 *
 * - Spawned tasks whose panics the reference would never see are awaited where their outcome is settled by the end
 *   of the test (noted per test); handles the reference drops at the end of a scope are closed there.
 * - The keep-alive tests are scaled down four times (hyper's 1 s interval and timeout are [KA] here).
 * - `http1_conn_coerces_http2_request` is an HTTP/1 connection test, ported with the HTTP/1 tests
 *   (`neton.http.h1.HyperClientTest.http1ConnCoercesHttp2Request`).
 */
class HyperH2ClientTest {

    /** `setup_duplex_test_server`: the client's and the server's ends. */
    private suspend fun duplex(): Pair<IoStream, IoStream> = testStreamPair(1024)

    /** `drain_til_eof`. */
    private suspend fun drainTilEof(sock: IoStream) {
        while (sock.readOnce().isNotEmpty()) {
        }
    }

    /** A request body that stays open (`StreamBody` over an `mpsc::channel(0)` whose sender is kept). */
    private fun openBody(): Pair<Channel<Result<Frame>>, Body> {
        val tx = Channel<Result<Frame>>(Channel.RENDEZVOUS)
        return tx to ChannelBody(tx)
    }

    /** `let _ = body.flow_control().release_capacity(n)`. */
    private fun releaseCapacity(body: RecvStream, n: Int) {
        try {
            body.flowControl().releaseCapacity(n)
        } catch (_: H2Error) {
        }
    }

    /** The server connection task of the reference, checked at the end when it has ended (`.expect(...)`). */
    private suspend fun checkIfEnded(task: Deferred<*>) {
        if (task.isCompleted) task.await()
    }

    /** Both server tasks and the client connection task are awaited (stricter: the reference does not observe them). */
    @Test
    fun http2DetectConnEof() = hyperTest {
        val (listener, port) = listenLocal()

        val shdn = CompletableDeferred<Unit>()
        val connTasks = ArrayList<Deferred<Unit>>()
        val acceptLoop = spawn {
            val service = HttpService { Response.builder().body(EmptyBody as Body) }
            while (true) {
                val stream = listener.accept().closeAtEnd()
                val conn = Http2ServerConfig().serveConnection(stream, service)
                connTasks += spawn {
                    val serving = async { conn.serve() }
                    select<Unit> {
                        serving.onAwait { }
                        shdn.onAwait {
                            conn.gracefulShutdown()
                            serving.await()
                        }
                    }
                }
            }
        }

        val (client, conn) = Http2ClientConfig().handshake(connectLocal(port))
        val clientConn = spawn { conn.run() }

        // Sanity check that client is ready
        client.ready()

        val req = Request.get("http://127.0.0.1:$port/").body(EmptyBody as Body)

        client.sendRequest(req)

        // Sanity check that client is STILL ready
        client.ready()

        // Trigger the server shutdown...
        shdn.complete(Unit)
        acceptLoop.cancel()

        // Allow time for graceful shutdown roundtrips...
        delay(100)

        // After graceful shutdown roundtrips, the client should be closed...
        assertFailsWith<HttpError>("client should be closed") { client.ready() }

        clientConn.await()
        for (t in connTasks) t.await()
    }

    /** Regression test for failure to fully close connections when using HTTP2 CONNECT: two requests, then dropped. */
    @Test
    fun http2ConnectDetectClose() = hyperTest {
        val (clientIo, serverIo) = duplex()
        val rxx = CompletableDeferred<Unit>()

        val service = HttpService { req ->
            spawn {
                val io = upgradeOn(req.extensions)
                io.writeAll("hello\n")
                io.close()
            }
            Response.builder().body(EmptyBody as Body)
        }

        spawn {
            runCatching { Http2ServerConfig().serveConnection(serverIo, service).serve() }
            rxx.complete(Unit)
        }

        val (client, conn) = Http2ClientConfig().handshake(clientIo)
        spawn { conn.run() }

        // Sanity check that client is ready
        client.ready()
        val requests = 2
        val clients = mutableListOf(client.clone(), client)
        val tx = CompletableDeferred<Unit>()
        val tx2 = CompletableDeferred<Unit>()
        val rxs = mutableListOf(tx, tx2)
        repeat(requests) {
            val c = clients.removeLast()
            val rx = rxs.removeLast()
            val req = Request.connect("[3fff::1]:8080").body(EmptyBody as Body)

            val resp = c.sendRequest(req)
            assertEquals(200, resp.status.asU16())
            val upgrade = upgradeOn(resp.extensions)
            spawn {
                runCatching { rx.await() }
                upgrade.close()
            }
            c.close()
        }
        // drop(tx); drop(tx2): the receivers see their senders gone.
        tx.cancel()
        tx2.cancel()
        assertNotNull(withTimeoutOrNull(1000) { rxx.await() }, "drop with 1s")
    }

    @Test
    fun http2KeepAliveDetectsUnresponsiveServer() = hyperTest {
        val (clientIo, serverIo) = duplex()

        // spawn a server that reads but doesn't write
        spawn { drainTilEof(serverIo) }

        val (_, conn) = Http2ClientConfig()
            .keepAliveInterval(KA)
            .keepAliveTimeout(KA)
            // enable while idle since we aren't sending requests
            .keepAliveWhileIdle(true)
            .handshake(clientIo)

        assertFailsWith<HttpError>("conn should time out") { conn.run() }
    }

    /**
     * Not setting `keep_alive_while_idle(true)` uses the default behavior, which will NOT detect the server is
     * unresponsive while no streams are active.
     */
    @Test
    fun http2KeepAliveNotWhileIdle() = hyperTest {
        val (clientIo, serverIo) = duplex()

        // spawn a server that reads but doesn't write
        spawn { drainTilEof(serverIo) }

        val (client, conn) = Http2ClientConfig()
            .keepAliveInterval(KA)
            .keepAliveTimeout(KA)
            .handshake(clientIo)

        val connTask = spawn { conn.run() }

        // sleep longer than keepalive would trigger
        delay(KA * 4)

        client.ready()
        checkIfEnded(connTask)
    }

    /** The client connection task is awaited (stricter: the reference does not observe it). */
    @Test
    fun http2KeepAliveClosesOpenStreams() = hyperTest {
        val (clientIo, serverIo) = duplex()

        // spawn a server that reads but doesn't write
        spawn { drainTilEof(serverIo) }

        val (client, conn) = Http2ClientConfig()
            .keepAliveInterval(KA)
            .keepAliveTimeout(KA)
            .handshake(clientIo)

        val connTask = spawn {
            val err = assertFailsWith<HttpError>("client conn should timeout") { conn.run() }
            assertTrue(err.isTimeout(), "$err")
        }

        val req = Request(EmptyBody as Body)
        val err = assertFailsWith<HttpError>("request should timeout") { client.sendRequest(req) }
        assertTrue(err.isTimeout(), "$err")

        val err2 = assertFailsWith<HttpError>("client should be closed") { client.ready() }
        assertTrue(err2.isClosed(), "poll_ready error should be closed: $err2")
        connTask.await()
    }

    /** A responsive server works just when client keep alive is enabled. */
    @Test
    fun http2KeepAliveWithResponsiveServer() = hyperTest {
        val (clientIo, serverIo) = duplex()

        // Spawn an HTTP2 server that reads the whole body and responds
        val server = spawn {
            Http2ServerConfig().serveConnection(
                serverIo,
                HttpService { req ->
                    spawn { req.body.concat() }
                    Response.builder().body(EmptyBody as Body)
                },
            ).serve()
        }

        val (client, conn) = Http2ClientConfig()
            .keepAliveInterval(KA)
            .keepAliveTimeout(KA)
            .handshake(clientIo)

        val connTask = spawn { conn.run() }

        // Use a channel to keep request stream open
        val (tx, body) = openBody()
        val req = Request(body)

        client.sendRequest(req)

        // sleep longer than keepalive would trigger
        delay(KA * 4)

        client.ready()
        checkIfEnded(connTask)
        checkIfEnded(server)
        tx.close()
    }

    /** Early-response from server works correctly (request body wasn't fully consumed): hyper issue #2872. */
    @Test
    fun http2RespondsBeforeConsumingRequestBodyNoTrailers() = hyperTest {
        val (listener, port) = listenLocal()

        // Spawn an HTTP2 server that responds before reading the whole request body.
        // It's normal case to decline the request due to headers or size of the body.
        val server = spawn {
            val sock = listener.accept().closeAtEnd()
            Http2ServerConfig().serveConnection(sock, HttpService {
                Response.builder().body(FullBody(bytesOf("No bread for you!")) as Body)
            }).serve()
        }

        val (client, conn) = Http2ClientConfig().handshake(connectLocal(port))
        val connTask = spawn { conn.run() }

        // Use a channel to keep request stream open
        val (tx, body) = openBody()
        val req = Request.post("/a").body(body)
        val resp = client.sendRequest(req)
        assertTrue(resp.status.isSuccess())

        val (data, trailers) = resp.body.collect()
        assertEquals("No bread for you!", s(data))
        assertNull(trailers)
        checkIfEnded(connTask)
        checkIfEnded(server)
        tx.close()
    }

    /** An `HttpBody` whose `is_end_stream()` is true after sending trailers. */
    private class TrailersBody(private var trailers: HeaderMap<HeaderValue>?) : Body {
        override suspend fun nextFrame(): Frame? = trailers?.let {
            trailers = null
            Frame.Trailers(it)
        }

        override val isEndStream: Boolean get() = trailers == null
        override val sizeHint: SizeHint get() = SizeHint.withExact(0)
        override val exactLength: Long get() = 0
    }

    /** Early-response from server works correctly (request body wasn't fully consumed): hyper issue #2872. */
    @Test
    fun http2RespondsBeforeConsumingRequestBodyWithTrailers() = hyperTest {
        val (listener, port) = listenLocal()

        // Spawn an HTTP2 server that responds before reading the whole request body.
        // It's normal case to decline the request due to headers or size of the body.
        val server = spawn {
            val sock = listener.accept().closeAtEnd()
            Http2ServerConfig().serveConnection(sock, HttpService {
                val trailers = headerMapOf("grpc" to "0")
                Response(TrailersBody(trailers) as Body)
            }).serve()
        }

        val (client, conn) = Http2ClientConfig().handshake(connectLocal(port))
        val connTask = spawn { conn.run() }

        // Use a channel to keep request stream open
        val (tx, body) = openBody()
        val req = Request.post("/a").body(body)
        val resp = client.sendRequest(req)
        assertTrue(resp.status.isSuccess())

        val (data, trailers) = resp.body.collect()

        // No body:
        assertEquals(0, data.size)

        // Have our `grpc` trailer:
        assertNotNull(trailers, "response has trailers")
        assertEquals(1, trailers.len())
        assertEquals("0", trailers["grpc"]?.toStr())
        checkIfEnded(connTask)
        checkIfEnded(server)
        tx.close()
    }

    /**
     * An HTTP2 server that asks for bread and responds with baguette; its task is awaited (stricter: the reference
     * does not observe it; see the comment inside for its last assertion).
     */
    @Test
    fun h2Connect() = hyperTest {
        val (clientIo, serverIo) = duplex()

        val server = spawn {
            val h2 = neton.http.h2.server.handshake(serverIo)
            spawn { h2.run() }

            val (req, respond) = h2.accept()!!
            assertEquals(Method.CONNECT, req.method)

            val body = req.body

            val sendStream = respond.sendResponse(Response(Unit), false)

            sendStream.sendData(bytesOf("Bread?"), true)

            val bytes = body.data()!!
            assertEquals("Baguette!", s(bytes.toByteArray()))
            releaseCapacity(body, bytes.size)

            // The reference asserts `body.data().await.is_none()` here, in a detached task it never observes. hyper's
            // tunnel ends its stream with an empty END_STREAM DATA frame (`UpgradedSendStreamTask`), which h2 0.4.19
            // delivers as an empty chunk (`Recv::recv_data` only drops empty frames without END_STREAM), so that
            // assert would fail in the reference. This port follows the implementations: the empty last chunk, then
            // the end.
            assertEquals(0, body.data()?.size)
            assertNull(body.data())
        }

        val (client, conn) = Http2ClientConfig().handshake(clientIo)
        val connTask = spawn { conn.run() }

        val req = Request.connect("localhost").body(EmptyBody as Body)
        val res = client.sendRequest(req)
        assertEquals(StatusCode.OK, res.status)

        val upgraded = upgradeOn(res.extensions)

        assertEquals("Bread?", s(upgraded.readToEnd()))

        upgraded.writeAll("Baguette!")

        upgraded.shutdownOutput()
        server.await()
        checkIfEnded(connTask)
    }

    @Test
    fun h2ConnectRejected() = hyperTest {
        val (clientIo, serverIo) = duplex()
        val done = CompletableDeferred<Unit>()

        val server = spawn {
            val h2 = neton.http.h2.server.handshake(serverIo)
            spawn { h2.run() }

            val (req, respond) = h2.accept()!!
            assertEquals(Method.CONNECT, req.method)

            val res = Response.builder().status(400).body(Unit)
            val sendStream = respond.sendResponse(res, false)
            sendStream.sendData(bytesOf("No bread for you!"), true)
            done.await()
        }

        val (client, conn) = Http2ClientConfig().handshake(clientIo)
        val connTask = spawn { conn.run() }

        val req = Request.connect("localhost").body(EmptyBody as Body)
        val res = client.sendRequest(req)
        assertEquals(StatusCode.BAD_REQUEST, res.status)
        assertNull(res.extensions.get(OnUpgrade::class))

        val body = s(res.body.concat())
        assertEquals("No bread for you!", body)

        done.complete(Unit)
        server.await()
        checkIfEnded(connTask)
    }

    /** hyper issue #4040. */
    @Test
    fun h2PipeTaskCancelledOnResponseFutureDrop() = hyperTest {
        val (clientIo, serverIo) = duplex()
        val rstTx = CompletableDeferred<Boolean>()

        spawn {
            val h2 = neton.http.h2.server.Builder().initialWindowSize(0).handshake(serverIo)
            spawn { runCatching { h2.run() } }
            val (req, _) = h2.accept()!!

            val body = req.body
            val frame = withTimeoutOrNull(2000) { runCatching { body.data() } }
            val gotRst = frame != null && (frame.isFailure || frame.getOrNull() == null)
            rstTx.complete(gotRst)
        }

        val (client, conn) = Http2ClientConfig().handshake(clientIo)
        spawn { runCatching { conn.run() } }

        val req = Request.post("http://localhost/").body(FullBody(Bytes.copyOf(ByteArray(50) { 'x'.code.toByte() })) as Body)
        val res = withTimeoutOrNull(5) { client.sendRequest(req) }
        assertNull(res, "should timeout waiting for response")

        val gotRst = rstTx.await()
        assertTrue(gotRst, "server should receive RST_STREAM")
    }

    /**
     * hyper issue #4003: an idle `PipeToSendStream` must not reserve any connection-level flow control capacity
     * speculatively. If it does, a first stream that has filled the connection window pins the remaining byte(s), and
     * no second stream can make progress when talking to a peer that only emits WINDOW_UPDATE after its receive window
     * is fully exhausted.
     */
    @Test
    fun h2IdleStreamDoesNotPinConnectionWindow() = hyperTest {
        // The HTTP/2 spec fixes the initial connection-level window at 65535 (RFC 9113 section 6.9.2), and it can only
        // be increased via WINDOW_UPDATE. Stream A therefore sends 65534 bytes to leave exactly one byte of connection
        // window for stream B.
        val streamALen = 65534

        val (clientIo, serverIo) = duplex()
        val streamAFull = CompletableDeferred<Unit>()
        val streamBGot = CompletableDeferred<Int>()

        // Raw h2 server that never calls `release_capacity`, so no connection-level WINDOW_UPDATE is ever sent —
        // mimicking peers that only emit WINDOW_UPDATE after their receive window is fully exhausted. The server
        // accepts streams in a loop; each stream is dispatched to a spawned handler that reads the body without ever
        // releasing capacity.
        //
        // The `streamADone` signal keeps stream A's server-side request alive until the test is done. Dropping the
        // recv side of stream A would let h2 auto-release its in-flight recv capacity and emit a WINDOW_UPDATE, which
        // would hide the bug.
        val streamADone = CompletableDeferred<Unit>()
        spawn {
            val h2 = neton.http.h2.server.handshake(serverIo)
            spawn { h2.run() }
            var seen = 0
            while (true) {
                val (req, respond) = h2.accept() ?: break
                seen += 1
                val which = seen
                spawn {
                    val body = req.body
                    try {
                        if (which == 1) {
                            // Stream A: drain the burst of body data without ever releasing recv capacity, then park
                            // on the done signal to hold on to the recv stream.
                            var received = 0
                            while (received < streamALen) {
                                val frame = runCatching { body.data() }.getOrNull() ?: return@spawn
                                received += frame.size
                                // Intentionally do NOT call release_capacity.
                            }
                            streamAFull.complete(Unit)
                            // Keep the recv stream alive so that dropping it cannot auto-release connection-level recv
                            // capacity and emit a WINDOW_UPDATE mid-test.
                            runCatching { streamADone.await() }
                        } else {
                            // Stream B: record the first data frame and respond.
                            var received = 0
                            runCatching { body.data() }.getOrNull()?.let { received += it.size }
                            streamBGot.complete(received)
                            val send = respond.sendResponse(Response(Unit), false)
                            runCatching { send.sendData(bytesOf("ok"), true) }
                            send.close()
                        }
                    } finally {
                        body.close()
                        respond.close()
                    }
                }
            }
        }

        val (client, conn) = Http2ClientConfig().handshake(clientIo)
        spawn { runCatching { conn.run() } }

        // Request A: streaming body that sends STREAM_A_LEN bytes and then stays open, waiting for more data. This
        // fills the advertised connection-level window down to one byte remaining.
        val txA = Channel<Result<Frame>>(4)
        val reqA = Request.post("http://localhost/a").body(ChannelBody(txA) as Body)
        val clientA = client.clone()
        val aHandle = spawn {
            try {
                clientA.sendRequest(reqA)
            } finally {
                clientA.close()
            }
        }

        // Push stream A's body in 16 KiB chunks to match the default h2 `SETTINGS_MAX_FRAME_SIZE`.
        var remaining = streamALen
        while (remaining > 0) {
            val take = minOf(remaining, 16_384)
            txA.send(Result.success(Frame.Data(Bytes.copyOf(ByteArray(take) { 'A'.code.toByte() }))))
            remaining -= take
        }

        // Wait for the server to confirm it received the full body on stream A, which means the connection window is
        // now down to its last byte.
        assertNotNull(withTimeoutOrNull(5000) { streamAFull.await() }, "server should receive full stream A body in time")

        // Give the client's `PipeToSendStream` for stream A a moment to park itself waiting for more body frames,
        // which (with the bug) would speculatively reserve the last byte of connection-level capacity.
        repeat(16) { yield() }

        // Request B: one byte of body. With the bug in `PipeToSendStream`, stream A pins the last byte of connection
        // window via a speculative reserve, so stream B can never ship its data frame.
        val reqB = Request.post("http://localhost/b").body(FullBody(bytesOf("b")) as Body)
        // hyper's `send_request` hands the request to the connection at once; its future is polled later.
        val bFut = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.sendRequest(reqB) } }

        val receivedB = withTimeoutOrNull(5000) { streamBGot.await() }
        assertNotNull(receivedB, "stream B must reach the server even while stream A is idle")
        assertEquals(1, receivedB, "stream B should deliver its single body byte")

        // Drive request B to completion so we don't leak the future.
        withTimeoutOrNull(5000) { bFut.await() }

        // Close stream A cleanly: first release the server-side handler so it drops the recv stream, then drop the
        // body sender.
        streamADone.complete(Unit)
        txA.close()
        withTimeoutOrNull(5000) { runCatching { aHandle.await() } }
    }

    private companion object {
        /** hyper's keep-alive interval and timeout (1 s), scaled down. */
        val KA: Duration = 250.milliseconds
    }
}
