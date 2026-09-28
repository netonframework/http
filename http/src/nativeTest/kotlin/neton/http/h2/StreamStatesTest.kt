// Ported from h2 0.4.19 tests/h2-tests/tests/stream_states.rs (34 tests).
//
// The reference file also holds a commented-out `send_data_after_headers_eos` and `exceed_max_streams` (inside
// `/* */`); they are not tests there and are not ported.

package neton.http.h2

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import neton.http.Method
import neton.http.Request
import neton.http.StatusCode
import neton.http.h2.frame.DEFAULT_INITIAL_WINDOW_SIZE
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Settings
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

class StreamStatesTest {
    /**
     * Drives a connection in the background (the reference's `h2.drive(..)` / `conn.run(..)` followed later by
     * `h2.await`). The failure is captured in the result rather than thrown, so a connection error expected by the
     * test does not cancel the test scope; await it where the reference awaits the connection.
     */
    private fun CoroutineScope.spawnConn(run: suspend () -> Unit): Deferred<Result<Unit>> =
        async { runCatching { run() } }

    /** `body.try_collect()`: every DATA payload of the body. */
    private suspend fun collect(body: RecvStream): List<Bytes> {
        val out = ArrayList<Bytes>()
        while (true) out.add(body.data() ?: break)
        return out
    }

    @Test
    fun sendRecvHeadersOnly() = h2Test {
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
            .read(raw(0, 0, 1, 1, 5, 0, 0, 0, 1, 0x89))
            .build()

        val (client, h2) = neton.http.h2.client.handshake(mock)

        // Send the request
        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (response, stream) = client.sendRequest(request, true)
        stream.close()

        val conn = spawnConn { h2.run() }
        val resp = response.await()
        assertEquals(StatusCode.NO_CONTENT, resp.status)

        conn.await().getOrThrow()
        mock.assertDone()
    }

    @Test
    fun sendRecvData() = h2Test {
        val mock = MockIoBuilder()
            .handshake()
            .write(
                raw(
                    // POST /
                    0, 0, 16, 1, 4, 0, 0, 0, 1, 131, 135, 65, 139, 157, 41, 172, 75, 143, 168, 233, 25, 151,
                    33, 233, 132,
                ),
            )
            .write(Frames.SETTINGS_ACK)
            .write(
                raw(
                    // DATA
                    0, 0, 5, 0, 1, 0, 0, 0, 1, 104, 101, 108, 108, 111,
                ),
            )
            // Read response
            .read(
                raw(
                    // HEADERS
                    0, 0, 1, 1, 4, 0, 0, 0, 1, 136, // DATA
                    0, 0, 5, 0, 1, 0, 0, 0, 1, 119, 111, 114, 108, 100,
                ),
            )
            .build()

        val (client, h2) = neton.http.h2.client.Builder().handshake(mock)

        val request = Request.builder()
            .method(Method.POST)
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (response, stream0) = client.sendRequest(request, false)

        // Reserve send capacity
        stream0.reserveCapacity(5)

        val conn = spawnConn { h2.run() }
        val stream = waitForCapacity(stream0, 5)

        // Send the data
        stream.sendData(bytes("hello"), true)

        // Get the response
        val resp = response.await()
        assertEquals(StatusCode.OK, resp.status)

        // Take the body
        val body = resp.body

        // Wait for all the data frames to be received
        val chunks = collect(body)

        // One byte chunk
        assertEquals(1, chunks.size)

        assertEquals(bytes("world"), chunks[0])

        // The H2 connection is closed
        conn.await().getOrThrow()
        mock.assertDone()
    }

    @Test
    fun recvIgnoresEmptyDataWithoutEndStream() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200))
            repeat(49) {
                srv.sendFrame(Frames.data(1, ""))
            }
            repeat(50) {
                srv.sendFrame(Frames.data(1, byteArrayOf(1, 0)).padded())
            }
            srv.sendFrame(Frames.data(1, "hello").eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (response0, stream) = client.sendRequest(request, true)
        stream.close()
        val conn = spawnConn { h2.run() }
        val response = response0.await()
        val body = response.body
        val chunks = collect(body)
        body.close()

        assertEquals(listOf(bytes("hello")), chunks)
        client.close()
        conn.await().getOrThrow()

        mock.join()
    }

    @Test
    fun tooManyEmptyDataFramesSendsGoaway() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200))

            repeat(101) {
                srv.sendFrame(Frames.data(1, ""))
            }

            srv.recvFrame(Frames.goAway(0).calm().data("too_many_data_frames"))
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (response0, stream) = client.sendRequest(request, true)
        stream.close()
        val conn = spawnConn { h2.run() }
        val response = response0.await()
        @Suppress("UNUSED_VARIABLE")
        val body = response.body
        val err = assertIs<H2Error>(conn.await().exceptionOrNull())
        assertEquals(Reason.ENHANCE_YOUR_CALM, err.reason())

        mock.join()
    }

    @Test
    fun tooManyPaddedEmptyDataFramesSendsGoaway() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200))

            // One byte describing the padding length followed by one byte of
            // padding leaves an empty application payload after decoding.
            repeat(101) {
                srv.sendFrame(Frames.data(1, byteArrayOf(1, 0)).padded())
            }

            srv.recvFrame(Frames.goAway(0).calm().data("too_many_data_frames"))
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (response0, stream) = client.sendRequest(request, true)
        stream.close()
        val conn = spawnConn { h2.run() }
        val response = response0.await()
        @Suppress("UNUSED_VARIABLE")
        val body = response.body
        val err = assertIs<H2Error>(conn.await().exceptionOrNull())
        assertEquals(Reason.ENHANCE_YOUR_CALM, err.reason())

        mock.join()
    }

    @Test
    fun tooManySmallDataFramesSendsGoaway() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://http2.akamai.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200))

            repeat(50) {
                srv.sendFrame(Frames.data(1, "a"))
            }

            // A good-sized frame replenishes some of the budget.
            srv.sendFrame(Frames.data(1, ByteArray(2048)))

            // One-byte frames still impose almost all of the overhead of an empty
            // frame. Even after replenishment, the 101st frame exhausts the
            // bounded budget.
            repeat(101) {
                srv.sendFrame(Frames.data(1, "a"))
            }

            srv.recvFrame(Frames.goAway(0).calm().data("too_many_data_frames"))
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (response0, stream) = client.sendRequest(request, true)
        stream.close()
        val conn = spawnConn { h2.run() }
        val response = response0.await()
        @Suppress("UNUSED_VARIABLE")
        val body = response.body
        val err = assertIs<H2Error>(conn.await().exceptionOrNull())
        assertEquals(Reason.ENHANCE_YOUR_CALM, err.reason())

        mock.join()
    }

    @Test
    fun manySmallFinalDataFramesDoNotExhaustBudget() = h2Test {
        val numStreams = 200

        val (io, srv) = mockNew()
        val done = CompletableDeferred<Unit>()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())

            for (i in 0 until numStreams) {
                val streamId = 1 + i * 2
                srv.recvFrame(
                    Frames.headers(streamId)
                        .request("GET", "https://http2.akamai.com/")
                        .eos(),
                )
            }

            for (i in 0 until numStreams) {
                val streamId = 1 + i * 2
                srv.sendFrame(Frames.headers(streamId).response(200))
                srv.sendFrame(Frames.data(streamId, "a").eos())
            }

            done.await()
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)

        // `tokio::spawn(requests)`
        launch {
            val responses = ArrayList<neton.http.h2.client.ResponseFuture>()
            repeat(numStreams) {
                client.ready()
                val request = Request.builder()
                    .uri("https://http2.akamai.com/")
                    .body(Unit)
                val (response, stream) = client.sendRequest(request, true)
                stream.close()
                responses.add(response)
            }

            // Wait for every response without polling any response body. This
            // ensures all final DATA frames can be buffered concurrently.
            val received = ArrayList<neton.http.Response<RecvStream>>()
            for (response in responses) {
                received.add(response.await())
            }
            assertEquals(numStreams, received.size)
            done.complete(Unit)
            // The end of the reference's `requests` block drops the bodies and the client.
            for (r in received) r.body.close()
            client.close()
        }
        h2.run()

        mock.join()
    }

    @Test
    fun droppingBufferedDataFramesReleasesBudget() = h2Test {
        val numStreams = 200

        val (io, srv) = mockNew()
        val done = CompletableDeferred<Unit>()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())

            for (i in 0 until numStreams) {
                val streamId = 1 + i * 2
                srv.recvFrame(
                    Frames.headers(streamId)
                        .request("GET", "https://http2.akamai.com/")
                        .eos(),
                )
                srv.sendFrame(Frames.headers(streamId).response(409))
                srv.sendFrame(Frames.data(streamId, "a"))
                srv.sendFrame(Frames.data(streamId, "b").eos())
            }

            done.await()
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)

        // `tokio::spawn(requests)`
        launch {
            repeat(numStreams) {
                client.ready()
                val request = Request.builder()
                    .uri("https://http2.akamai.com/")
                    .body(Unit)
                val (future, stream) = client.sendRequest(request, true)
                val response = future.await()
                stream.close()
                assertEquals(StatusCode.CONFLICT, response.status)

                val body = response.body
                while (body.flowControl().usedCapacity() < 2) {
                    yield()
                }
                body.close()
            }
            done.complete(Unit)
            client.close()
        }
        h2.run()

        mock.join()
    }

    @Test
    fun sendHeadersRecvDataSingleFrame() = h2Test {
        val mock = MockIoBuilder()
            .handshake()
            // Write GET /
            .write(
                raw(
                    0, 0, 16, 1, 5, 0, 0, 0, 1, 130, 135, 65, 139, 157, 41, 172, 75, 143, 168, 233, 25,
                    151, 33, 233, 132,
                ),
            )
            .write(Frames.SETTINGS_ACK)
            // Read response
            .read(
                raw(
                    0, 0, 1, 1, 4, 0, 0, 0, 1, 136, 0, 0, 5, 0, 0, 0, 0, 0, 1, 104, 101, 108, 108, 111, 0,
                    0, 5, 0, 1, 0, 0, 0, 1, 119, 111, 114, 108, 100,
                ),
            )
            .build()

        val (client, h2) = neton.http.h2.client.handshake(mock)

        // Send the request
        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (response, stream) = client.sendRequest(request, true)
        stream.close()

        val conn = spawnConn { h2.run() }
        val resp = response.await()
        assertEquals(StatusCode.OK, resp.status)

        // Take the body
        val body = resp.body

        // Wait for all the data frames to be received
        val chunks = collect(body)

        // Two data frames
        assertEquals(2, chunks.size)

        assertEquals(bytes("hello"), chunks[0])
        assertEquals(bytes("world"), chunks[1])

        // The H2 connection is closed
        conn.await().getOrThrow()
        mock.assertDone()
    }

    @Test
    fun closedStreamsAreReleased() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(204).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.get("https://example.com/").body(Unit)

        // Send request
        val (future, stream) = client.sendRequest(request, true)
        stream.close()
        spawnConn { h2.run() }
        val response = future.await()
        assertEquals(StatusCode.NO_CONTENT, response.status)

        // There are no active streams
        assertEquals(0, client.numActiveStreams())

        // The response contains a handle for the body. This keeps the
        // stream wired.
        assertEquals(1, client.numWiredStreams())

        val body = response.body
        assertTrue(body.isEndStream)
        body.close()

        // The stream state is now free
        assertEquals(0, client.numWiredStreams())

        mock.join()
    }

    @Test
    fun resetStreamsDontGrowMemoryContinuously() = h2Test {
        val (io, client) = mockNew()

        val n = 50
        val max = 20

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            for (id in 1 until n * 2 step 2) {
                client.sendFrame(Frames.headers(id).request("GET", "https://a.b/").eos())
                client.sendFrame(Frames.reset(id).protocolError())
            }

            assertNotNull(
                withTimeoutOrNull(1_000) {
                    client.recvFrame(
                        Frames.goAway(max * 2 + 1)
                            .data("too_many_resets")
                            .calm(),
                    )
                },
                "client goaway",
            )
            client.close()
        }

        val srv = neton.http.h2.server.Builder()
            .maxPendingAcceptResetStreams(max)
            .handshake(io)

        val closed = runCatching { srv.run() }
        assertTrue(closed.isFailure, "server should error")
        // specifically, not 50;
        assertEquals(21, srv.numWiredStreams())

        c.join()
    }

    @Test
    fun goAwayWithPendingAccepting() = h2Test {
        val (io, client) = mockNew()

        val sentGoAway = CompletableDeferred<Unit>()
        val recvGoAway = CompletableDeferred<Unit>()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())

            client.sendFrame(Frames.headers(1).request("GET", "https://baguette/").eos())

            client.sendFrame(Frames.headers(3).request("GET", "https://campagne/").eos())
            client.sendFrame(Frames.goAway(1).protocolError())

            sentGoAway.complete(Unit)

            recvGoAway.await()
            client.close()
        }

        val srv = neton.http.h2.server.Builder()
            .maxPendingAcceptResetStreams(1)
            .handshake(io)
        spawnConn { srv.run() }

        // (req_1, send_response_1), kept alive until the end
        val accepted1 = srv.accept()
        assertNotNull(accepted1)

        // `poll_fn(|cx| srv.poll_closed(cx)).drive(sent_go_away_rx)`: the connection keeps being driven meanwhile.
        sentGoAway.await()

        // (req_2, send_response_2), kept alive until the end
        val accepted2 = srv.accept()
        assertNotNull(accepted2)

        recvGoAway.complete(Unit)

        c.join()
    }

    @Test
    fun pendingAcceptResetStreamsDecrementToo() = h2Test {
        val (io, client) = mockNew()

        // If it didn't decrement internally, this would eventually get
        // the count over MAX.
        val m = 2
        val n = 5
        val max = 6

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            var id = 1
            repeat(m) {
                repeat(n) {
                    client.sendFrame(Frames.headers(id).request("GET", "https://a.b/").eos())
                    client.sendFrame(Frames.reset(id).protocolError())
                    id += 2
                }
                delay(50)
            }
            client.close()
        }

        val srv = neton.http.h2.server.Builder()
            .maxPendingAcceptResetStreams(max)
            .handshake(io)
        val conn = spawnConn { srv.run() }

        // `while let Some(Ok(_)) = srv.accept().await {}`
        while (true) {
            val (req, respond) = runCatching { srv.accept() }.getOrNull() ?: break
            req.body.close()
            respond.close()
        }

        conn.await().getOrThrow()

        c.join()
    }

    @Test
    fun errorsIfRecvFrameExceedsMaxFrameSize() = h2Test {
        val (io, srv) = mockNew()

        // a bad peer
        srv.codec.setMaxSendFrameSize(16_384 * 4)

        val mock = launch {
            srv.assertClientHandshake()
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(16_385)).eos())
            srv.recvFrame(Frames.goAway(0).frameSize())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val req = launch {
            val resp = client.get("https://example.com/").await()
            assertEquals(StatusCode.OK, resp.status)
            val body = resp.body
            val err = assertFailsWith<H2Error> { concat(body) }
            assertEquals(
                "connection error detected: frame with invalid size",
                err.message,
            )
            body.close()
            client.close()
        }

        // client should see a conn error
        val err = assertIs<H2Error>(runCatching { h2.run() }.exceptionOrNull())
        assertEquals(
            "connection error detected: frame with invalid size",
            err.message,
        )
        req.join()

        mock.join()
    }

    @Test
    fun configureMaxFrameSize() = h2Test {
        val (io, srv) = mockNew()

        // a good peer
        srv.codec.setMaxSendFrameSize(16_384 * 2)

        val mock = launch {
            srv.assertClientHandshake()
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(16_385)).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.Builder()
            .maxFrameSize(16_384 * 2)
            .handshake(io)

        val req = launch {
            val resp = client.get("https://example.com/").await()
            assertEquals(StatusCode.OK, resp.status)
            val body = resp.body
            val buf = concat(body)
            assertEquals(16_385, buf.size)
            body.close()
            client.close()
        }

        h2.run()
        req.join()

        mock.join()
    }

    @Test
    fun recvGoawayFinishesProcessedStreams() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.recvFrame(
                Frames.headers(3)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.goAway(1))
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)).eos())
            // expecting a goaway of 0, since server never initiated a stream
            srv.recvFrame(Frames.goAway(0))
            //.close();
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val clientClone = client.clone()
        val req1 = launch {
            val resp = clientClone.get("https://example.com").await()
            assertEquals(StatusCode.OK, resp.status)
            val body = resp.body
            val buf = concat(body)
            assertEquals(16_384, buf.size)
            body.close()
            clientClone.close()
        }

        // this request will trigger a goaway
        val req2 = launch {
            val err = assertFailsWith<H2Error> { client.get("https://example.com/").await() }
            assertEquals(
                "connection error received: not a result of an error",
                err.message,
            )
            client.close()
        }

        h2.run()
        req1.join()
        req2.join()

        mock.join()
    }

    @Test
    fun recvGoawayWithHigherLastProcessedId() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.goAway(1))
            // a bigger goaway? kaboom
            srv.sendFrame(Frames.goAway(3))
            // expecting a goaway of 0, since server never initiated a stream
            srv.recvFrame(Frames.goAway(0).protocolError())
            //.close();
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val response = client.get("https://example.com")
        spawnConn { conn.run() }
        val err = assertFailsWith<H2Error>("client should error") { response.await() }
        assertEquals(Reason.PROTOCOL_ERROR, err.reason())

        mock.join()
    }

    @Test
    fun recvNextStreamIdUpdatedByMalformedHeaders() = h2Test {
        val (io, client) = mockNew()

        val badAuth = "not:a/good authority"
        val badHeaders = Frames.headers(1)
            .request("GET", "https://example.com/")
            .eos()
            .frame
        badHeaders.pseudo.authority = badAuth

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            // bad headers -- should error.
            client.sendFrame(badHeaders)
            client.recvFrame(Frames.reset(1).protocolError())
            // this frame is good, but the stream id should already have been incr'd
            client.sendFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            client.recvFrame(Frames.goAway(1).protocolError())
            client.close()
        }

        val srv = neton.http.h2.server.Builder()
            // forget the bad stream immediately
            .maxConcurrentResetStreams(0)
            .handshake(io)
        spawnConn { srv.run() }
        val err = assertFailsWith<H2Error> { srv.accept() }
        assertEquals(Reason.PROTOCOL_ERROR, err.reason())

        c.join()
    }

    @Test
    fun skippedStreamIdsAreImplicitlyClosed() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(5)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            // send the response on a lower-numbered stream, which should be
            // implicitly closed.
            srv.sendFrame(Frames.headers(3).response(299))
            // however, our client choose to send a RST_STREAM because it
            // can't tell if it had previously reset '3'.
            srv.recvFrame(Frames.reset(3).streamClosed())
            srv.sendFrame(Frames.headers(5).response(200).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.Builder()
            .initialStreamId(5)
            .handshake(io)

        val conn = spawnConn { h2.run() }
        // `req` is an `async move` block: the response and the client are dropped at its end.
        val res = client.get("https://example.com/").await()
        assertEquals(StatusCode.OK, res.status)
        res.body.close()
        client.close()

        conn.await().getOrThrow()

        mock.join()
    }

    @Test
    fun sendRstStreamAllowsRecvData() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200))
            srv.recvFrame(Frames.reset(1).cancel())
            // sending frames after canceled!
            //   note: sending 2 to consume 50% of connection window
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)).eos())
            // make sure we automatically free the connection window
            srv.recvFrame(Frames.windowUpdate(0, 16_384 * 2))
            // do a pingpong to ensure no other frames were sent
            srv.pingPong(ByteArray(8) { 1 })
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val connTask = spawnConn { conn.run() }

        val resp = client.get("https://example.com/").await()
        assertEquals(StatusCode.OK, resp.status)
        // drop resp will send a reset
        resp.body.close()

        connTask.await().getOrThrow()
        client.close()

        mock.join()
    }

    @Test
    fun sendRstStreamAllowsRecvTrailers() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.recvFrame(Frames.reset(1).cancel())
            // sending frames after canceled!
            srv.sendFrame(Frames.headers(1).field("foo", "bar").eos())
            // do a pingpong to ensure no other frames were sent
            srv.pingPong(ByteArray(8) { 1 })
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val connTask = spawnConn { conn.run() }

        val resp = client.get("https://example.com/").await()
        assertEquals(StatusCode.OK, resp.status)
        // drop resp will send a reset
        resp.body.close()

        connTask.await().getOrThrow()
        client.close()

        mock.join()
    }

    @Test
    fun rstStreamExpires() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.recvFrame(Frames.reset(1).cancel())
            // wait till after the configured duration
            idleMs(15)
            srv.pingPong(ByteArray(8) { 1 })
            // sending frame after canceled!
            srv.sendFrame(Frames.data(1, ByteArray(16_384)).eos())
            // window capacity is returned
            srv.recvFrame(Frames.windowUpdate(0, 16_384 * 2))
            // and then stream error
            srv.recvFrame(Frames.reset(1).streamClosed())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.Builder()
            .resetStreamDuration(10.milliseconds)
            .handshake(io)

        // no connection error should happen
        val connTask = spawnConn { conn.run() }

        val resp = client.get("https://example.com/").await()
        assertEquals(StatusCode.OK, resp.status)
        // drop resp will send a reset
        resp.body.close()

        connTask.await().getOrThrow()
        client.close()

        mock.join()
    }

    @Test
    fun rstStreamMax() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.recvFrame(
                Frames.headers(3)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(16)))
            srv.sendFrame(Frames.headers(3).response(200))
            srv.sendFrame(Frames.data(3, ByteArray(16)))
            srv.recvFrame(Frames.reset(1).cancel())
            srv.recvFrame(Frames.reset(3).cancel())
            // sending frame after canceled!
            // olders streams trump newer streams
            // 1 is still being ignored
            srv.sendFrame(Frames.data(1, ByteArray(16)).eos())
            // ping pong to be sure of no goaway
            srv.pingPong(ByteArray(8) { 1 })
            // 3 has been evicted, will get a reset
            srv.sendFrame(Frames.data(3, ByteArray(16)).eos())
            srv.recvFrame(Frames.reset(3).streamClosed())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.Builder()
            .maxConcurrentResetStreams(1)
            .handshake(io)
        val clientClone = client.clone()

        // no connection error should happen
        val connTask = spawnConn { conn.run() }

        val req1 = launch {
            val resp = clientClone.get("https://example.com/").await()
            assertEquals(StatusCode.OK, resp.status, "response1")
            // drop resp will send a reset
            resp.body.close()
            clientClone.close()
        }

        val req2 = launch {
            val resp = client.get("https://example.com/").await()
            assertEquals(StatusCode.OK, resp.status, "response2")
            // drop resp will send a reset
            resp.body.close()
        }

        req1.join()
        req2.join()
        connTask.await().getOrThrow()
        client.close()

        mock.join()
    }

    @Test
    fun reservedStateRecvWindowUpdate() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.pushPromise(1, 2).request("GET", "https://example.com/push"))
            // it'd be weird to send a window update on a push promise,
            // since the client can't send us data, but whatever. The
            // point is that it's allowed, so we're testing it.
            srv.sendFrame(Frames.windowUpdate(2, 128))
            srv.sendFrame(Frames.headers(1).response(200).eos())
            // ping pong to ensure no goaway
            srv.pingPong(ByteArray(8) { 1 })
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val connTask = spawnConn { conn.run() }

        // `req` is an `async move` block: the response and the client are dropped at its end.
        val resp = client.get("https://example.com/").await()
        assertEquals(StatusCode.OK, resp.status)
        resp.body.close()
        client.close()

        connTask.await().getOrThrow()

        mock.join()
    }

    @Test
    fun recvEndStreamSurvivesReset() = h2Test {
        assertRecvEndStreamSurvivesReset(Reason.NO_ERROR)
    }

    @Test
    fun recvEndStreamPreservesResetReason() = h2Test {
        assertRecvEndStreamSurvivesReset(Reason.INTERNAL_ERROR)
    }

    private suspend fun CoroutineScope.assertRecvEndStreamSurvivesReset(resetReason: Reason) {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.sendFrame(Frames.reset(1).reason(resetReason))
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)
        val (future, sendStream) = client.sendRequest(request, false)

        // Process the reset before polling the response future.
        spawnConn { conn.run() }
        val reason = sendStream.awaitReset()
        // The stream was moved into the driven `poll_fn` closure, dropped with it.
        sendStream.close()
        assertEquals(resetReason, reason)

        val response = future.await()
        assertTrue(response.body.isEndStream)

        val body = response.body
        assertNull(body.data())
        assertNull(body.trailers())

        mock.join()
    }

    @Test
    fun rstWhileClosing() = h2Test {
        // Test to reproduce panic in issue #246 --- receipt of a RST_STREAM frame
        // on a stream in the Half Closed (remote) state with a queued EOS causes
        // a panic.
        val (io, srv) = mockNew()

        // Rendezvous when we've queued a trailers frame
        val tx = CompletableDeferred<Unit>()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.headers(1).eos())
            // Idling for a moment here is necessary to ensure that the client
            // enqueues its TRAILERS frame *before* we send the RST_STREAM frame
            // which causes the panic.
            tx.await()
            // Send the RST_STREAM frame which causes the client to panic.
            srv.sendFrame(Frames.reset(1).cancel())
            // ⚖️ adapted: in the reference the connection is not polled between the client queueing its trailers and
            // the RST_STREAM arriving, so the trailers are dropped by the reset. Here the connection runs by itself
            // and has usually written them already: accept them. They end the stream, which is released once they are
            // written; with no handle left the client then goes away at once, as the reference's connection does when
            // its last stream is released during a poll ("wake again"), before it reads the PING: GOAWAY, no pong.
            // (The reset of a stream with queued frames is covered by rstWithBufferedData and the state tests.)
            srv.sendFrame(Frames.ping(ByteArray(8) { 1 }))
            val f = srv.next()
            if (f is Headers) {
                assertFrameEq(f, Frames.headers(1).eos())
                assertFrameEq(srv.next() ?: fail("unexpected EOF"), Frames.goAway(0).noError())
                srv.close()
                return@launch
            }
            assertFrameEq(f ?: fail("unexpected EOF"), Frames.ping(ByteArray(8) { 1 }).pong())
            srv.recvFrame(Frames.goAway(0).noError())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        val connTask = spawnConn { conn.run() }
        // The request should be left streaming.
        // (`req` is an `async move` block: the response and the client are dropped at its end.)
        val (future, stream) = client.sendRequest(request, false)
        // on receipt of an EOS response from the server, transition
        // the stream Open => Half Closed (remote).
        val resp = future.await()
        assertEquals(StatusCode.OK, resp.status)
        resp.body.close()
        client.close()

        // Enqueue trailers frame.
        runCatching { stream.sendTrailers(headerMapOf()) }
        // Signal the server mock to send RST_FRAME
        tx.complete(Unit)
        stream.close()
        yieldOnce()
        // yield once to allow the server mock to be polled
        // before the conn flushes its buffer
        connTask.await().getOrThrow()

        mock.join()
    }

    @Test
    fun rstWithBufferedData() = h2Test {
        // Data is buffered in `FramedWrite` and the stream is reset locally before
        // the data is fully flushed. Given that resetting a stream requires
        // clearing all associated state for that stream, this test ensures that the
        // buffered up frame is correctly handled.

        // This allows the settings + headers frame through
        val (io, srv) = mockNewWithWriteCapacity(73)

        // Synchronize the client / server on response
        val tx = CompletableDeferred<Unit>()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            srv.bufferBytes(128)
            srv.sendFrame(Frames.headers(1).response(204).eos())
            srv.sendFrame(Frames.reset(1).cancel())
            tx.await()
            srv.unboundedBytes()
            srv.recvFrame(Frames.data(1, ByteArray(16_384)))
            srv.close()
        }

        // A large body
        val body = ByteArray(2 * DEFAULT_INITIAL_WINDOW_SIZE)

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        // Send the request
        val (resp, stream) = client.sendRequest(request, false)

        // Send the data
        stream.sendData(Bytes.wrap(body), true)

        val connTask = spawnConn { conn.run() }
        runCatching { resp.await() }.getOrNull()?.body?.close()
        tx.complete(Unit)
        connTask.await().getOrThrow()

        mock.join()
    }

    @Test
    fun errWithBufferedData() = h2Test {
        // Data is buffered in `FramedWrite` and the stream is reset locally before
        // the data is fully flushed. Given that resetting a stream requires
        // clearing all associated state for that stream, this test ensures that the
        // buffered up frame is correctly handled.

        // This allows the settings + headers frame through
        val (io, srv) = mockNewWithWriteCapacity(73)

        // Synchronize the client / server on response
        val tx = CompletableDeferred<Unit>()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            srv.bufferBytes(128)
            srv.sendFrame(Frames.headers(1).response(204).eos())
            // Send invalid data
            srv.sendBytes(raw(0, 0, 0, 0, 0, 0, 0, 0, 0))
            tx.await()
            srv.unboundedBytes()
            srv.recvFrame(Frames.data(1, ByteArray(16_384)))
            srv.close()
        }

        // A large body
        val body = ByteArray(2 * DEFAULT_INITIAL_WINDOW_SIZE)

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        // Send the request
        val (resp, stream) = client.sendRequest(request, false)

        // Send the data
        stream.sendData(Bytes.wrap(body), true)

        client.close()
        // `try_join(conn, resp)`: the first error wins; with a response, the connection's result decides.
        // ⚖️ adapted: `try_join` drops the connection future when it returns; here the connection keeps running in
        // the background until the test ends (a Kotlin connection cannot be dropped without cancelling its run()).
        val connTask = spawnConn { conn.run() }
        val respResult = runCatching { resp.await() }
        respResult.getOrNull()?.body?.close()
        val res = if (respResult.isFailure) respResult.map { } else connTask.await()
        assertTrue(res.isFailure)
        tx.complete(Unit)

        mock.join()
    }

    @Test
    fun sendErrWithBufferedData() = h2Test {
        // Data is buffered in `FramedWrite` and the stream is reset locally before
        // the data is fully flushed. Given that resetting a stream requires
        // clearing all associated state for that stream, this test ensures that the
        // buffered up frame is correctly handled.

        // This allows the settings + headers frame through
        val (io, srv) = mockNewWithWriteCapacity(73)

        // Synchronize the client / server on response
        val tx = CompletableDeferred<Unit>()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            srv.bufferBytes(128)
            srv.sendFrame(Frames.headers(1).response(204).eos())
            tx.await()
            srv.unboundedBytes()
            srv.recvFrame(Frames.data(1, ByteArray(16_384)))
            srv.recvFrame(Frames.reset(1).cancel())
            srv.recvFrame(Frames.goAway(0).noError())
            srv.close()
        }

        // A large body
        val body = ByteArray(2 * DEFAULT_INITIAL_WINDOW_SIZE)

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .method(Method.POST)
            .uri("https://example.com/")
            .body(Unit)

        // Send the request
        val (resp, stream) = client.sendRequest(request, false)

        // Send the data
        stream.sendData(Bytes.wrap(body), true)

        // Hack to drive the connection, trying to flush data
        // ⚖️ adapted: the reference polls the connection future once (`lazy(|cx| conn.poll_unpin(cx))`); here the
        // connection starts running in the background and the test yields once to let it make progress. The steps
        // below up to `resp.await()` do not suspend, so the connection does not run in between, as in the reference.
        val connTask = spawnConn { conn.run() }
        yieldOnce()
        if (connTask.isCompleted) connTask.await().getOrThrow()

        // Send a reset
        stream.sendReset(Reason.CANCEL)
        stream.close()
        client.close()
        runCatching { resp.await() }.getOrNull()?.body?.close()
        tx.complete(Unit)
        connTask.await().getOrThrow()

        mock.join()
    }

    @Test
    fun srvWindowUpdateOnLowerStreamId() = h2Test {
        // See https://github.com/hyperium/h2/issues/208
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(7)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(
                Frames.pushPromise(7, 2).request("GET", "https://http2.akamai.com/style.css"),
            )
            srv.sendFrame(Frames.headers(7).eos())
            srv.recvFrame(Frames.reset(2).cancel())
            srv.sendFrame(Frames.windowUpdate(5, 66666))
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.Builder()
            .initialStreamId(7)
            .handshake(io)
        val request = Request.builder()
            .method("GET")
            .uri("https://example.com/")
            .body(Unit)

        val conn = spawnConn { h2.run() }
        val (future, stream) = client.sendRequest(request, true)
        val resp = future.await()
        stream.close()
        assertEquals(StatusCode.OK, resp.status)
        resp.body.close()

        println("RESPONSE DONE")
        conn.await().getOrThrow()
        client.close()

        mock.join()
    }

    // See https://github.com/hyperium/h2/issues/570
    @Test
    fun resetNewStreamBeforeSend() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(
                Frames.headers(1)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(1).response(200).eos())
            // Send unexpected headers, that depends on itself, causing a framing error.
            srv.sendBytes(
                raw(
                    0, 0, 0x6, // len
                    0x1, // type (headers)
                    0x25, // flags (eos, eoh, pri)
                    0, 0, 0, 0x3, // stream id
                    0, 0, 0, 0x3, // dependency
                    2, // weight
                    0x88, // HPACK :status=200
                ),
            )
            srv.recvFrame(Frames.reset(3).protocolError())
            srv.recvFrame(
                Frames.headers(5)
                    .request("GET", "https://example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(5).response(200).eos())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val connTask = spawnConn { conn.run() }
        val resp1 = client.get("https://example.com/").await()
        assertEquals(StatusCode.OK, resp1.status)

        // req number 2
        val resp2 = client.get("https://example.com/").await()
        assertEquals(StatusCode.OK, resp2.status)
        connTask.await().getOrThrow()

        mock.join()
    }

    @Test
    fun explicitResetWithMaxConcurrentStream() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake(Frames.settings().maxConcurrentStreams(1))
            assertFrameEq(settings, Settings())

            srv.recvFrame(Frames.headers(1).request("POST", "https://www.example.com/"))
            srv.sendFrame(Frames.headers(1).response(200))

            srv.recvFrame(Frames.reset(1).cancel())

            srv.recvFrame(
                Frames.headers(3)
                    .request("POST", "https://www.example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(3).response(200))
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)

        val conn: Deferred<Result<Unit>>
        run {
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://www.example.com/")
                .body(Unit)

            val (future, stream) = client.sendRequest(request, false)

            conn = spawnConn { h2.run() }
            run {
                val resp = future.await()
                assertEquals(StatusCode.OK, resp.status)
                resp.body.close()
            }

            stream.sendReset(Reason.CANCEL)
            stream.close()
        }

        run {
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://www.example.com/")
                .body(Unit)

            val (future, stream) = client.sendRequest(request, true)
            stream.close()

            run {
                val resp = future.await()
                assertEquals(StatusCode.OK, resp.status)
                resp.body.close()
            }
        }

        conn.await().getOrThrow()

        mock.join()
    }

    @Test
    fun implicitCancelWithMaxConcurrentStream() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake(Frames.settings().maxConcurrentStreams(1))
            assertFrameEq(settings, Settings())

            srv.recvFrame(Frames.headers(1).request("POST", "https://www.example.com/"))
            srv.sendFrame(Frames.headers(1).response(200))

            srv.recvFrame(Frames.reset(1).cancel())

            srv.recvFrame(
                Frames.headers(3)
                    .request("POST", "https://www.example.com/")
                    .eos(),
            )
            srv.sendFrame(Frames.headers(3).response(200))
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)

        val conn: Deferred<Result<Unit>>
        run {
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://www.example.com/")
                .body(Unit)

            val (future, stream) = client.sendRequest(request, false)

            conn = spawnConn { h2.run() }
            run {
                val resp = future.await()
                assertEquals(StatusCode.OK, resp.status)
                resp.body.close()
            }

            // This implicitly resets the stream with CANCEL.
            stream.close()
        }

        run {
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://www.example.com/")
                .body(Unit)

            val (future, stream) = client.sendRequest(request, true)
            stream.close()

            run {
                val resp = future.await()
                assertEquals(StatusCode.OK, resp.status)
                resp.body.close()
            }
        }

        conn.await().getOrThrow()

        mock.join()
    }
}
