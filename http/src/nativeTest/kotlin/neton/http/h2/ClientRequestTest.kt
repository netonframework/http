// Ported from h2 0.4.19 tests/h2-tests/tests/client_request.rs (45 tests).
//
// The reference's `MockH2` trait (`mock_io::Builder::handshake`) and its SETTINGS / SETTINGS_ACK constants are
// `MockIoBuilder.handshake()` and `Frames.SETTINGS` / `Frames.SETTINGS_ACK` in the Kotlin test support (Mock.kt).
//
// Runtime notes: `h2.drive(fut)` / `join(h2.await, ..)` run the connection in a coroutine ([drive]) for the whole
// test; `tokio::spawn` becomes [spawn], which (like a spawned tokio task) does not fail the test when its body fails.
// Rust drops are mirrored with close(): the mock handle `srv` is closed where its `async move` block ends.
package neton.http.h2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import neton.http.Method
import neton.http.Request
import neton.http.StatusCode
import neton.http.Version
import neton.http.h2.client.Builder
import neton.http.h2.client.Connection
import neton.http.h2.client.ResponseFuture
import neton.http.h2.client.handshake as clientHandshake
import neton.http.h2.frame.DEFAULT_INITIAL_WINDOW_SIZE
import neton.http.h2.frame.DEFAULT_MAX_FRAME_SIZE
import neton.http.h2.frame.GoAway
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Pseudo
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import neton.http.h2.proto.IoErrorKind
import neton.http.uri.Authority
import neton.http.uri.PathAndQuery
import neton.http.uri.Scheme
import neton.http.uri.Uri
import neton.http.uri.UriParts
import neton.io.bytes.Bytes
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs the connection (`h2.drive(..)`, `h2.await`); the result is kept for the tests that check it. */
private fun CoroutineScope.drive(h2: Connection): Deferred<Result<Unit>> = async { runCatching { h2.run() } }

/** `tokio::spawn`: a panic in a spawned task does not fail the reference test, so a failure here is ignored. */
private fun CoroutineScope.spawn(block: suspend CoroutineScope.() -> Unit): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Throwable) {
        // ignored, as the reference ignores a spawned task's panic
    }
}

class ClientRequestTest {
    @Test
    fun handshake() = h2Test {
        val mock = MockIoBuilder()
            .handshake()
            .write(Frames.SETTINGS_ACK)
            .build()

        val (_, h2) = clientHandshake(mock)

        // At this point, the connection should be closed
        h2.run()
        mock.assertDone()
    }

    @Test
    fun clientOtherThread() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        spawn {
            try {
                val request = Request.builder()
                    .uri("https://http2.akamai.com/")
                    .body(Unit)
                val (fut, stream) = client.sendRequest(request, true)
                try {
                    val res = fut.await() // expect("request")
                    // The task ends: its response is dropped.
                    res.body.close()
                } finally {
                    stream.close()
                }
            } finally {
                // the client was moved into the task
                client.close()
            }
        }
        h2.run() // expect("h2")
        s.join()
    }

    @Test
    fun recvInvalidServerStreamId() = h2Test {
        val mock = MockIoBuilder()
            .handshake()
            // Write GET /
            .write(
                raw(
                    0, 0, 0x10, 1, 5, 0, 0, 0, 1, 0x82, 0x87, 0x41, 0x8B, 0x9D, 0x29, 0xAC, 0x4B, 0x8F,
                    0xA8, 0xE9, 0x19, 0x97, 0x21, 0xE9, 0x84,
                ),
            )
            .write(Frames.SETTINGS_ACK)
            // Read response
            .read(raw(0, 0, 1, 1, 5, 0, 0, 0, 2, 137))
            // Write GO_AWAY
            .write(raw(0, 0, 8, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
            .build()

        val (client, h2) = clientHandshake(mock)

        // Send the request
        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (response, stream) = client.sendRequest(request, true)
        stream.close()

        // The connection errors
        assertTrue(runCatching { h2.run() }.isFailure)

        // The stream errors
        assertTrue(runCatching { response.await() }.isFailure)
        mock.assertDone()
    }

    @Test
    fun requestStreamIdOverflows() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(Int.MAX_VALUE) // u32::MAX >> 1
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(Int.MAX_VALUE).response(200).eos())
            idleMs(10)
            srv.close()
        }

        val (client, h2) = Builder()
            .initialStreamId(Int.MAX_VALUE)
            .handshake(io)
        val conn = drive(h2)
        val request = Request.builder()
            .method(Method.GET)
            .uri("https://example.com/")
            .body(Unit)

        // first request is allowed
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        val x = response.await()

        val request2 = Request.builder()
            .method(Method.GET)
            .uri("https://example.com/")
            .body(Unit)
        // second cannot use the next stream id, it's over
        val pollErr = assertFailsWith<H2Error> { client.ready() }
        assertEquals("user error: stream ID overflowed", pollErr.message)

        val err = assertFailsWith<H2Error> { client.sendRequest(request2, true) }
        assertEquals("user error: stream ID overflowed", err.message)

        conn.await().getOrThrow()
        x.body.close()
        s.join()
    }

    @Test
    fun clientBuilderMaxConcurrentStreams() = h2Test {
        val (io, srv) = mockNew()

        val settings = Settings()
        settings.maxConcurrentStreams = 1L

        val s = launch {
            val rcvdSettings = srv.assertClientHandshake()
            assertFrameEq(settings, rcvdSettings)

            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val builder = Builder()
        builder.maxConcurrentStreams(1)

        val (client, h2) = builder.handshake(io)
        drive(h2)
        val request = Request.builder()
            .method(Method.GET)
            .uri("https://example.com/")
            .body(Unit)
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        response.await().body.close()
        s.join()
    }

    @Test
    fun requestOverMaxConcurrentStreamsErrors() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake(
                Frames.settings()
                    // super tiny server
                    .maxConcurrentStreams(1),
            )
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("POST", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.recvFrame(Frames.headers(3).request("POST", "https://example.com/"))
            srv.sendFrame(Frames.headers(3).response(200))
            srv.recvFrame(Frames.data(3, "hello").eos())
            srv.sendFrame(Frames.data(3, "").eos())
            srv.recvFrame(Frames.headers(5).request("POST", "https://example.com/"))
            srv.sendFrame(Frames.headers(5).response(200))
            srv.recvFrame(Frames.data(5, "hello").eos())
            srv.sendFrame(Frames.data(5, "").eos())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        val conn = drive(h2)
        // we send a simple req here just to drive the connection so we can
        // receive the server settings.
        val request = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)
        // first request is allowed
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        response.await().body.close()

        val request1 = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        // first request is allowed
        val (resp1, stream1) = client.sendRequest(request1, false)
        // as long as we let the connection internals tick
        client.ready()

        val request2 = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        // second request is put into pending_open
        val (resp2, stream2) = client.sendRequest(request2, false)

        val request3 = Request.builder()
            .method(Method.GET)
            .uri("https://example.com/")
            .body(Unit)

        // third stream is over max concurrent
        // ⚖️ adapted: `poll_ready` with a noop waker is Pending -> ready() started undispatched is still suspended.
        val readyJob = launch(start = CoroutineStart.UNDISPATCHED) { client.ready() }
        assertTrue(readyJob.isActive, "poll_ready should be pending")
        readyJob.cancel()

        val err = assertFailsWith<H2Error> { client.sendRequest(request3, true) }
        assertEquals("user error: rejected", err.message)

        stream1.sendData(bytes("hello"), true) // expect("req send_data")

        resp1.await().body.close() // expect("req")
        stream2.sendData(bytes("hello"), true) // expect("req2 send_data")
        // stream2 is dropped at the end of the driven block
        stream2.close()

        val r2 = resp2.await()
        conn.await().getOrThrow()
        r2.body.close()
        stream1.close()
        s.join()
    }

    @Test
    fun recvDecrementMaxConcurrentStreamsWhenRequestsQueued() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("POST", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())

            srv.pingPong(ByteArray(8))

            // limit this server later in life
            srv.sendFrame(Frames.settings().maxConcurrentStreams(1))
            srv.recvFrame(Frames.settingsAck())
            srv.recvFrame(
                Frames.headers(3)
                    .request("POST", "https://example.com/")
                    .eos(),
            )
            srv.pingPong(ByteArray(8) { 1 })
            srv.sendFrame(Frames.headers(3).response(200).eos())

            srv.recvFrame(
                Frames.headers(5)
                    .request("POST", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(5).response(200).eos())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        val conn = drive(h2)
        // we send a simple req here just to drive the connection so we can
        // receive the server settings.
        val request = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)
        // first request is allowed
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        response.await().body.close()

        // ⚖️ adapted: in the reference the connection is not polled between `drive` calls, so the two requests below
        // wait in pending_open until the lowered limit is applied. Here the connection runs by itself and would open
        // them at once: wait until the server's new SETTINGS (max 1 stream) are applied, and let the first request be
        // opened (`ready`) before queueing the second one behind the lowered limit.
        while (client.currentMaxSendStreams() != 1) idleMs(1)

        val request1 = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        // first request is allowed
        val (resp1, stream1) = client.sendRequest(request1, true)
        stream1.close()
        client.ready()

        val request2 = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        // second request is put into pending_open
        val (resp2, stream2) = client.sendRequest(request2, true)
        stream2.close()

        resp1.await().body.close() // expect("req")

        val r2 = resp2.await()
        conn.await().getOrThrow()
        r2.body.close()
        s.join()
    }

    @Test
    fun sendRequestPollReadyWhenConnectionError() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake(
                Frames.settings()
                    // super tiny server
                    .maxConcurrentStreams(1),
            )
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("POST", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.recvFrame(
                Frames.headers(3)
                    .request("POST", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(8).response(200).eos())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        val conn = drive(h2)
        // we send a simple req here just to drive the connection so we can
        // receive the server settings.
        val request = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        // first request is allowed
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        response.await().body.close()

        val request1 = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        // first request is allowed
        val (resp1, stream1) = client.sendRequest(request1, true)
        stream1.close()
        // as long as we let the connection internals tick
        client.ready()

        val request2 = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        // second request is put into pending_open
        val (resp2, stream2) = client.sendRequest(request2, true)
        stream2.close()

        // third stream is over max concurrent
        val untilReady = async { runCatching { client.ready() } }

        // a FuturesUnordered is used on purpose!
        //
        // We don't want a join, since any of the other futures notifying
        // will make the until_ready future polled again, but we are
        // specifically testing that until_ready gets notified on its own.
        // (Here each future is its own coroutine, woken only by its own notification.)
        val r1 = async { runCatching { resp1.await() } }
        val r2 = async { runCatching { resp2.await() } }

        assertTrue(untilReady.await().isFailure, "client poll_ready")
        assertTrue(conn.await().isFailure, "client conn")
        assertTrue(r1.await().isFailure, "req1")
        assertTrue(r2.await().isFailure, "req2")
        client.close()
        s.join()
    }

    @Test
    fun sendResetNotifiesRecvStream() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            srv.sendFrame(Frames.headers(1).response(200))
            srv.recvFrame(Frames.reset(1).refused())
            srv.recvFrame(Frames.goAway(0))
            srv.recvEof()
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        val conn = drive(h2)
        val request = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        // first request is allowed
        val (resp1, tx) = client.sendRequest(request, false)
        val res = resp1.await()

        // a FuturesUnordered is used on purpose!
        //
        // We don't want a join, since any of the other futures notifying
        // will make the rx future polled again, but we are
        // specifically testing that rx gets notified on its own.
        val rx = launch {
            val body = res.body
            val err = assertFailsWith<H2Error>("RecvBody") { body.data() }
            assertEquals(
                "stream error sent by user: refused stream before processing any application logic",
                err.message,
            )
            body.close()
        }
        val txJob = launch {
            tx.sendReset(Reason.REFUSED_STREAM)
            tx.close()
        }

        rx.join()
        txJob.join()
        client.close() // now let client gracefully goaway
        conn.await().getOrThrow() // expect("client")
        s.join()
    }

    @Test
    fun http11RequestWithoutSchemeOrAuthority() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "/").scheme("http").eos())
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        drive(h2)

        // HTTP_11 request with just :path is allowed
        val request = Request.builder()
            .method(Method.GET)
            .uri("/")
            .body(Unit)

        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        response.await().body.close()
        s.join()
    }

    @Test
    fun http2RequestWithoutSchemeOrAuthority() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)

        // HTTP_2 with only a :path is illegal, so this request should
        // be rejected as a user error.
        val request = Request.builder()
            .version(Version.HTTP_2)
            .method(Method.GET)
            .uri("/")
            .body(Unit)

        assertFailsWith<H2Error>("should be UserError") { client.sendRequest(request, true) }
        h2.run() // expect("h2")
        client.close()
        s.join()
    }

    @Test
    fun http2ConnectRequestOmitSchemeAndPathFields() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .pseudo(
                        Pseudo(
                            method = Method.CONNECT,
                            authority = "tunnel.example.com:8443",
                        ),
                    )
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        drive(h2)

        // In HTTP_2 CONNECT request the ":scheme" and ":path" pseudo-header fields MUST be omitted.
        val request = Request.builder()
            .version(Version.HTTP_2)
            .method(Method.CONNECT)
            .uri("https://tunnel.example.com:8443/")
            .body(Unit)

        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        response.await().body.close()
        s.join()
    }

    // ignored in the reference (empty body there too)
    @Ignore
    @Test
    fun requestWithH1Version() {
    }

    @Test
    fun requestWithConnectionHeaders() = h2Test {
        val (io, srv) = mockNew()

        // can't assert full handshake, since client never sends a request, and
        // thus never bothers to ack the settings...
        val s = launch {
            srv.readPreface()
            srv.recvFrame(Frames.settings())
            // goaway is required to make sure the connection closes because
            // of no active streams
            srv.recvFrame(Frames.goAway(0))
            srv.close()
        }

        val headers = listOf(
            "connection" to "foo",
            "keep-alive" to "5",
            "proxy-connection" to "bar",
            "transfer-encoding" to "chunked",
            "upgrade" to "HTTP/2",
            "te" to "boom",
        )

        val (client, conn) = clientHandshake(io)

        for ((name, value) in headers) {
            val req = Request.builder()
                .uri("https://http2.akamai.com/")
                .header(name, value)
                .body(Unit)
            val err = assertFailsWith<H2Error>(name) { client.sendRequest(req, true) }
            assertEquals("user error: malformed headers", err.message)
        }
        client.close()
        conn.run()
        s.join()
    }

    @Test
    fun connectionCloseNotifiesResponseFuture() = h2Test {
        val (io, srv) = mockNew()
        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            // don't send any response, just close
            srv.close()
        }

        val (client, conn) = clientHandshake(io)

        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val driver = drive(conn)
        // req
        val (fut, stream) = client.sendRequest(request, true) // expect("send_request1")
        val err = assertFailsWith<H2Error>("response") { fut.await() }
        stream.close()
        assertEquals("stream closed because of a broken pipe", err.message)
        // the client was moved into the req block and is dropped at its end
        client.close()
        driver.await().getOrThrow() // expect("conn")
        s.join()
    }

    @Test
    fun connectionCloseNotifiesClientPollReady() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            srv.close()
        }

        val (client, conn) = clientHandshake(io)

        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        drive(conn)
        val (fut, stream) = client.sendRequest(request, true) // expect("send_request1")
        val res = assertFailsWith<H2Error>("response") { fut.await() }
        stream.close()
        assertEquals("stream closed because of a broken pipe", res.message)

        val err = assertFailsWith<H2Error>("poll_ready") { client.ready() }
        assertEquals(
            "connection closed because of a broken pipe",
            err.message,
        )
        client.close()
        s.join()
    }

    @Test
    fun sendingRequestOnClosedConnection() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            // a bad frame!
            srv.sendFrame(Frames.headers(0).response(200).eos())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)

        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        // ⚖️ adapted: `select(h2, req)` with "req first is unreachable" depends on the reference's poll order (both
        // frames are read in one poll of the connection); here both run as coroutines and both results are checked.
        // first request works
        val (fut, stream) = client.sendRequest(request, true) // expect("send_request1")
        val req = launch {
            fut.await().body.close() // expect("response1")
            stream.close()
        }

        // after finish request1, there should be a conn error
        val conn = drive(h2)
        assertTrue(conn.await().isFailure, "h2 error")
        req.join()

        val pollErr = assertFailsWith<H2Error> { client.ready() }
        val msg = "connection error detected: unspecific protocol error detected"
        assertEquals(msg, pollErr.message)

        val request2 = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)
        val sendErr = assertFailsWith<H2Error> { client.sendRequest(request2, true) }
        assertEquals(msg, sendErr.message)
        client.close()
        s.join()
    }

    @Test
    fun recvTooBigHeaders() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Frames.settings().maxHeaderListSize(40))
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            srv.recvFrame(
                Frames.headers(3)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.sendFrame(Frames.headers(3).response(200))
            // no reset for 1, since it's closed anyway
            // but reset for 3, since server hasn't closed stream
            srv.recvFrame(Frames.reset(3).protocolError())
            idleMs(10)
            srv.close()
        }

        val (client, conn) = Builder()
            .maxHeaderListSize(40)
            .handshake(io)

        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (fut1, tx1) = client.sendRequest(request, true) // expect("send_request")

        val request2 = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (fut2, tx2) = client.sendRequest(request2, true) // expect("send_request")

        drive(conn)
        // Spawn tasks to ensure that the error wakes up tasks that are blocked
        // waiting for a response.
        val req1 = launch {
            val err = assertFailsWith<H2Error>("response1") { fut1.await() }
            tx1.close()
            assertEquals(Reason.PROTOCOL_ERROR, err.reason())
        }
        val req2 = launch {
            val err = assertFailsWith<H2Error>("response2") { fut2.await() }
            tx2.close()
            assertEquals(Reason.PROTOCOL_ERROR, err.reason())
        }
        req1.join()
        req2.join()
        client.close()
        s.join()
    }

    @Test
    fun pendingSendRequestGetsResetByPeerProperly() = h2Test {
        val (io, srv) = mockNew()

        val payload = ByteArray(DEFAULT_INITIAL_WINDOW_SIZE * 2)
        val maxFrameSize = DEFAULT_MAX_FRAME_SIZE

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/"))
            // Note that we can only send up to ~4 frames of data by default
            srv.recvFrame(Frames.data(1, payload.copyOfRange(0, maxFrameSize)))
            srv.recvFrame(Frames.data(1, payload.copyOfRange(maxFrameSize, maxFrameSize * 2)))
            srv.recvFrame(Frames.data(1, payload.copyOfRange(maxFrameSize * 2, maxFrameSize * 3)))
            srv.recvFrame(Frames.data(1, payload.copyOfRange(maxFrameSize * 3, maxFrameSize * 4 - 1)))

            idleMs(100)

            srv.sendFrame(Frames.reset(1).refused())
            // Because all active requests are finished, connection should shutdown
            // and send a GO_AWAY frame. If the reset stream is bugged (and doesn't
            // count towards concurrency limit), then connection will not send
            // a GO_AWAY and this test will fail.
            srv.recvFrame(Frames.goAway(0))
            srv.close()
        }

        val (client, conn) = Builder()
            .handshake(io)

        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (response, stream) = client.sendRequest(request, false) // expect("send_request")

        // Send the data
        stream.sendData(Bytes.wrap(payload.copyOf()), true)
        val driver = drive(conn)
        // response
        val err = assertFailsWith<H2Error>("response") { response.await() }
        assertEquals(Reason.REFUSED_STREAM, err.reason())
        client.close()
        stream.close()
        driver.await().getOrThrow() // expect("client")
        s.join()
    }

    @Test
    fun requestWithoutPath() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())

            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "http://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, conn) = clientHandshake(io)
        drive(conn)
        // Note the lack of trailing slash.
        val request = Request.get("http://example.com").body(Unit)

        val (response, stream) = client.sendRequest(request, true)
        stream.close()

        response.await().body.close()
        s.join()
    }

    @Test
    fun requestOptionsWithStar() = h2Test {
        val (io, srv) = mockNew()

        // Note the lack of trailing slash.
        fun starUri(): Uri = Uri.fromParts(
            UriParts(
                scheme = Scheme.HTTP,
                authority = Authority.fromStatic("example.com"),
                pathAndQuery = PathAndQuery.fromStatic("*"),
            ),
        )
        val uri = starUri()

        val uriClone = starUri()
        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            // `frames::headers(1).request("OPTIONS", uri_clone)`: Frames.request takes a string, so the pseudo is
            // built from the Uri the same way.
            srv.recvFrame(Frames.headers(1).pseudo(Pseudo.request(Method.OPTIONS, uriClone)).eos())
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, conn) = clientHandshake(io)
        drive(conn)
        val request = Request.builder()
            .method(Method.OPTIONS)
            .uri(uri)
            .body(Unit)

        val (response, stream) = client.sendRequest(request, true)
        stream.close()

        response.await().body.close()
        s.join()
    }

    @Test
    fun notifyOnSendCapacity() = h2Test {
        // This test ensures that the client gets notified when there is additional
        // send capacity. In other words, when the server is ready to accept a new
        // stream, the client is notified.
        val (io, srv) = mockNew()
        val done = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()

        val settings = Settings()
        settings.maxConcurrentStreams = 1L

        val s = launch {
            val received = srv.assertClientHandshake(settings)
            // This is the ACK
            assertFrameEq(received, Settings())
            ready.complete(Unit)
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://www.example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.recvFrame(
                Frames.headers(3)
                    .request("GET", "https://www.example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(3).response(200).eos())
            srv.recvFrame(
                Frames.headers(5)
                    .request("GET", "https://www.example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(5).response(200).eos())
            // Don't close the connection until the client is done doing its
            // checks.
            done.await()
            srv.close()
        }

        val (client, conn) = clientHandshake(io)
        // ⚖️ adapted: a failure in this task fails the test directly; in the reference it drops `done_tx`, which
        // makes the server side panic.
        launch {
            ready.await()

            val responses = ArrayList<ResponseFuture>()

            repeat(3) {
                // Wait for capacity. If the client is **not** notified,
                // this hangs.
                client.ready()

                val request = Request.builder()
                    .uri("https://www.example.com/")
                    .body(Unit)

                val (response, stream) = client.sendRequest(request, true)
                stream.close()

                responses.add(response)
            }

            for (r in responses) {
                val response = r.await()
                assertEquals(StatusCode.OK, response.status)
                response.body.close()
            }

            client.ready()

            done.complete(Unit)
            // the task ends: the client is dropped
            client.close()
        }

        conn.run() // expect("h2")
        s.join()
    }

    @Test
    fun sendStreamPollReset() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            srv.sendFrame(Frames.reset(1).refused())
            srv.close()
        }

        val (client, conn) = Builder()
            .handshake(io)
        drive(conn)
        val request = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        val (response, tx) = client.sendRequest(request, false)
        val reason = tx.awaitReset()
        tx.close()
        assertEquals(Reason.REFUSED_STREAM, reason)
        response.close()
        client.close()
        s.join()
    }

    @Test
    fun dropPendingOpen() = h2Test {
        // This test checks that a stream queued for pending open behaves correctly when its
        // client drops.
        val (io, srv) = mockNew()
        val init = CompletableDeferred<Unit>()
        val triggerGoAway = CompletableDeferred<Unit>()
        val sentGoAway = CompletableDeferred<Unit>()
        val dropped = CompletableDeferred<Unit>()

        val settings = Settings()
        settings.maxConcurrentStreams = 2L

        val s = launch {
            val received = srv.assertClientHandshake(settings)
            // This is the ACK
            assertFrameEq(received, Settings())
            init.complete(Unit)
            srv.recvFrame(Frames.headers(1).request("GET", "https://www.example.com/"))
            srv.recvFrame(
                Frames.headers(3)
                    .request("GET", "https://www.example.com/")
                    .eos(),
            )
            triggerGoAway.await()
            srv.sendFrame(Frames.goAway(3))
            sentGoAway.complete(Unit)
            dropped.await()
            srv.sendFrame(Frames.headers(3).response(200).eos())
            srv.recvFrame(Frames.data(1, ByteArray(0)).eos())
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        fun request(): Request<Unit> = Request.builder()
            .uri("https://www.example.com/")
            .body(Unit)

        val (client, conn) = Builder()
            .maxConcurrentResetStreams(0)
            .handshake(io)
        val driver = drive(conn)

        // f
        init.await() // expect("init_rx")
        // Fill up the concurrent stream limit.
        client.ready()
        val response1 = client.sendRequest(request(), false)
        client.ready()
        val response2 = client.sendRequest(request(), true)
        client.ready()
        val response3 = client.sendRequest(request(), true)

        // Trigger a GOAWAY frame to invalidate our third request.
        triggerGoAway.complete(Unit)
        sentGoAway.await() // expect("sent_go_away_rx")
        // Now drop all the references to that stream.
        response3.first.close()
        response3.second.close()
        client.close()
        dropped.complete(Unit)

        // Complete the second request, freeing up a stream.
        response2.first.await().body.close() // expect("resp2")
        response1.second.sendData(Bytes.EMPTY, true)
        val resp1 = response1.first.await() // expect("resp1")
        // f ends: what it still holds is dropped
        response1.second.close()
        response2.second.close()
        resp1.body.close()

        driver.await().getOrThrow() // expect("h2")
        s.join()
    }

    @Test
    fun malformedResponseHeadersDontUnlinkStream() = h2Test {
        // This test checks that receiving malformed headers frame on a stream with
        // no remaining references correctly resets the stream, without prematurely
        // unlinking it.
        val (io, srv) = mockNew()
        val drop = CompletableDeferred<Unit>()
        val queued = CompletableDeferred<Unit>()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())

            srv.recvFrame(Frames.headers(1).request("GET", "http://example.com/"))
            srv.recvFrame(Frames.headers(3).request("GET", "http://example.com/"))
            srv.recvFrame(Frames.headers(5).request("GET", "http://example.com/"))
            drop.complete(Unit)
            queued.await()
            srv.sendBytes(
                raw(
                    // 2 byte frame
                    0, 0, 2,
                    // type: HEADERS
                    1,
                    // flags: END_STREAM | END_HEADERS
                    5,
                    // stream identifier: 3
                    0, 0, 0, 3,
                    // data - invalid (pseudo not at end of block)
                    144,
                    135,
                    // Per the spec, this frame should cause a stream error of type
                    // PROTOCOL_ERROR.
                ),
            )
            srv.close()
        }

        fun request(): Request<Unit> = Request.builder()
            .uri("http://example.com/")
            .body(Unit)

        val (client, conn) = Builder()
            .handshake(io)

        val (req1, send1) = client.sendRequest(request(), false)
        // Use up most of the connection window.
        send1.sendData(bytes(65534), true)
        val (req2, send2) = client.sendRequest(request(), false)
        val (req3, send3) = client.sendRequest(request(), false)

        val driver = drive(conn)
        // f
        drop.await()
        // Use up the remainder of the connection window.
        send2.sendData(bytes(2), true)
        // Queue up for more connection window.
        send3.sendData(bytes(1), true)
        queued.complete(Unit)
        req2.close()
        req3.close()
        // f ends: send2 and send3 were moved into it
        send2.close()
        send3.close()

        driver.await().getOrThrow() // expect("h2")
        req1.close()
        send1.close()
        client.close()
        s.join()
    }

    @Test
    fun allowEmptyDataForHead() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("HEAD", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(
                Frames.headers(1)
                    .response(200)
                    .field("content-length", "100"),
            )
            srv.sendFrame(Frames.data(1, "").eos())
            srv.close()
        }

        val (client, h2) = Builder()
            .handshake(io)
        spawn {
            h2.run() // expect("connection failed")
        }
        val request = Request.builder()
            .method(Method.HEAD)
            .uri("https://example.com/")
            .body(Unit)
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        val (_, body) = response.await()
        assertEquals("", body.data()!!.decodeToString())
        body.close()
        client.close()
        s.join()
    }

    @Test
    fun rejectNoneZeroContentLengthHeaderWithEndStream() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(
                Frames.headers(1)
                    .response(200)
                    .field("content-length", "100")
                    .eos(),
            )
            srv.close()
        }

        val (client, h2) = Builder()
            .handshake(io)
        spawn {
            h2.run() // expect("connection failed")
        }
        val request = Request.builder()
            .method(Method.GET)
            .uri("https://example.com/")
            .body(Unit)
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        assertFailsWith<H2Error> { response.await() }
        client.close()
        s.join()
    }

    @Test
    fun earlyHints() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(103))
            srv.sendFrame(Frames.headers(1).response(200).field("content-length", "2"))
            srv.sendFrame(Frames.data(1, "ok").eos())
            srv.close()
        }

        val (client, h2) = Builder()
            .handshake(io)
        spawn {
            h2.run() // expect("connection failed")
        }
        val request = Request.builder()
            .method(Method.GET)
            .uri("https://example.com/")
            .body(Unit)
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        val (ha, body) = response.await()
        println(ha)
        assertEquals("ok", body.data()!!.decodeToString())
        body.close()
        client.close()
        s.join()
    }

    @Test
    fun informationalWhileLocalStreaming() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            srv.sendFrame(Frames.headers(1).response(103))
            srv.sendFrame(Frames.headers(1).response(200).field("content-length", "2"))
            srv.recvFrame(Frames.data(1, "hello").eos())
            srv.sendFrame(Frames.data(1, "ok").eos())
            srv.close()
        }

        val (client, h2) = Builder()
            .handshake(io)
        spawn {
            h2.run() // expect("connection failed")
        }
        val request = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)
        // don't EOS stream yet..
        val (response, bodyTx) = client.sendRequest(request, false)
        // eventual response is 200, not 103
        val resp = response.await() // expect("response")
        // assert_eq!(resp.status(), 200);
        // now we can end the stream
        bodyTx.sendData(bytes("hello"), true) // expect("send_data")
        val body = resp.body
        assertEquals("ok", body.data()!!.decodeToString())
        body.close()
        bodyTx.close()
        client.close()
        s.join()
    }

    @Test
    fun extendedConnectProtocolDisabledByDefault() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())

            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        drive(h2)

        // we send a simple req here just to drive the connection so we can
        // receive the server settings.
        val request = Request.get("https://example.com/").body(Unit)
        // first request is allowed
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        response.await().body.close()

        assertFalse(client.isExtendedConnectProtocolEnabled)
        client.close()
        s.join()
    }

    @Test
    fun extendedConnectProtocolEnabledDuringHandshake() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake(Frames.settings().enableConnectProtocol(1))
            assertFrameEq(settings, Settings())

            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        drive(h2)

        // we send a simple req here just to drive the connection so we can
        // receive the server settings.
        val request = Request.get("https://example.com/").body(Unit)
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        response.await().body.close()

        assertTrue(client.isExtendedConnectProtocolEnabled)
        client.close()
        s.join()
    }

    @Test
    fun invalidConnectProtocolEnabledSetting() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            // Send a settings frame
            srv.sendFrame(Frames.settings().enableConnectProtocol(2))
            srv.readPreface()

            val settings = assertIs<Settings>(srv.next() ?: fail("unexpected EOF"))
            assertFrameEq(settings, Settings())

            // Send the ACK
            val ack = Settings.ack()

            // TODO: Don't unwrap?
            srv.send(ack)

            val frame = srv.next()!!
            val goAway = assertIs<GoAway>(frame)
            assertEquals(Reason.PROTOCOL_ERROR, goAway.reason)
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        drive(h2)

        // we send a simple req here just to drive the connection so we can
        // receive the server settings.
        val request = Request.get("https://example.com/").body(Unit)
        val (response, stream) = client.sendRequest(request, true)
        stream.close()

        val error = assertFailsWith<H2Error> { response.await() }
        assertEquals(Reason.PROTOCOL_ERROR, error.reason())
        client.close()
        s.join()
    }

    @Test
    fun extendedConnectRequest() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake(Frames.settings().enableConnectProtocol(1))
            assertFrameEq(settings, Settings())

            srv.recvFrame(
                Frames.headers(1)
                    .pseudo(
                        Pseudo(
                            method = Method.CONNECT,
                            scheme = "http",
                            authority = "bread",
                            path = "/baguette",
                            protocol = Protocol.fromStatic("the-bread-protocol").asStr(),
                        ),
                    )
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        drive(h2)

        val request = Request.connect("http://bread/baguette")
            .extension(Protocol("the-bread-protocol"))
            .body(Unit)
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        response.await().body.close()
        client.close()
        s.join()
    }

    @Test
    fun rogueServerOddHeaders() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.sendFrame(Frames.headers(1))
            srv.recvFrame(Frames.goAway(0).protocolError())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)

        val err = assertFailsWith<H2Error> { h2.run() }
        assertTrue(err.isGoAway)
        assertEquals(Reason.PROTOCOL_ERROR, err.reason())
        client.close()
        s.join()
    }

    @Test
    fun rogueServerEvenHeaders() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.sendFrame(Frames.headers(2))
            srv.recvFrame(Frames.goAway(0).protocolError())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)

        val err = assertFailsWith<H2Error> { h2.run() }
        assertTrue(err.isGoAway)
        assertEquals(Reason.PROTOCOL_ERROR, err.reason())
        client.close()
        s.join()
    }

    @Test
    fun rogueServerReusedHeaders() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())

            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://camembert.fromage")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.sendFrame(Frames.headers(1))
            srv.recvFrame(Frames.reset(1).streamClosed())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        val conn = drive(h2)

        run {
            val request = Request.builder()
                .method(Method.GET)
                .uri("https://camembert.fromage")
                .body(Unit)
            val (fut, stream) = client.sendRequest(request, true)
            val res = fut.await()
            stream.close()
            // `_res` is dropped at the end of the driven block
            res.body.close()
        }

        conn.await().getOrThrow()
        client.close()
        s.join()
    }

    @Test
    fun clientBuilderHeaderTableSize() = h2Test {
        val (io, srv) = mockNew()
        val settings = Settings()

        settings.headerTableSize = 10000L

        val s = launch {
            val recvSettings = srv.assertClientHandshake()
            assertFrameEq(recvSettings, settings)

            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val builder = Builder()
        builder.headerTableSize(10000)

        val (client, h2) = builder.handshake(io)
        drive(h2)
        val request = Request.get("https://example.com/").body(Unit)
        val (response, stream) = client.sendRequest(request, true)
        stream.close()
        response.await().body.close()
        client.close()
        s.join()
    }

    @Test
    fun configuredMaxConcurrentSendStreamsAndUpdateItBasedOnEmptySettingsFrame() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            // Send empty SETTINGS frame (no MAX_CONCURRENT_STREAMS is provided)
            srv.sendFrame(Frames.settings())
            srv.close()
        }

        val (client, h2) = Builder()
            // Configure the initial value to 2024
            .initialMaxSendStreams(2024)
            .handshake(io)
        // It should be pre-configured value before it receives the initial
        // SETTINGS frame from the server
        assertEquals(2024, h2.maxConcurrentSendStreams())
        h2.run()
        // If the server's initial SETTINGS frame does not include
        // MAX_CONCURRENT_STREAMS, this should be updated to usize::MAX.
        // (usize::MAX limits are Int.MAX_VALUE here.)
        assertEquals(Int.MAX_VALUE, h2.maxConcurrentSendStreams())
        client.close()
        s.join()
    }

    @Test
    fun configuredMaxConcurrentSendStreamsAndUpdateItBasedOnNonEmptySettingsFrame() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            // Send SETTINGS frame with MAX_CONCURRENT_STREAMS set to 42
            srv.sendFrame(Frames.settings().maxConcurrentStreams(42))
            srv.close()
        }

        val (client, h2) = Builder()
            // Configure the initial value to 2024
            .initialMaxSendStreams(2024)
            .handshake(io)
        // It should be pre-configured value before it receives the initial
        // SETTINGS frame from the server
        assertEquals(2024, h2.maxConcurrentSendStreams())
        h2.run()
        // Now the client has received the initial SETTINGS frame from the
        // server, which should update the value accordingly
        assertEquals(42, h2.maxConcurrentSendStreams())
        client.close()
        s.join()
    }

    @Test
    fun receiveSettingsFrameTwiceWithSecondOneEmpty() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            // Send the initial SETTINGS frame with MAX_CONCURRENT_STREAMS set to 42
            srv.sendFrame(Frames.settings().maxConcurrentStreams(42))

            // Handle the client's connection preface
            srv.readPreface()
            when (val frame = srv.next()) {
                is Settings -> {
                    val ack = Settings.ack()
                    srv.send(ack)
                }
                null -> fail("unexpected EOF")
                else -> fail("unexpected frame: $frame")
            }

            // Should receive the ack for the server's initial SETTINGS frame
            val frame = assertIs<Settings>(srv.next())
            assertTrue(frame.isAck)

            // Send another SETTINGS frame with no MAX_CONCURRENT_STREAMS
            // This should not update the max_concurrent_send_streams value that
            // the client manages.
            srv.sendFrame(Frames.settings())
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        assertEquals(Int.MAX_VALUE, h2.maxConcurrentSendStreams())
        h2.run()
        // Even though the second SETTINGS frame contained no value for
        // MAX_CONCURRENT_STREAMS, update to usize::MAX should not happen
        assertEquals(42, h2.maxConcurrentSendStreams())
        client.close()
        s.join()
    }

    @Test
    fun receiveSettingsFrameTwiceWithSecondOneNonEmpty() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            // Send the initial SETTINGS frame with MAX_CONCURRENT_STREAMS set to 42
            srv.sendFrame(Frames.settings().maxConcurrentStreams(42))

            // Handle the client's connection preface
            srv.readPreface()
            when (val frame = srv.next()) {
                is Settings -> {
                    val ack = Settings.ack()
                    srv.send(ack)
                }
                null -> fail("unexpected EOF")
                else -> fail("unexpected frame: $frame")
            }

            // Should receive the ack for the server's initial SETTINGS frame
            val frame = assertIs<Settings>(srv.next())
            assertTrue(frame.isAck)

            // Send another SETTINGS frame with no MAX_CONCURRENT_STREAMS
            // This should not update the max_concurrent_send_streams value that
            // the client manages.
            srv.sendFrame(Frames.settings().maxConcurrentStreams(2024))
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        assertEquals(Int.MAX_VALUE, h2.maxConcurrentSendStreams())
        h2.run()
        // The most-recently advertised value should be used
        assertEquals(2024, h2.maxConcurrentSendStreams())
        client.close()
        s.join()
    }

    // If the server has not sent a go_away message before dropping the connection
    // make sure the UnexpectedEof error is propogated.
    @Test
    fun serverDropConnectionUnexpectedlyReturnUnexpectedEofErr() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            srv.closeWithoutNotify()
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        spawn {
            try {
                val request = Request.builder()
                    .uri("https://http2.akamai.com/")
                    .body(Unit)
                val (fut, stream) = client.sendRequest(request, true)
                try {
                    fut.await().body.close() // expect("request")
                } finally {
                    stream.close()
                }
            } finally {
                // the client was moved into the task
                client.close()
            }
        }
        val err = assertFailsWith<H2Error>("should receive UnexpectedEof") { h2.run() }
        assertEquals(
            IoErrorKind.UnexpectedEof,
            err.ioKind ?: fail("should be UnexpectedEof"),
        )
        s.join()
    }

    @Test
    fun serverDropConnectionAfterGoAway() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.goAway(1))
            delay(50)
            srv.closeWithoutNotify()
            srv.close()
        }

        val (client, h2) = clientHandshake(io)
        spawn {
            try {
                val request = Request.builder()
                    .uri("https://http2.akamai.com/")
                    .body(Unit)
                val (fut, stream) = client.sendRequest(request, true)
                try {
                    fut.await().body.close() // expect("request")
                } finally {
                    stream.close()
                }
            } finally {
                // the client was moved into the task
                client.close()
            }
        }
        h2.run()
        s.join()
    }

    @Test
    fun resetBeforeHeadersReachesPeerWithoutHeaders() = h2Test {
        // Repro: body future errors immediately and hyper/h2 converts that into a
        // RST_STREAM before the queued HEADERS are ever written, so the peer sees
        // a reset for an idle stream and treats it as a PROTOCOL_ERROR.
        val (io, srv) = mockNew()

        // Server task: perform handshake then observe the first frame.
        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())

            val frame = withTimeoutOrNull(1000) {
                srv.next() ?: fail("unexpected EOF")
            } ?: fail("timed out waiting for first frame")

            when {
                frame is Headers && frame.streamId == StreamId(1) -> {
                    assertTrue(frame.isEndStream == false)
                }
                frame is Reset && frame.streamId == StreamId(1) -> {
                    fail(
                        "BUG: client sent RST_STREAM before any HEADERS on stream 1; reason=${frame.reason}",
                    )
                }
                else -> fail("unexpected first frame: $frame")
            }
            srv.close()
        }

        // Client task: queue HEADERS, immediately reset, then drive the connection.
        val (client, conn) = clientHandshake(io)

        val req = Request.builder()
            .method("POST")
            .uri("https://example.com/")
            .body(Unit)
        client.ready() // expect("poll_ready")
        val (respFut, sendStream) = client.sendRequest(req, false)

        // Simulate body error (reqwest wraps into io::Error::Other) by resetting
        // immediately after the stream is created.
        sendStream.sendReset(Reason.INTERNAL_ERROR)

        // Now start driving the connection so the queued frames get written.
        val connTask = launch {
            runCatching { conn.run() }
        }

        // Give the connection a moment to flush frames.
        delay(10)

        sendStream.close()
        connTask.join()
        respFut.close()
        client.close()
        s.join()
    }

    /**
     * RFC 9113 S5.1: "Receiving any frame other than HEADERS or PRIORITY on a
     * stream in [idle] state MUST be treated as a connection error of type
     * PROTOCOL_ERROR."
     */
    @Test
    fun frameOnPendingOpenStreamIsConnError() = h2Test {
        for (scenario in 0 until 5) {
            val (io, srv) = mockNew()

            val s = launch {
                val settings = srv.assertClientHandshake(Frames.settings().maxConcurrentStreams(1))
                assertFrameEq(settings, Settings())

                // 3. Receive stream 1 HEADERS.
                srv.recvFrame(
                    Frames.headers(1)
                        .request("POST", "https://example.com/")
                        .eos(),
                )

                idleMs(50)

                // 4. Send a frame targeting stream 3, whose HEADERS haven't
                //    been transmitted since it's pending. This is illegal.
                when (scenario) {
                    0 -> srv.sendFrame(Frames.reset(3).reason(Reason.NO_ERROR))
                    1 -> srv.sendFrame(Frames.reset(3).reason(Reason.CANCEL))
                    2 -> srv.sendFrame(Frames.windowUpdate(3, 1024))
                    3 -> srv.sendFrame(Frames.headers(3).response(200).eos())
                    4 -> srv.sendFrame(Frames.data(3, "hello"))
                    else -> error("unreachable")
                }

                // 5. Client responds with GOAWAY(PROTOCOL_ERROR).
                srv.recvFrame(Frames.goAway(0).protocolError())
                srv.close()
            }

            val (client, conn) = Builder()
                .initialMaxSendStreams(1)
                .handshake(io)
            drive(conn)

            // 1. Stream 1 fills the concurrent slot
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://example.com/")
                .body(Unit)
            val (resp1, stream1) = client.sendRequest(request, true)
            stream1.close()
            client.ready()

            // 2. Stream 3 is queued
            val request3 = Request.builder()
                .method(Method.POST)
                .uri("https://example.com/")
                .body(Unit)
            val (resp3, stream3) = client.sendRequest(request3, true)
            stream3.close()

            // 6. Connection error propagates to poll_ready.
            assertFailsWith<H2Error>("connection error") { client.ready() }

            s.join()
            resp1.close()
            resp3.close()
            client.close()
        }
    }
}
