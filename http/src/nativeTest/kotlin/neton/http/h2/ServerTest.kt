// Ported from h2 0.4.19 tests/h2-tests/tests/server.rs (43 tests).
//
// Notes on the Kotlin model (see GUIDE.md):
// - Each Rust `join(client, srv)` becomes a launched coroutine for the mock client side, the server side inline, then
//   `c.join()`. The server connection is driven by `run()` in its own coroutine; `srv.next().await` is `srv.accept()`.
// - The mock handle moved into the Rust client block is dropped at its end (the server then reads EOF): mirrored
//   with `client.close()` at that point. Stream handles dropped by the reference are closed where they are dropped.
// - Where the reference expects the connection to fail, `run()` is driven through `driveCatching` so that the error
//   is returned instead of failing the test's scope (an exception in a child coroutine cancels its parent).
//
// Mock note (not an API gap of the library): in `clientDropConnectionWithoutCloseNotify` the Kotlin mock's
// `MockIo.read` only checks `unexpectedEof` before parking in the inner read; once the handle is closed the parked
// read returns a clean EOF instead of the reference's "Simulate an unexpected eof error". The test still passes in
// both cases but then checks the clean-EOF path; re-checking `shared.unexpectedEof` after the inner read in Mock.kt
// would restore the reference behaviour.

package neton.http.h2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.http.Method
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.h2.frame.Ping as PingFrame
import neton.http.h2.frame.Pseudo
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Settings
import neton.http.h2.server.Builder
import neton.http.h2.server.Connection
import neton.http.h2.server.handshake
import neton.http.uri.Uri
import neton.io.bytes.Buffer
import neton.http.testStreamPair
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerTest {
    private val shutdownPayload: ByteArray get() = PingFrame(PingFrame.SHUTDOWN).payloadBytes()

    /** Drives [srv] (`poll_closed`), returning its error (null when it closed cleanly) instead of throwing it. */
    private fun CoroutineScope.driveCatching(srv: Connection): Deferred<H2Error?> = async {
        try {
            srv.run()
            null
        } catch (e: H2Error) {
            e
        }
    }

    @Test
    fun readPrefaceInMultipleFrames() = h2Test {
        val mock = MockIoBuilder()
            .read(raw("PRI * HTTP/2.0"))
            .read(raw("\r\n\r\nSM\r\n\r\n"))
            .write(Frames.SETTINGS)
            .read(Frames.SETTINGS)
            .write(Frames.SETTINGS_ACK)
            .read(Frames.SETTINGS_ACK)
            .build()

        val h2 = handshake(mock)
        launch { h2.run() }

        assertNull(h2.accept())
        mock.assertDone()
    }

    @Test
    fun serverBuilderSetMaxConcurrentStreams() = h2Test {
        val (io, client) = mockNew()

        val settings = Settings()
        settings.maxConcurrentStreams = 1L

        val c = launch {
            val recvSettings = client.assertServerHandshake()
            assertFrameEq(recvSettings, settings)
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/"))
            client.sendFrame(Frames.headers(3).request("GET", "https://example.com/"))
            client.sendFrame(Frames.data(1, "hello").eos())
            client.recvFrame(Frames.reset(3).refused())
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val builder = Builder()
        builder.maxConcurrentStreams(1)

        val srv = builder.handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!

        assertEquals(Method.GET, req.method)

        val rsp = Response.builder().status(200).body(Unit)
        stream.sendResponse(rsp, true).close()

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun serverBuilderHeaderTableSize() = h2Test {
        for (size in listOf(0, 10000)) {
            val (io, client) = mockNew()

            val expected = Settings()
            expected.headerTableSize = size.toLong()

            val c = launch {
                val recvSettings = client.assertServerHandshake()
                assertFrameEq(recvSettings, expected)
                client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
                client.recvFrame(Frames.headers(1).response(200).eos())
                client.close()
            }

            val builder = Builder()
            builder.headerTableSize(size)

            val srv = builder.handshake(io)
            launch { srv.run() }
            val (req, stream) = srv.accept()!!
            assertEquals(Method.GET, req.method)
            stream.sendResponse(Response.builder().status(200).body(Unit), true).close()
            assertNull(srv.accept())
            c.join()
        }
    }

    @Test
    fun serveRequest() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!

        assertEquals(Method.GET, req.method)

        val rsp = Response.builder().status(200).body(Unit)
        stream.sendResponse(rsp, true).close()

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun serveConnect() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("CONNECT", "localhost").eos())
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!

        assertEquals(Method.CONNECT, req.method)

        val rsp = Response.builder().status(200).body(Unit)
        stream.sendResponse(rsp, true).close()

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun pushRequest() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            client.assertServerHandshake(Frames.settings().maxConcurrentStreams(100))
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.pushPromise(1, 2).request("GET", "https://http2.akamai.com/style.css"))
            client.recvFrame(Frames.headers(2).response(200).eos())
            client.recvFrame(Frames.pushPromise(1, 4).request("GET", "https://http2.akamai.com/style2.css"))
            client.recvFrame(Frames.headers(4).response(200).eos())
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!

        assertEquals(Method.GET, req.method)

        // Promise stream 2
        val pushedS2 = run {
            val req = Request.builder().method("GET").uri("https://http2.akamai.com/style.css").body(Unit)
            stream.pushRequest(req)
        }

        // Promise stream 4 and push response headers
        run {
            val req = Request.builder().method("GET").uri("https://http2.akamai.com/style2.css").body(Unit)
            val rsp = Response.builder().status(200).body(Unit)
            val pushed = stream.pushRequest(req)
            pushed.sendResponse(rsp, true).close()
            pushed.close()
        }

        // Push response to stream 2
        run {
            val rsp = Response.builder().status(200).body(Unit)
            pushedS2.sendResponse(rsp, true).close()
        }

        // Send response for stream 1
        val rsp = Response.builder().status(200).body(Unit)
        stream.sendResponse(rsp, true).close()

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun pushRequestDisabled() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            client.assertServerHandshake(Frames.settings().disablePush())
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!

        assertEquals(Method.GET, req.method)

        // attempt to push - expect failure
        val pushReq = Request.builder().method("GET").uri("https://http2.akamai.com/style.css").body(Unit)
        assertFailsWith<H2Error>("push_request should error") { stream.pushRequest(pushReq) }

        // send normal response
        val rsp = Response.builder().status(200).body(Unit)
        stream.sendResponse(rsp, true).close()

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun pushRequestAgainstConcurrency() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            client.assertServerHandshake(Frames.settings().maxConcurrentStreams(1))
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.pushPromise(1, 2).request("GET", "https://http2.akamai.com/style.css"))
            client.recvFrame(Frames.headers(2).response(200))
            client.recvFrame(Frames.pushPromise(1, 4).request("GET", "https://http2.akamai.com/style2.css"))
            client.recvFrame(Frames.data(2, "").eos())
            client.recvFrame(Frames.headers(4).response(200).eos())
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!

        assertEquals(Method.GET, req.method)

        // Promise stream 2 and start response (concurrency limit reached)
        val s2Tx = run {
            val req = Request.builder().method("GET").uri("https://http2.akamai.com/style.css").body(Unit)
            val pushedStream = stream.pushRequest(req)
            val rsp = Response.builder().status(200).body(Unit)
            val tx = pushedStream.sendResponse(rsp, false)
            pushedStream.close()
            tx
        }

        // Promise stream 4 and push response
        run {
            val pushedReq = Request.builder().method("GET").uri("https://http2.akamai.com/style2.css").body(Unit)
            val rsp = Response.builder().status(200).body(Unit)
            val pushed = stream.pushRequest(pushedReq)
            pushed.sendResponse(rsp, true).close()
            pushed.close()
        }

        // Send and finish response for stream 1
        run {
            val rsp = Response.builder().status(200).body(Unit)
            stream.sendResponse(rsp, true).close()
        }

        // Finish response for stream 2 (at which point stream 4 will be sent)
        s2Tx.sendData(bytes(0), true)

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun pushRequestWithData() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            client.assertServerHandshake(Frames.settings().maxConcurrentStreams(100))
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.headers(1).response(200))
            client.recvFrame(Frames.pushPromise(1, 2).request("GET", "https://http2.akamai.com/style.css"))
            client.recvFrame(Frames.headers(2).response(200))
            client.recvFrame(Frames.data(1, "").eos())
            client.recvFrame(Frames.data(2, ByteArray(1)).eos())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!

        assertEquals(Method.GET, req.method)

        // Start response to stream 1
        val s1Tx = run {
            val rsp = Response.builder().status(200).body(Unit)
            stream.sendResponse(rsp, false)
        }

        // Promise stream 2, push response headers and send data
        run {
            val pushedReq = Request.builder().method("GET").uri("https://http2.akamai.com/style.css").body(Unit)
            val rsp = Response.builder().status(200).body(Unit)
            val pushed = stream.pushRequest(pushedReq)
            val pushTx = pushed.sendResponse(rsp, false)
            pushed.close()
            // Make sure nothing can queue our pushed stream before we have the PushPromise sent
            pushTx.sendData(bytes(1), true)
            pushTx.reserveCapacity(1)
            pushTx.close()
        }

        // End response for stream 1
        s1Tx.sendData(bytes(0), true)

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun pushRequestBetweenData() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            client.assertServerHandshake(Frames.settings().maxConcurrentStreams(100))
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.headers(1).response(200))
            client.recvFrame(Frames.data(1, ""))
            client.recvFrame(Frames.pushPromise(1, 2).request("GET", "https://http2.akamai.com/style.css"))
            client.recvFrame(Frames.headers(2).response(200).eos())
            client.recvFrame(Frames.data(1, "").eos())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!

        assertEquals(Method.GET, req.method)

        // Push response to stream 1 and send some data
        val s1Tx = run {
            val rsp = Response.builder().status(200).body(Unit)
            val tx = stream.sendResponse(rsp, false)
            tx.sendData(bytes(0), false)
            tx
        }

        // Promise stream 2 and push response headers
        run {
            val pushedReq = Request.builder().method("GET").uri("https://http2.akamai.com/style.css").body(Unit)
            val rsp = Response.builder().status(200).body(Unit)
            val pushed = stream.pushRequest(pushedReq)
            pushed.sendResponse(rsp, true).close()
            pushed.close()
        }

        // End response for stream 1
        s1Tx.sendData(bytes(0), true)

        assertNull(srv.accept())
        c.join()
    }

    // ignored in the reference (an empty placeholder there too)
    @Ignore
    @Test
    fun acceptWithPendingConnectionsAfterSocketClose() = h2Test {}

    @Test
    fun recvInvalidAuthority() = h2Test {
        val (io, client) = mockNew()

        val badAuth = "not:a/good authority"
        val badHeaders = Frames.headers(1).request("GET", "https://example.com/").eos()
        badHeaders.frame.pseudo.authority = badAuth

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(badHeaders)
            client.recvFrame(Frames.reset(1).protocolError())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun recvConnectionHeader() = h2Test {
        val (io, client) = mockNew()

        fun req(id: Int, name: String, value: String) =
            Frames.headers(id).request("GET", "https://example.com/").field(name, value).eos()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(req(1, "connection", "foo"))
            client.sendFrame(req(3, "keep-alive", "5"))
            client.sendFrame(req(5, "proxy-connection", "bar"))
            client.sendFrame(req(7, "transfer-encoding", "chunked"))
            client.sendFrame(req(9, "upgrade", "HTTP/2"))
            client.recvFrame(Frames.reset(1).protocolError())
            client.recvFrame(Frames.reset(3).protocolError())
            client.recvFrame(Frames.reset(5).protocolError())
            client.recvFrame(Frames.reset(7).protocolError())
            client.recvFrame(Frames.reset(9).protocolError())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun sendsResetNoErrorWhenReqBodyIsDropped() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("POST", "https://example.com/"))
            // server responded with data before consuming POST-request's body, resulting in `RST_STREAM(NO_ERROR)`.
            client.recvFrame(Frames.headers(1).response(200))
            client.recvFrame(Frames.data(1, ByteArray(16384)))
            client.recvFrame(Frames.data(1, ByteArray(16384)).eos())
            client.recvFrame(Frames.reset(1).reason(Reason.NO_ERROR))
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        run {
            val (req, stream) = srv.accept()!!
            assertEquals(Method.POST, req.method)

            val rsp = Response.builder().status(200).body(Unit)
            val tx = stream.sendResponse(rsp, false)
            tx.sendData(bytes(16384 * 2), true)
            // end of the Rust block: tx, stream and req are dropped
            tx.close()
            stream.close()
            req.body.close()
        }
        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun noErrorResponseBodyDeliveredBeforeRst() = h2Test {
        // When a server sends a large response body and drops the request
        // body without reading it, NO_ERROR is scheduled. The response DATA
        // must still be delivered.
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("POST", "https://example.com/"))
            client.recvFrame(Frames.headers(1).response(200))
            client.recvFrame(Frames.data(1, ByteArray(16384)))
            client.recvFrame(Frames.data(1, ByteArray(16384)))
            client.recvFrame(Frames.data(1, ByteArray(16384)))
            client.recvFrame(Frames.data(1, ByteArray(16383)))
            // These window updates allow the full response to be delivered.
            client.sendFrame(Frames.windowUpdate(0, 65535))
            client.sendFrame(Frames.windowUpdate(1, 65535))
            client.recvFrame(Frames.data(1, ByteArray(16384)))
            client.recvFrame(Frames.data(1, ByteArray(16384)))
            client.recvFrame(Frames.data(1, ByteArray(1)).eos())
            client.recvFrame(Frames.reset(1).reason(Reason.NO_ERROR))
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        run {
            val (req, stream) = srv.accept()!!
            assertEquals(Method.POST, req.method)

            val rsp = Response.builder().status(200).body(Unit)
            val tx = stream.sendResponse(rsp, false)
            // Response body larger than the stream window. The first 65535 bytes
            // are sent immediately, and the remaining bytes wait for the client's
            // WINDOW_UPDATE.
            tx.sendData(bytes(16384 * 6), true)
            // end of the Rust block: tx, stream and req are dropped
            tx.close()
            stream.close()
            req.body.close()
        }
        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun abruptShutdown() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("POST", "https://example.com/"))
            client.recvFrame(Frames.goAway(1).internalError())
            client.recvEof()
            client.close()
        }

        val srv = handshake(io)
        val conn = async { srv.run() }
        val (req, tx) = assertNotNull(srv.accept(), "server receives request")

        val reqFut = async {
            val body = runCatching { concat(req.body) }
            tx.close()
            val err = assertIs<H2Error>(body.exceptionOrNull(), "request body should error")
            assertEquals(
                Reason.INTERNAL_ERROR,
                err.reason(),
                "streams should be also error with user's reason",
            )
        }

        srv.abruptShutdown(Reason.INTERNAL_ERROR)

        // srv_fut: poll_closed(..).expect("server")
        conn.await()
        reqFut.await()
        c.join()
    }

    @Test
    fun gracefulShutdown() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            // 2^31 - 1 = 2147483647
            // Note: not using a constant in the library because library devs
            // can be unsmart.
            client.recvFrame(Frames.goAway(2147483647))
            client.recvFrame(Frames.ping(shutdownPayload))
            client.recvFrame(Frames.headers(1).response(200).eos())
            // Pretend this stream was sent while the GOAWAY was in flight
            client.sendFrame(Frames.headers(3).request("POST", "https://example.com/"))
            client.sendFrame(Frames.ping(shutdownPayload).pong())
            client.recvFrame(Frames.goAway(3))
            // streams sent after GOAWAY receive no response
            client.sendFrame(Frames.headers(7).request("GET", "https://example.com/"))
            client.sendFrame(Frames.data(7, "").eos())
            client.sendFrame(Frames.data(3, "").eos())
            client.recvFrame(Frames.headers(3).response(200).eos())
            client.recvEof()
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!
        assertEquals(Method.GET, req.method)

        srv.gracefulShutdown()

        val rsp = Response.builder().status(200).body(Unit)
        stream.sendResponse(rsp, true).close()

        val (req2, stream2) = srv.accept()!!
        assertEquals(Method.POST, req2.method)
        val body = req2.body

        // `srv.drive(body)` then `srv.await`: the next accept runs while the body is handled.
        val next = async { assertNull(srv.accept(), "unexpected request") }
        val bodyTask = async {
            val buf = concat(body)
            body.close()
            assertTrue(buf.isEmpty())

            val rsp2 = Response.builder().status(200).body(Unit)
            stream2.sendResponse(rsp2, true).close()
            stream2.close()
        }
        bodyTask.await()
        next.await()
        c.join()
    }

    @Test
    fun goawayEvenIfClientSentGoaway() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(5).request("GET", "https://example.com/").eos())
            // Ping-pong so as to wait until server gets req
            client.pingPong(ByteArray(8))
            client.sendFrame(Frames.goAway(0))
            // 2^31 - 1 = 2147483647
            // Note: not using a constant in the library because library devs
            // can be unsmart.
            client.recvFrame(Frames.goAway(2147483647))
            client.recvFrame(Frames.ping(shutdownPayload))
            client.recvFrame(Frames.headers(5).response(200).eos())
            client.sendFrame(Frames.ping(shutdownPayload).pong())
            client.recvFrame(Frames.goAway(5))
            client.recvEof()
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!
        assertEquals(Method.GET, req.method)

        srv.gracefulShutdown()

        val rsp = Response.builder().status(200).body(Unit)
        stream.sendResponse(rsp, true).close()

        assertNull(srv.accept(), "unexpected request")
        c.join()
    }

    @Test
    fun clientGoawayDoesNotKillRemoteInitiatedStreams() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            // Client sends a request on stream 1
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            // Receive response headers (no END_STREAM)
            client.recvFrame(Frames.headers(1).response(200))
            // Client sends GOAWAY(0)
            client.sendFrame(Frames.goAway(0))
            // Server should still be able to send the response body
            client.recvFrame(Frames.data(1, "the response body").eos())
            // Server sends its own GOAWAY and closes
            client.recvFrame(Frames.goAway(1))
            client.recvEof()
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!
        assertEquals(Method.GET, req.method)

        // Send response headers without END_STREAM
        val rsp = Response.builder().status(200).body(Unit)
        val tx = stream.sendResponse(rsp, false)

        // Drive the connection while sending the body.
        // The yields ensure the connection processes the client's GOAWAY
        // before we attempt to send data.
        val next = async { assertNull(srv.accept(), "unexpected request") }
        // First yield: connection flushes headers. Client receives them
        // and sends GOAWAY(0).
        yieldOnce()
        // Second yield: connection reads and processes GOAWAY(0).
        // Before the fix, stream 1 was killed here.
        yieldOnce()
        // ⚖️ adapted: the reactor's scheduling differs from tokio's, so two yields do not guarantee that the
        // GOAWAY(0) has been read; also wait a little so the test really sends after it was processed.
        idleMs(10)
        // Send response body. Before the fix, this failed because
        // stream 1 was incorrectly closed by recv_go_away.
        tx.sendData(bytes("the response body"), true)
        next.await()
        c.join()
    }

    @Test
    fun sendsResetCancelWhenResBodyIsDropped() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.headers(1).response(200))
            client.recvFrame(Frames.reset(1).cancel())
            client.sendFrame(Frames.headers(3).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.headers(3).response(200))
            // CANCEL means "stream is no longer needed" (RFC 9113 §7). Buffered DATA
            // is discarded and RST_STREAM is sent immediately.
            client.recvFrame(Frames.reset(3).cancel())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        run {
            val (req, stream) = srv.accept()!!

            assertEquals(Method.GET, req.method)

            val rsp = Response.builder().status(200).body(Unit)
            stream.sendResponse(rsp, false).close()
            // SendStream dropped
            stream.close()
            req.body.close()
        }
        run {
            val (req, stream) = srv.accept()!!
            val rsp = Response.builder().status(200).body(Unit)
            val tx = stream.sendResponse(rsp, false)
            tx.sendData(bytes(10), false)
            // no send_data with eos
            tx.close()
            stream.close()
            req.body.close()
        }

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun tooBigHeadersSends431() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Frames.settings().maxHeaderListSize(64))
            client.sendFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .field("some-header", "some-value")
                    .eos(),
            )
            client.recvFrame(Frames.headers(1).response(431).eos())
            idleMs(10)
            client.close()
        }

        val srv = Builder()
            .maxHeaderListSize(64)
            .handshake(io)
        launch { srv.run() }

        val req = srv.accept()
        assertNull(req, "req is $req")
        c.join()
    }

    @Test
    fun tooBigHeadersSendsResetAfter431IfNotEos() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Frames.settings().maxHeaderListSize(64))
            client.sendFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .field("some-header", "some-value"),
            )
            client.recvFrame(Frames.headers(1).response(431).eos())
            client.recvFrame(Frames.reset(1).protocolError())
            client.close()
        }

        val srv = Builder()
            .maxHeaderListSize(64)
            .handshake(io)
        launch { srv.run() }

        val req = srv.accept()
        assertNull(req, "req is $req")
        c.join()
    }

    @Test
    fun abusiveHeadersSendGoaway() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Frames.settings().maxHeaderListSize(64))
            client.sendFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .field("x-abuse", "a".repeat(200))
                    .eos(),
            )
            client.recvFrame(Frames.goAway(0).calm().data("header_list_way_too_large"))
            client.close()
        }

        val srv = Builder()
            .maxHeaderListSize(64)
            .handshake(io)
        val conn = driveCatching(srv)

        val err = assertFailsWith<H2Error>("server") { srv.accept() }
        assertTrue(err.isGoAway)
        assertTrue(err.isLibrary)
        assertEquals(Reason.ENHANCE_YOUR_CALM, err.reason())
        conn.await()
        c.join()
    }

    @Test
    fun tooManyContinuationFramesSendsGoaway() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Frames.settings().maxHeaderListSize(1024L * 32))

            // the mock impl automatically splits into CONTINUATION frames if the
            // headers are too big for one frame. So without a max header list size
            // set, we'll send a bunch of headers that will eventually get nuked.
            client.sendFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .field("a".repeat(10_000), "b".repeat(10_000))
                    .field("c".repeat(10_000), "d".repeat(10_000))
                    .field("e".repeat(10_000), "f".repeat(10_000))
                    .field("g".repeat(10_000), "h".repeat(10_000))
                    .field("i".repeat(10_000), "j".repeat(10_000))
                    .field("k".repeat(10_000), "l".repeat(10_000))
                    .field("m".repeat(10_000), "n".repeat(10_000))
                    .field("o".repeat(10_000), "p".repeat(10_000))
                    .field("y".repeat(10_000), "z".repeat(10_000)),
            )
            client.recvFrame(Frames.goAway(0).calm().data("too_many_continuations"))
            client.close()
        }

        val srv = Builder()
            // should mean ~3 continuation
            .maxHeaderListSize(1024 * 32)
            .handshake(io)
        val conn = driveCatching(srv)

        val err = assertFailsWith<H2Error>("server") { srv.accept() }
        assertTrue(err.isGoAway)
        assertTrue(err.isLibrary)
        assertEquals(Reason.ENHANCE_YOUR_CALM, err.reason())
        conn.await()
        c.join()
    }

    @Test
    fun pendingAcceptRecvIllegalContentLengthData() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(
                Frames.headers(1)
                    .request("POST", "https://a.b")
                    .field("content-length", "1"),
            )
            client.sendFrame(Frames.data(1, "hello").eos())
            client.recvFrame(Frames.reset(1).protocolError())
            idleMs(10)
            client.close()
        }

        val srv = Builder()
            .handshake(io)
        launch { srv.run() }

        val req = assertNotNull(srv.accept(), "req")
        // The reference drops the connection here (end of its block); the connection keeps running until the
        // client side is done so that the RST_STREAM it owes is written, then the test's end cancels it.
        c.join()
        req.first.body.close()
        req.second.close()
    }

    @Test
    fun pollReset() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            idleMs(10)
            client.sendFrame(Frames.reset(1).cancel())
            client.close()
        }

        val srv = Builder()
            .handshake(io)
        launch { srv.run() }
        val (req, tx) = assertNotNull(srv.accept(), "server")
        val conn = async {
            val next = srv.accept()
            assertNull(next, "no second request")
        }
        val reason = tx.awaitReset()
        tx.close()
        assertEquals(Reason.CANCEL, reason)
        conn.await()
        req.body.close()
        c.join()
    }

    @Test
    fun pollResetIoError() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())

            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            idleMs(10)
            client.close()
        }

        val srv = Builder()
            .handshake(io)
        launch { srv.run() }

        val (req, tx) = assertNotNull(srv.accept(), "server")
        val conn = async {
            val next = srv.accept()
            assertNull(next, "no second request")
        }
        assertFailsWith<H2Error>("poll_reset should error") { tx.awaitReset() }
        tx.close()
        conn.await()
        req.body.close()
        c.join()
    }

    @Test
    fun pollResetAfterSendResponseIsUserError() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.headers(1).response(200))
            client.recvFrame(
                // After the error, our server will drop the handles,
                // meaning we receive a RST_STREAM here.
                Frames.reset(1).cancel(),
            )
            idleMs(10)
            client.close()
        }

        val srv = Builder()
            .handshake(io)
        launch { srv.run() }

        val (req, tx) = assertNotNull(srv.accept(), "server")
        val conn = async {
            val next = srv.accept()
            assertNull(next, "no second request")
        }
        tx.sendResponse(Response(Unit), false).close()
        req.body.close()
        assertFailsWith<H2Error>("poll_reset should error") { tx.awaitReset() }
        // tx was moved into the poll_fn and is dropped with it
        tx.close()
        conn.await()
        c.join()
    }

    @Test
    fun serverErrorOnUncleanShutdown() = h2Test {
        val (io, client) = mockNew()

        // The reference creates the handshake future first but only polls it after the client is gone.
        client.sendBytes("PRI *".encodeToByteArray())
        client.close()

        assertFailsWith<H2Error>("should error") { Builder().handshake(io) }
    }

    @Test
    fun serverErrorOnStatusInRequest() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).status(StatusCode.OK))
            client.recvFrame(Frames.reset(1).protocolError())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun requestWithoutAuthority() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(
                Frames.headers(1)
                    .request("GET", "/just-a-path")
                    .scheme("http")
                    .eos(),
            )
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!
        assertEquals("/just-a-path", req.uri.path)

        val rsp = Response(Unit)
        stream.sendResponse(rsp, true).close()

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun serveWhenRequestInResponseExtensions() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!

        val rsp = Response(Unit)
        rsp.extensions.insert(req)
        stream.sendResponse(rsp, true).close()

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun sendResetExplicitly() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.reset(1).reason(Reason.ENHANCE_YOUR_CALM))
            client.close()
        }

        val srv = handshake(io)
        launch { srv.run() }
        val (_, stream) = srv.accept()!!

        stream.sendReset(Reason.ENHANCE_YOUR_CALM)

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun sendResetExplicitlyDoesNotAffectLocalLimit() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            for (s in 1 until 9 step 2) {
                client.sendFrame(Frames.headers(s).request("GET", "https://example.com/").eos())
                client.recvFrame(Frames.reset(s).reason(Reason.INTERNAL_ERROR))
            }
            client.close()
        }

        val srv = Builder()
            .maxLocalErrorResetStreams(3)
            .handshake(io)
        launch { srv.run() }

        (1 until 9 step 2).forEach { _ ->
            val (req, stream) = srv.accept()!!
            stream.sendReset(Reason.INTERNAL_ERROR)
            // dropped at the end of the loop body
            stream.close()
            req.body.close()
        }

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun extendedConnectProtocolDisabledByDefault() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()

            assertNull(settings.isExtendedConnectProtocolEnabled)

            client.sendFrame(
                Frames.headers(1).pseudo(
                    Pseudo.request(Method.CONNECT, Uri.parse("http://bread/baguette"), "the-bread-protocol"),
                ),
            )

            client.recvFrame(Frames.reset(1).protocolError())
            client.close()
        }

        val srv = handshake(io)
        val conn = async { srv.run() }

        // poll_closed(..).expect("server")
        conn.await()
        c.join()
    }

    @Test
    fun extendedConnectProtocolEnabledDuringHandshake() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()

            assertEquals(true, settings.isExtendedConnectProtocolEnabled)

            client.sendFrame(
                Frames.headers(1).pseudo(
                    Pseudo.request(Method.CONNECT, Uri.parse("http://bread/baguette"), "the-bread-protocol"),
                ),
            )

            client.recvFrame(Frames.headers(1).response(200))
            client.close()
        }

        val builder = Builder()

        builder.enableConnectProtocol()

        val srv = builder.handshake(io)
        val conn = async { srv.run() }

        val (req, stream) = srv.accept()!!

        assertEquals(
            Protocol.fromStatic("the-bread-protocol"),
            req.extensions.get<Protocol>(),
        )

        val rsp = Response(Unit)
        stream.sendResponse(rsp, false).close()

        // poll_closed(..).expect("server")
        conn.await()
        c.join()
    }

    @Test
    fun rejectPseudoProtocolOnNonConnectRequest() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()

            assertEquals(true, settings.isExtendedConnectProtocolEnabled)

            client.sendFrame(
                Frames.headers(1).pseudo(
                    Pseudo.request(Method.GET, Uri.parse("http://bread/baguette"), "the-bread-protocol"),
                ),
            )

            client.recvFrame(Frames.reset(1).protocolError())
            client.close()
        }

        val builder = Builder()

        builder.enableConnectProtocol()

        val srv = builder.handshake(io)
        val conn = async { srv.run() }

        assertNull(srv.accept())

        // poll_closed(..).expect("server")
        conn.await()
        c.join()
    }

    @Test
    fun rejectExtendedConnectRequestWithoutScheme() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()

            assertEquals(true, settings.isExtendedConnectProtocolEnabled)

            client.sendFrame(
                Frames.headers(1).pseudo(
                    Pseudo(
                        method = Method.CONNECT,
                        path = "/",
                        protocol = "the-bread-protocol",
                    ),
                ),
            )

            client.recvFrame(Frames.reset(1).protocolError())
            client.close()
        }

        val builder = Builder()

        builder.enableConnectProtocol()

        val srv = builder.handshake(io)
        val conn = async { srv.run() }

        assertNull(srv.accept())

        // poll_closed(..).expect("server")
        conn.await()
        c.join()
    }

    @Test
    fun rejectExtendedConnectRequestWithoutPath() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()

            assertEquals(true, settings.isExtendedConnectProtocolEnabled)

            client.sendFrame(
                Frames.headers(1).pseudo(
                    Pseudo(
                        method = Method.CONNECT,
                        scheme = "https",
                        protocol = "the-bread-protocol",
                    ),
                ),
            )

            client.recvFrame(Frames.reset(1).protocolError())
            client.close()
        }

        val builder = Builder()

        builder.enableConnectProtocol()

        val srv = builder.handshake(io)
        val conn = async { srv.run() }

        assertNull(srv.accept())

        // poll_closed(..).expect("server")
        conn.await()
        c.join()
    }

    @Test
    fun rejectInformationalStatusHeaderInRequest() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            client.assertServerHandshake()

            val statusCode = 128
            assertTrue(StatusCode.fromU16(statusCode).isInformational())

            client.sendFrame(Frames.headers(1).response(statusCode))

            client.recvFrame(Frames.reset(1).protocolError())
            client.close()
        }

        val builder = Builder()
        val srv = builder.handshake(io)
        val conn = async { srv.run() }

        // poll_closed(..).expect("server")
        conn.await()
        c.join()
    }

    @Test
    fun clientDropConnectionWithoutCloseNotify() = h2Test {
        val (io, client) = mockNew()
        val c = launch {
            client.assertServerHandshake()
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/"))
            client.sendFrame(Frames.data(1, "hello"))
            client.recvFrame(Frames.headers(1).response(200))

            client.closeWithoutNotify() // Client closed without notify causing UnexpectedEof
            // end of the Rust block: the handle is dropped
            client.close()
        }

        val builder = Builder()
        builder.maxConcurrentStreams(1)

        val srv = builder.handshake(io)
        val conn = async { srv.run() }
        val (req, stream) = srv.accept()!!

        assertEquals(Method.GET, req.method)

        val rsp = Response.builder().status(200).body(Unit)
        stream.sendResponse(rsp, false).close()

        // Step the conn state forward and hitting the EOF
        // But we have no outstanding request from client to be satisfied, so we should not return
        // an error
        conn.await()
        c.join()
    }

    @Test
    fun initWindowSizeSmallerThanDefaultShouldUseDefaultBeforeAck() = h2Test {
        val (io, client) = mockNew()
        val c = launch {
            // Client can send in some data before ACK;
            // Server needs to make sure the Recv stream has default window size
            // as per https://datatracker.ietf.org/doc/html/rfc9113#name-initial-flow-control-window
            client.writePreface()
            client.send(Settings())
            assertNotNull(client.next(), "unexpected EOF")
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/"))
            client.sendFrame(Frames.data(1, "hello"))
            client.send(Settings.ack())
            client.next()
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val builder = Builder()
        builder.maxConcurrentStreams(1)
        builder.initialWindowSize(1)
        val srv = builder.handshake(io)
        val conn = async { srv.run() }
        val (req, stream) = srv.accept()!!

        assertEquals(Method.GET, req.method)

        val rsp = Response.builder().status(200).body(Unit)
        stream.sendResponse(rsp, true).close()

        // Drive the state forward
        conn.await()
        c.join()
    }

    @Test
    fun remoteResetDoesNotPanicConnectionDriver() = h2Test {
        val adversarialWire = raw(
            // Client connection preface.
            0x50, 0x52, 0x49, 0x20, 0x2a, 0x20, 0x48, 0x54, 0x54, 0x50, 0x2f, 0x32, 0x2e, 0x30, 0x0d,
            0x0a, 0x0d, 0x0a, 0x53, 0x4d, 0x0d, 0x0a, 0x0d, 0x0a,
            // SETTINGS len=0, flags=0, stream=0.
            0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
            // Unknown frame type 0x87, len=5, flags=0xc1, stream=257.
            0x00, 0x00, 0x05, 0x87, 0xc1, 0x00, 0x00, 0x01, 0x01, 0x05, 0x94, 0x05, 0x01, 0x00,
            // Unknown frame type 0xc1, len=0, flags=0x94, stream=1281.
            0x00, 0x00, 0x00, 0xc1, 0x94, 0x00, 0x00, 0x05, 0x01,
            // HEADERS len=4, flags=END_STREAM | END_HEADERS, stream=4353.
            0x00, 0x00, 0x04, 0x01, 0x05, 0x00, 0x00, 0x11, 0x01, 0x83, 0x87, 0x01, 0x00,
            // RST_STREAM len=4, flags=0x05, stream=4353.
            0x00, 0x00, 0x04, 0x03, 0x05, 0x00, 0x00, 0x11, 0x01, 0x83, 0x87, 0x01, 0x00,
            // HEADERS len=5, flags=0xf6, stream=4353.
            0x00, 0x00, 0x05, 0x01, 0xf6, 0x00, 0x00, 0x11, 0x01, 0x01, 0x94, 0x00, 0x3d, 0x01,
            // PUSH_PROMISE len=5, flags=0xf6, stream=4353.
            0x00, 0x00, 0x05, 0x05, 0xf6, 0x00, 0x00, 0x11, 0x01, 0x3d, 0x94, 0x81, 0x00, 0x95,
            // HEADERS len=0, flags=END_STREAM | END_HEADERS, stream=4353.
            0x00, 0x00, 0x00, 0x01, 0x05, 0x00, 0x00, 0x11, 0x01,
        )

        val (clientIo, serverIo) = testStreamPair(256 * 1024)
        // A "panic" is any exception other than an H2Error: it fails this task and so the test.
        val serverTask = async {
            val server = try {
                handshake(serverIo)
            } catch (e: H2Error) {
                return@async
            }
            val conn = driveCatching(server)

            // `while let Some(result) = server.next().await { let _ = result; }`
            // ⚖️ adapted: accept() throws the connection error on every call once the connection failed (the
            // reference yields it once, then None), so the loop stops at the first error.
            while (true) {
                val result = try {
                    server.accept()
                } catch (e: H2Error) {
                    break
                } ?: break
                // `let _ = result;` drops the handles
                result.second.close()
                result.first.body.close()
            }
            conn.await()
        }

        clientIo.write(Buffer().also { it.writeBytes(adversarialWire) })
        clientIo.flush()
        clientIo.close()

        withTimeout(1000) { serverTask.await() } // "server task timed out" / "server task panicked"
    }
}
