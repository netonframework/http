// Ported from h2 0.4.19 tests/h2-tests/tests/flow_control.rs (28 tests: the second half, from
// `recv_no_init_window_then_receive_some_init_window` to the end of the file).
package neton.http.h2

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import neton.http.Method
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.h2.frame.DEFAULT_INITIAL_WINDOW_SIZE
import neton.http.h2.frame.Data
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Ping as PingFrame
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.Settings
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class FlowControlTestB {
    /**
     * `util::wait_for_capacity`: waits until the stream has at least [target] capacity. Should only be called after a
     * non-0 capacity was requested for the stream.
     */
    private suspend fun waitForCapacity(stream: SendStream, target: Int) {
        while (true) {
            assertNotNull(stream.awaitCapacity(), "poll_capacity returned None")
            val act = stream.capacity()
            // If a non-0 capacity was requested for the stream before calling
            // wait_for_capacity, then poll_capacity should return Pending
            // until there is a non-0 capacity.
            assertNotEquals(0, act)
            if (act >= target) return
        }
    }

    @Test
    fun recvNoInitWindowThenReceiveSomeInitWindow() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake(Frames.settings().initialWindowSize(0))
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            idleMs(100)
            srv.sendFrame(Frames.settings().initialWindowSize(10))
            srv.recvFrame(Frames.settingsAck())
            srv.recvFrame(Frames.data(1, "hello worl"))
            idleMs(100)
            srv.sendFrame(Frames.settings().initialWindowSize(11))
            srv.recvFrame(Frames.settingsAck())
            srv.recvFrame(Frames.data(1, "d").eos())
            srv.sendFrame(Frames.headers(1).response(204).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.builder().method(Method.POST).uri("https://http2.akamai.com/").body(Unit)

        val (response, stream) = client.sendRequest(request, false)

        stream.reserveCapacity(10)

        val connTask = async { h2.run() }
        waitForCapacity(stream, 10)
        assertEquals(10, stream.capacity())

        stream.sendData(bytes("hello world"), true)

        val resp = response.await()
        assertEquals(StatusCode.NO_CONTENT, resp.status)

        // Wait for the connection to close
        // Hold on to the `client` handle to avoid sending a GO_AWAY frame.
        connTask.await()
        s.join()
    }

    // ⚖️ adapted: the reference runs the mock server and the connection with `tokio::spawn` and syncs with oneshot
    // channels; here they are coroutines and CompletableDeferreds.
    @Test
    fun settingsLoweredCapacityReturnsCapacityToConnection() = h2Test {
        val (io, srv) = mockNew()
        val tx1 = CompletableDeferred<Unit>()
        val tx2 = CompletableDeferred<Unit>()

        val windowSize = DEFAULT_INITIAL_WINDOW_SIZE

        // Spawn the server on a thread
        val th1 = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            tx1.complete(Unit)
            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/one"))
            srv.recvFrame(Frames.headers(3).request("POST", "https://example.com/two"))
            idleMs(200)
            // Remove all capacity from streams
            srv.sendFrame(Frames.settings().initialWindowSize(0))
            srv.recvFrame(Frames.settingsAck())

            // Let stream 3 make progress
            srv.sendFrame(Frames.windowUpdate(3, 11))
            srv.recvFrame(Frames.data(3, "hello world").eos())
            // Wait to get notified
            //
            // A timeout is used here to avoid blocking forever if there is a
            // failure
            withTimeout(5_000) { tx2.await() }

            idleMs(500)

            // Reset initial window size
            srv.sendFrame(Frames.settings().initialWindowSize(windowSize.toLong()))
            srv.recvFrame(Frames.settingsAck())

            // Get data from first stream
            srv.recvFrame(Frames.data(1, "hello world").eos())

            // Send responses
            srv.sendFrame(Frames.headers(1).response(204).eos())
            srv.sendFrame(Frames.headers(3).response(204).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)

        // Drive client connection
        val th2 = async { h2.run() }

        // Wait for server handshake to complete.
        withTimeout(5_000) { tx1.await() }

        val request1 = Request.post("https://example.com/one").body(Unit)

        val (resp1, stream1) = client.sendRequest(request1, false)

        val request2 = Request.post("https://example.com/two").body(Unit)

        val (resp2, stream2) = client.sendRequest(request2, false)

        // Reserve capacity for stream one, this will consume all connection level
        // capacity
        stream1.reserveCapacity(windowSize)
        waitForCapacity(stream1, windowSize)

        // Now, wait for capacity on the other stream
        stream2.reserveCapacity(11)
        waitForCapacity(stream2, 11)

        // Send data on stream 2
        stream2.sendData(bytes("hello world"), true)

        tx2.complete(Unit)

        // Wait for capacity on stream 1
        waitForCapacity(stream1, 11)

        stream1.sendData(bytes("hello world"), true)

        // Wait for responses..
        val r1 = resp1.await()
        assertEquals(StatusCode.NO_CONTENT, r1.status)

        val r2 = resp2.await()
        assertEquals(StatusCode.NO_CONTENT, r2.status)

        th1.join()
        th2.await()
    }

    @Test
    fun clientIncreaseTargetWindowSize() = h2Test {
        val (io, srv) = mockNew()

        // `join(srv, client)` polls the server side first: it sends its SETTINGS before the client runs.
        val s = launch(start = CoroutineStart.UNDISPATCHED) {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.windowUpdate(0, (2 shl 20) - 65_535))
            srv.close()
        }

        // `_client` is held (not dropped) in the reference.
        val (client, conn) = neton.http.h2.client.handshake(io)
        conn.setTargetWindowSize(2 shl 20)
        conn.run()
        s.join()
        client.close()
    }

    @Test
    fun increaseTargetWindowSizeAfterUsingSome() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)).eos())
            srv.recvFrame(Frames.windowUpdate(0, (2 shl 20) - 65_535))
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder().uri("https://http2.akamai.com/").body(Unit)

        val (resFut, sendStream) = client.sendRequest(request, true)
        // `.0`: the SendStream is dropped at once.
        sendStream.close()

        val connTask = async { conn.run() }
        val res = resFut.await()
        conn.setTargetWindowSize(2 shl 20)
        // drive an empty future to allow the WINDOW_UPDATE
        // to go out while the response capacity is still in use.
        yieldOnce()
        val body = res.body
        @Suppress("UNUSED_VARIABLE")
        val resBytes = runCatching { concat(body) }
        body.close()
        connTask.await()
        s.join()
    }

    @Test
    fun decreaseTargetWindowSize() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, ByteArray(16_383)).eos())
            srv.recvFrame(Frames.windowUpdate(0, 16_384))
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        conn.setTargetWindowSize(16_384 * 2)

        val request = Request.builder().uri("https://http2.akamai.com/").body(Unit)
        val (resp, sendStream) = client.sendRequest(request, true)
        sendStream.close()
        val connTask = async { conn.run() }
        val res = resp.await()
        conn.setTargetWindowSize(16_384)
        val body = res.body
        val cap = body.flowControl().clone()

        val bytes = concat(body)
        body.close()
        assertEquals(65_535, bytes.size)
        cap.releaseCapacity(bytes.size)
        connTask.await()
        s.join()
    }

    @Test
    fun clientUpdateInitialWindowSize() = h2Test {
        val (io, srv) = mockNew()

        val windowSize = DEFAULT_INITIAL_WINDOW_SIZE * 2

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.windowUpdate(0, windowSize - 65_535))
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(16_384) { 'a'.code.toByte() }))
            srv.sendFrame(Frames.data(1, ByteArray(16_384) { 'b'.code.toByte() }))
            srv.sendFrame(Frames.data(1, ByteArray(16_384) { 'c'.code.toByte() }))
            srv.recvFrame(Frames.settings().initialWindowSize(windowSize.toLong()))
            srv.sendFrame(Frames.settingsAck())
            // we never got a WINDOW_UPDATE, but initial update allows more
            srv.sendFrame(Frames.data(1, ByteArray(16_384) { 'd'.code.toByte() }))
            srv.sendFrame(Frames.data(1, ByteArray(16_384) { 'e'.code.toByte() }).eos())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        conn.setTargetWindowSize(windowSize)

        // We'll never release_capacity back...
        suspend fun data(body: RecvStream, expect: String) {
            val buf = assertNotNull(body.data(), expect)
            assertEquals(16_384, buf.size, expect)
        }

        val resFut = client.get("https://http2.akamai.com/")

        val connTask = async { conn.run() }

        // Receive most of the stream's window...
        val resp = resFut.await()
        val body = resp.body
        data(body, "data1")
        data(body, "data2")
        data(body, "data3")

        // Update the initial window size to double
        conn.setInitialWindowSize(windowSize)

        // And then ensure we got the data normally "over" the smaller
        // initial_window_size...
        val f = launch {
            data(body, "data4")
            data(body, "data5")
            assertNull(body.data(), "eos")
            body.close()
        }

        connTask.await()
        f.join()
        s.join()
    }

    @Test
    fun clientDecreaseInitialWindowSize() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())

            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(100) { 'a'.code.toByte() }))

            srv.recvFrame(Frames.headers(3).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(3).response(200))
            srv.sendFrame(Frames.data(3, ByteArray(100) { 'a'.code.toByte() }))

            srv.recvFrame(Frames.headers(5).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(5).response(200))
            srv.sendFrame(Frames.data(5, ByteArray(100) { 'a'.code.toByte() }))

            srv.recvFrame(Frames.settings().initialWindowSize(0))
            // check settings haven't applied before ACK
            srv.sendFrame(Frames.data(1, ByteArray(100) { 'a'.code.toByte() }).eos())
            srv.sendFrame(Frames.settingsAck())

            // check stream 3 has no window
            srv.sendFrame(Frames.data(3, ByteArray(1) { 'a'.code.toByte() }))
            srv.recvFrame(Frames.reset(3).flowControl())

            // check stream 5 can release capacity
            srv.recvFrame(Frames.windowUpdate(5, 100))

            srv.recvFrame(Frames.settings().initialWindowSize(16_384))
            srv.sendFrame(Frames.settingsAck())

            srv.sendFrame(Frames.data(5, ByteArray(100) { 'a'.code.toByte() }))
            srv.sendFrame(Frames.data(5, ByteArray(100) { 'a'.code.toByte() }).eos())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)

        suspend fun data(body: RecvStream, expect: String) {
            val buf = assertNotNull(body.data(), expect)
            assertEquals(100, buf.size, expect)
        }

        suspend fun req(client: neton.http.h2.client.SendRequest): RecvStream {
            val resFut = client.get("https://http2.akamai.com/")

            // Use some of the recv window
            val resp = resFut.await()
            val body = resp.body

            data(body, "data1")

            return body
        }

        val connTask = async { conn.run() }

        val body1 = req(client)
        val body3 = req(client)
        val body5 = req(client)

        // Remove *all* window size of streams
        conn.setInitialWindowSize(0)
        yieldOnce()

        // stream 1 received before settings ACK
        data(body1, "body1 data2")
        assertTrue(body1.isEndStream)

        // stream 3 received after ACK, which is stream error
        assertFailsWith<H2Error>("data2") { body3.data() }

        // stream 5 went negative, so release back to 0
        assertEquals(-100, body5.flowControl().availableCapacity())
        assertEquals(100, body5.flowControl().usedCapacity())
        body5.flowControl().releaseCapacity(100)
        yieldOnce()

        // open up again
        conn.setInitialWindowSize(16_384)
        yieldOnce()

        // get stream 5 data after opening up
        data(body5, "body5 data2")
        data(body5, "body5 data3")
        assertFalse(body3.isEndStream)

        connTask.await()
        s.join()
    }

    @Test
    fun serverTargetWindowSize() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.recvFrame(Frames.windowUpdate(0, (2 shl 20) - 65_535))
            client.close()
        }

        val conn = neton.http.h2.server.handshake(io)
        conn.setTargetWindowSize(2 shl 20)
        // The reference's `conn.next().await` drives the connection; its result is ignored.
        val connTask = async { runCatching { conn.run() } }
        runCatching { conn.accept() }

        c.join()
        connTask.await()
    }

    @Test
    fun recvSettingsIncreaseWindowSizeAfterUsingSome() = h2Test {
        // See https://github.com/hyperium/h2/issues/208
        val (io, srv) = mockNew()

        val newWinSize = 16_384 * 4 // 1 bigger than default
        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            srv.recvFrame(Frames.data(1, ByteArray(16_384)))
            srv.recvFrame(Frames.data(1, ByteArray(16_384)))
            srv.recvFrame(Frames.data(1, ByteArray(16_384)))
            srv.recvFrame(Frames.data(1, ByteArray(16_383)))
            srv.sendFrame(Frames.settings().initialWindowSize(newWinSize.toLong()))
            srv.recvFrame(Frames.settingsAck())
            srv.sendFrame(Frames.windowUpdate(0, 1))
            srv.recvFrame(Frames.data(1, ByteArray(1)).eos())
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder().method("POST").uri("https://http2.akamai.com/").body(Unit)
        val (resp, reqBody) = client.sendRequest(request, false)
        reqBody.sendData(bytes(newWinSize), true)
        val connTask = async { conn.run() }
        @Suppress("UNUSED_VARIABLE")
        val res = resp.await()
        connTask.await()
        s.join()
    }

    @Test
    fun reserveCapacityAfterPeerCloses() = h2Test {
        // See https://github.com/hyperium/h2/issues/300
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            // close connection suddenly
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder().method("POST").uri("https://http2.akamai.com/").body(Unit)
        val (resp, reqBody) = client.sendRequest(request, false)
        val connTask = async { conn.run() }
        assertFailsWith<H2Error> { resp.await() }
        // As stated in #300, this would panic because the connection
        // had already been closed.
        reqBody.reserveCapacity(1)
        connTask.await()
        s.join()
    }

    @Test
    fun resetStreamWaitingForCapacity() = h2Test {
        // This tests that receiving a reset on a stream that has some available
        // connection-level window reassigns that window to another stream.
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "http://example.com/"))
            srv.recvFrame(Frames.headers(3).request("GET", "http://example.com/"))
            srv.recvFrame(Frames.headers(5).request("GET", "http://example.com/"))
            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16383)).eos())
            srv.sendFrame(Frames.headers(1).response(200))
            // Assign enough connection window for stream 3...
            srv.sendFrame(Frames.windowUpdate(0, 1))
            // but then reset it.
            srv.sendFrame(Frames.reset(3))
            // 5 should use that window instead.
            srv.recvFrame(Frames.data(5, ByteArray(1)).eos())
            srv.sendFrame(Frames.headers(5).response(200))
            srv.close()
        }

        fun request(): Request<Unit> = Request.builder().uri("http://example.com/").body(Unit)

        val (client, conn) = neton.http.h2.client.Builder().handshake(io)
        val (req1, send1) = client.sendRequest(request(), false)
        val (req2, send2) = client.sendRequest(request(), false)
        val (req3, send3) = client.sendRequest(request(), false)
        // Use up the connection window.
        send1.sendData(bytes(65535), true)
        // Queue up for more connection window.
        send2.sendData(bytes(1), true)
        // .. and even more.
        send3.sendData(bytes(1), true)

        val connTask = async { conn.run() }
        val f1 = launch { req1.await() }
        val f2 = launch { assertFailsWith<H2Error> { req2.await() } }
        val f3 = launch { req3.await() }
        connTask.await()
        f1.join()
        f2.join()
        f3.join()
        s.join()
    }

    // Regression test for https://github.com/hyperium/h2/pull/893
    @Test
    fun reserveCapacityThenCancelDoesNotLeak() = h2Test {
        for (explicitReset in listOf(true, false)) {
            val (io, srv) = mockNew()

            val s = launch {
                srv.assertClientHandshake()
                srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
                if (!explicitReset) {
                    srv.sendFrame(Frames.headers(1).response(200))
                }
                var dataBytes = 0
                while (true) {
                    val frame = assertNotNull(srv.next())
                    when (frame) {
                        is Reset, is Headers -> {}
                        is Data -> {
                            dataBytes += frame.payload.size
                            if (frame.isEndStream) break
                        }
                        else -> fail("unexpected: $frame")
                    }
                }
                assertEquals(65535, dataBytes)
                srv.sendFrame(Frames.headers(3).response(200).eos())
                srv.close()
            }

            val (client, conn) = neton.http.h2.client.handshake(io)
            val request = Request.builder().method(Method.POST).uri("https://example.com/").body(Unit)
            val (response, stream) = client.sendRequest(request, false)
            stream.reserveCapacity(10)

            val connTask = async { conn.run() }

            if (explicitReset) {
                stream.sendReset(Reason.CANCEL)
                stream.close()
                val err = assertFailsWith<H2Error> { response.await() }
                assertEquals(Reason.CANCEL, err.reason())
            } else {
                val resp = response.await()
                assertEquals(StatusCode.OK, resp.status)
                stream.close()
                resp.body.close()
            }

            // Open a second stream and send a full window of data. If capacity
            // leaked, this would stall.
            val request2 = Request.builder().method(Method.POST).uri("https://example.com/").body(Unit)
            val (response2, stream2) = client.sendRequest(request2, false)
            stream2.sendData(bytes(65535), true)
            val f = launch {
                val resp = response2.await()
                assertEquals(StatusCode.OK, resp.status)
                client.close()
                resp.body.close()
            }
            connTask.await()
            f.join()
            stream2.close()
            s.join()
        }
    }

    @Test
    fun scheduledResetWithBufferedDataSendsRst() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake(Frames.settings().initialWindowSize(0))
            assertFrameEq(settings, Settings())

            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            srv.sendFrame(Frames.headers(1).response(200))

            withTimeoutOrNull(5_000) { srv.recvFrame(Frames.reset(1).cancel()) }
                ?: fail("RST_STREAM not received within 5s")
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder().method(Method.POST).uri("https://example.com/").body(Unit)
        val (response, stream) = client.sendRequest(request, false)
        val connTask = async { conn.run() }
        val resp = response.await()
        assertEquals(StatusCode.OK, resp.status)
        // Buffer data that can never be sent (zero stream window).
        stream.sendData(bytes(10), false)
        // Drop both handles to schedule a CANCEL reset.
        stream.close()
        resp.body.close()
        connTask.await()
        s.join()
        client.close()
    }

    @Test
    fun scheduledResetWithExcessBufferedDataIsCleanedUp() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            srv.assertClientHandshake()
            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16383)))
            srv.sendFrame(Frames.windowUpdate(0, 65535))
            srv.sendFrame(Frames.headers(1).response(200))
            srv.recvFrame(Frames.reset(1).cancel())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder().method(Method.POST).uri("https://example.com/").body(Unit)
        val (response, stream) = client.sendRequest(request, false)
        // Buffer the full window plus excess. The first 65535 bytes
        // are sent, and the remaining 1000 are stuck.
        stream.sendData(bytes(65535 + 1000), false)
        val connTask = async { conn.run() }
        val resp = response.await()
        assertEquals(StatusCode.OK, resp.status)
        stream.close()
        resp.body.close()
        client.close()
        withTimeoutOrNull(5_000) { connTask.await() }
            ?: fail("connection did not shut down within 5s")
        s.join()
    }

    @Test
    fun dataPadding() = h2Test {
        val (io, srv) = mockNew()

        val body = ByteArray(1 + 100 + 5)
        body[0] = 5
        for (i in 1..100) body[i] = 'z'.code.toByte()
        for (i in 101..105) body[i] = '0'.code.toByte()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "http://example.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200).field("content-length", "100"))
            srv.sendFrame(Frames.data(1, body).padded().eos())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder().method(Method.GET).uri("http://example.com/").body(Unit)

        // first request is allowed
        val (response, sendStream) = client.sendRequest(request, true)
        sendStream.close()
        val connTask = async { conn.run() }
        val fut = launch {
            val resp = response.await()
            assertEquals(StatusCode.OK, resp.status)
            val respBody = resp.body
            val bytes = concat(respBody)
            respBody.close()
            assertEquals(100, bytes.size)
        }
        connTask.await()
        fut.join()
        s.join()
    }

    @Test
    fun pollCapacityAfterSendDataAndReserve() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake(Frames.settings().initialWindowSize(5))
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://www.example.com/"))
            srv.sendFrame(Frames.headers(1).response(200))
            srv.recvFrame(Frames.data(1, "abcde"))
            srv.sendFrame(Frames.windowUpdate(1, 5))
            srv.recvFrame(Frames.data(1, "").eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.builder().method(Method.POST).uri("https://www.example.com/").body(Unit)

        val (response, stream) = client.sendRequest(request, false)

        val connTask = async { h2.run() }
        val resp = response.await()
        assertEquals(StatusCode.OK, resp.status)

        stream.sendData(bytes("abcde"), false)

        stream.reserveCapacity(5)

        // Initial window size was 5 so current capacity is 0 even if we just reserved.
        assertEquals(0, stream.capacity())

        // This will panic if there is a bug causing h2 to return Ok(0) from poll_capacity.
        waitForCapacity(stream, 5)

        stream.sendData(bytes(""), true)

        // Wait for the connection to close
        connTask.await()
        s.join()
    }

    @Test
    fun pollCapacityAfterSendDataAndReserveWithMaxSendBufferSize() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake(Frames.settings().initialWindowSize(10))
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://www.example.com/"))
            srv.sendFrame(Frames.headers(1).response(200))
            srv.recvFrame(Frames.data(1, "abcde"))
            srv.sendFrame(Frames.windowUpdate(1, 10))
            srv.recvFrame(Frames.data(1, "").eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.Builder().maxSendBufferSize(5).handshake(io)
        val request = Request.builder().method(Method.POST).uri("https://www.example.com/").body(Unit)

        val (response, stream) = client.sendRequest(request, false)

        val connTask = async { h2.run() }
        val resp = response.await()
        assertEquals(StatusCode.OK, resp.status)

        stream.sendData(bytes("abcde"), false)

        stream.reserveCapacity(5)

        // Initial window size was 10 but with a max send buffer size of 10 in the client,
        // so current capacity is 0 even if we just reserved.
        assertEquals(0, stream.capacity())

        // This will panic if there is a bug causing h2 to return Ok(0) from poll_capacity.
        waitForCapacity(stream, 5)

        stream.sendData(bytes(""), true)

        // Wait for the connection to close
        connTask.await()
        s.join()
    }

    @Test
    fun maxSendBufferSizeOverflow() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://www.example.com/"))
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.recvFrame(Frames.data(1, ByteArray(10)))
            srv.recvFrame(Frames.data(1, ByteArray(0)).eos())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.Builder().maxSendBufferSize(5).handshake(io)
        val request = Request.builder().method(Method.POST).uri("https://www.example.com/").body(Unit)

        val (response, stream) = client.sendRequest(request, false)

        val connTask = async { conn.run() }
        val resp = response.await()
        assertEquals(StatusCode.OK, resp.status)

        assertEquals(0, stream.capacity())
        stream.reserveCapacity(10)
        assertEquals(5, stream.capacity(), "polled capacity not over max buffer size")

        stream.sendData(bytes(10), false)

        stream.reserveCapacity(15)
        assertEquals(0, stream.capacity(), "now with buffered over the max, don't overflow")
        stream.sendData(bytes(0), true)

        // Wait for the connection to close
        connTask.await()
        s.join()
    }

    // ⚖️ adapted: the reference's `tokio::spawn`ed sender task is a coroutine here.
    @Test
    fun maxSendBufferSizePollCapacityWakesTask() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://www.example.com/"))
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.recvFrame(Frames.data(1, ByteArray(5)))
            srv.recvFrame(Frames.data(1, ByteArray(5)))
            srv.recvFrame(Frames.data(1, ByteArray(5)))
            srv.recvFrame(Frames.data(1, ByteArray(5)))
            srv.recvFrame(Frames.data(1, ByteArray(0)).eos())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.Builder().maxSendBufferSize(5).handshake(io)
        val request = Request.builder().method(Method.POST).uri("https://www.example.com/").body(Unit)

        val (response, stream) = client.sendRequest(request, false)

        val connTask = async { conn.run() }
        val resp = response.await()

        assertEquals(StatusCode.OK, resp.status)

        assertEquals(0, stream.capacity())
        val toSend = 20
        stream.reserveCapacity(toSend)
        assertEquals(5, stream.capacity(), "polled capacity not over max buffer size")

        val t1 = launch {
            var sent = 0
            val buf = ByteArray(toSend)
            while (true) {
                val cap = stream.awaitCapacity() ?: fail("no cap")
                stream.sendData(Bytes.wrap(buf.copyOfRange(sent, sent + cap)), false)
                sent += cap
                if (sent >= toSend) break
            }
            stream.sendData(Bytes.EMPTY, true)
        }

        // Wait for the connection to close
        connTask.await()
        t1.join()
        s.join()
    }

    @Test
    fun pollCapacityWakeupAfterWindowUpdate() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake(Frames.settings().initialWindowSize(10))
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://www.example.com/"))
            srv.sendFrame(Frames.headers(1).response(200))
            srv.recvFrame(Frames.data(1, "abcde"))
            srv.sendFrame(Frames.windowUpdate(1, 5))
            srv.sendFrame(Frames.windowUpdate(1, 5))
            srv.recvFrame(Frames.data(1, "abcde"))
            srv.recvFrame(Frames.data(1, "").eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.Builder().maxSendBufferSize(5).handshake(io)
        val request = Request.builder().method(Method.POST).uri("https://www.example.com/").body(Unit)

        val (response, stream) = client.sendRequest(request, false)

        val connTask = async { h2.run() }
        val resp = response.await()
        assertEquals(StatusCode.OK, resp.status)

        stream.sendData(bytes("abcde"), false)

        stream.reserveCapacity(10)
        assertEquals(0, stream.capacity())

        waitForCapacity(stream, 5)
        idleMs(10)
        stream.sendData(bytes("abcde"), false)

        stream.reserveCapacity(5)
        assertEquals(0, stream.capacity())

        // This will panic if there is a bug causing h2 to return Ok(0) from poll_capacity.
        waitForCapacity(stream, 5)

        stream.sendData(bytes(""), true)

        // Wait for the connection to close
        connTask.await()
        s.join()
    }

    @Test
    fun windowSizeDoesNotUnderflow() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())

            // Invalid HEADERS frame (missing mandatory fields).
            client.sendBytes(raw(0, 0, 0, 1, 5, 0, 0, 0, 1))

            client.sendFrame(Frames.settings().initialWindowSize(1329018135))

            client.sendFrame(Frames.settings().initialWindowSize(3809661))

            client.sendFrame(Frames.settings().initialWindowSize(1467177332))

            client.sendFrame(Frames.settings().initialWindowSize(3844989))
            client.close()
        }

        val builder = neton.http.h2.server.Builder()
        val srv = builder.handshake(io)

        srv.run()
        c.join()
    }

    @Test
    fun reclaimReservedCapacity() = h2Test {
        val (io, srv) = mockNew()
        val depleted = CompletableDeferred<Unit>()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())

            srv.recvFrame(Frames.headers(1).request("POST", "https://www.example.com/"))
            srv.sendFrame(Frames.headers(1).response(200))

            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16383)))
            depleted.complete(Unit)

            // By now, this peer's connection window is completely depleted.

            srv.recvFrame(Frames.headers(3).request("POST", "https://www.example.com/"))
            srv.sendFrame(Frames.headers(3).response(200))

            srv.recvFrame(Frames.reset(1).cancel())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val connTask = async { h2.run() }

        val depletingStream = run {
            val request = Request.builder().method(Method.POST).uri("https://www.example.com/").body(Unit)

            val (resp, stream) = client.sendRequest(request, false)

            run {
                val r = resp.await()
                assertEquals(StatusCode.OK, r.status)
                // `resp` is dropped at the end of the reference's block.
                r.body.close()
            }

            stream
        }

        depletingStream.sendData(bytes(65535), false)
        depleted.await()

        // By now, the client knows it has completely depleted the server's
        // connection window.

        depletingStream.reserveCapacity(1)

        val starvedStream = run {
            val request = Request.builder().method(Method.POST).uri("https://www.example.com/").body(Unit)

            val (resp, stream) = client.sendRequest(request, false)

            run {
                val r = resp.await()
                assertEquals(StatusCode.OK, r.status)
                r.body.close()
            }

            stream
        }

        // The following call puts starved_stream in pending_send, as the
        // server's connection window is completely empty.
        starvedStream.sendData(bytes(1), false)

        // This drop should change nothing, as it didn't actually reserve
        // any available connection window, only requested it.
        depletingStream.close()

        connTask.await()
        mock.join()
    }

    @Test
    fun capacityNotAssignedToUnopenedStreams() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake(Frames.settings().maxConcurrentStreams(1))
            assertFrameEq(settings, Settings())

            srv.recvFrame(Frames.headers(1).request("POST", "https://www.example.com/"))
            srv.recvFrame(Frames.data(1, "hello"))
            srv.recvFrame(Frames.data(1, "world").eos())
            srv.sendFrame(Frames.headers(1).response(200).eos())

            srv.recvFrame(Frames.headers(3).request("POST", "https://www.example.com/"))
            srv.sendFrame(Frames.windowUpdate(0, DEFAULT_INITIAL_WINDOW_SIZE + 10))
            srv.recvFrame(Frames.reset(3).cancel())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        fun request() = Request.builder().method(Method.POST).uri("https://www.example.com/").body(Unit)

        val (response1, stream1) = client.sendRequest(request(), false)
        stream1.sendData(bytes("hello"), false)
        val (response2, stream2) = client.sendRequest(request(), false)
        // `let (_, mut stream2)`: the ResponseFuture is dropped at once.
        response2.close()
        stream2.reserveCapacity(DEFAULT_INITIAL_WINDOW_SIZE)
        stream1.sendData(bytes("world"), true)
        val connTask = async { h2.run() }
        response1.await().body.close()
        waitForCapacity(stream2, DEFAULT_INITIAL_WINDOW_SIZE)
        stream2.close()
        connTask.await()
        mock.join()
    }

    @Test
    fun newInitialWindowSizeCapacityNotAssignedToUnopenedStreams() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake(
                Frames.settings().maxConcurrentStreams(1).initialWindowSize(10),
            )
            assertFrameEq(settings, Settings())

            srv.recvFrame(Frames.headers(1).request("POST", "https://www.example.com/"))
            srv.recvFrame(Frames.data(1, "hello"))
            srv.sendFrame(Frames.settings().initialWindowSize(DEFAULT_INITIAL_WINDOW_SIZE.toLong()))
            srv.recvFrame(Frames.settingsAck())
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.recvFrame(Frames.data(1, "world").eos())

            srv.recvFrame(Frames.headers(3).request("POST", "https://www.example.com/"))
            srv.sendFrame(Frames.windowUpdate(0, DEFAULT_INITIAL_WINDOW_SIZE + 10))
            srv.recvFrame(Frames.reset(3).cancel())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        fun request() = Request.builder().method(Method.POST).uri("https://www.example.com/").body(Unit)

        val (response1, stream1) = client.sendRequest(request(), false)
        stream1.sendData(bytes("hello"), false)
        val (response2, stream2) = client.sendRequest(request(), false)
        // `let (_, mut stream2)`: the ResponseFuture is dropped at once.
        response2.close()
        stream2.reserveCapacity(DEFAULT_INITIAL_WINDOW_SIZE)
        val connTask = async { h2.run() }
        response1.await().body.close()
        stream1.sendData(bytes("world"), true)
        waitForCapacity(stream2, DEFAULT_INITIAL_WINDOW_SIZE)
        stream2.close()
        connTask.await()
        mock.join()
    }

    // ==== abusive window updates ====

    @Test
    fun tooManyWindowUpdateResetsCausesGoAway() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            for (s in 1 until 21 step 2) {
                client.sendFrame(Frames.headers(s).request("GET", "https://example.com/").eos())
                // send a bunch of bad window updates before any headers
                client.sendFrame(Frames.windowUpdate(s, (UInt.MAX_VALUE - 2u).toInt()))
                client.recvFrame(Frames.reset(s).flowControl())
            }

            client.sendFrame(Frames.headers(21).request("GET", "https://example.com/").eos())
            // send a bunch of bad window updates before any headers
            client.sendFrame(Frames.windowUpdate(21, (UInt.MAX_VALUE - 2u).toInt()))
            client.recvFrame(Frames.goAway(21).calm().data("too_many_internal_resets"))
            client.close()
        }

        val conn = neton.http.h2.server.Builder().maxLocalErrorResetStreams(10).handshake(io)
        // The connection fails with the GOAWAY; the reference sees it from `next()`.
        val connTask = async { runCatching { conn.run() } }
        for (i in 1 until 21 step 2) {
            // `let (_, _)`: both are dropped at once.
            val (req, respond) = assertNotNull(conn.accept())
            req.body.close()
            respond.close()
        }
        val err = assertFailsWith<H2Error> { conn.accept() }
        assertTrue(err.isGoAway)
        assertTrue(err.isLibrary)
        assertEquals(Reason.ENHANCE_YOUR_CALM, err.reason())

        c.join()
        connTask.await()
    }

    @Test
    fun goawayIgnoresDataButReturnsConnectionCapacity() = h2Test {
        for (padded in listOf(false, true)) {
            val (io, client) = mockNew()

            // Test both with and without padding
            val data = ByteArray(16_384)
            if (padded) data[0] = 5 // 5 bytes of padding

            fun dataFrame(): MockData = if (padded) Frames.data(5, data).padded() else Frames.data(5, data)

            val shutdownPayload = PingFrame(PingFrame.SHUTDOWN).payloadBytes()

            val c = launch {
                client.assertServerHandshake()

                client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())

                // Receive GOAWAY(MAX) + PING from graceful shutdown.
                client.recvFrame(Frames.goAway(2147483647))
                client.recvFrame(Frames.ping(shutdownPayload))

                client.recvFrame(Frames.headers(1).response(200).eos())

                // Stream 3 arrives "in flight" before client processes GOAWAY.
                client.sendFrame(Frames.headers(3).request("POST", "https://example.com/"))

                // Complete the graceful shutdown handshake.
                client.sendFrame(Frames.ping(shutdownPayload).pong())

                // Final GOAWAY(3): streams 1 and 3 are accepted, everything above is rejected.
                client.recvFrame(Frames.goAway(3))

                // Stream 5 is above last_stream_id=3; DATA will be ignored,
                // but connection window must still be replenished.
                client.sendFrame(Frames.headers(5).request("POST", "https://example.com/"))
                client.sendFrame(dataFrame())
                client.sendFrame(dataFrame())

                client.recvFrame(Frames.windowUpdate(0, 16_384 * 2))

                client.sendFrame(Frames.data(3, "").eos())

                client.recvFrame(Frames.headers(3).response(200).eos())

                client.recvEof()
                client.close()
            }

            val srv = neton.http.h2.server.handshake(io)
            val connTask = async { srv.run() }

            // `_req` and `stream` are held until the end of the reference's block.
            val (req1, stream1) = assertNotNull(srv.accept())
            srv.gracefulShutdown()
            val rsp = Response.builder().status(200).body(Unit)
            stream1.sendResponse(rsp, true).close()

            val (req, stream) = assertNotNull(srv.accept())
            val body = req.body

            val b = launch {
                val buf = concat(body)
                body.close()
                assertTrue(buf.isEmpty())
                val rsp3 = Response.builder().status(200).body(Unit)
                stream.sendResponse(rsp3, true).close()
                stream.close()
            }

            assertNull(srv.accept(), "unexpected stream after GOAWAY")
            b.join()
            connTask.await()

            c.join()
            req1.body.close()
            stream1.close()
        }
    }

    /**
     * When the library sends RST_STREAM (e.g., due to a WINDOW_UPDATE
     * overflow), `poll_capacity` and `poll_reset` must be notified.
     * Regression test for https://github.com/hyperium/h2/pull/897
     */
    // ⚖️ adapted: futures-test's `.wakened()` (fails when the future resolves without a wake) has no equivalent; the
    // suspend functions only resume on a wake-up, so the timeout alone checks that the reset woke them.
    @Test
    fun pollCapacityWokenOnLibraryReset() = h2Test {
        for (pollingCapacity in listOf(true, false)) {
            val (io, srv) = mockNew()
            val clientDone = CompletableDeferred<Unit>()

            val s = launch {
                val settings = srv.assertClientHandshake()
                assertFrameEq(settings, Settings())

                srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))

                // 2. Receive the 65535-byte initial window (4 DATA frames at default MAX_FRAME_SIZE).
                srv.recvFrame(Frames.data(1, ByteArray(16_384)))
                srv.recvFrame(Frames.data(1, ByteArray(16_384)))
                srv.recvFrame(Frames.data(1, ByteArray(16_384)))
                srv.recvFrame(Frames.data(1, ByteArray(16_383)))

                // 3. Grow stream window to 2^31-1, to set up for overflow later.
                srv.sendFrame(Frames.windowUpdate(0, 65535))
                srv.sendFrame(Frames.windowUpdate(1, 2_147_483_647))

                // 5. Receive the next 65535 bytes (connection-limited).
                srv.recvFrame(Frames.data(1, ByteArray(16_384)))
                srv.recvFrame(Frames.data(1, ByteArray(16_384)))
                srv.recvFrame(Frames.data(1, ByteArray(16_384)))
                srv.recvFrame(Frames.data(1, ByteArray(16_383)))

                // 6. Overflow: stream window 2147418112 + 65536 = 2^31 > 2^31-1.
                srv.sendFrame(Frames.windowUpdate(1, 65536))

                // 8. Receive the RST_STREAM(FLOW_CONTROL_ERROR) sent by the library.
                srv.recvFrame(Frames.reset(1).flowControl())

                // Wait for the client to finish. Otherwise Recv::recv_eof hides
                // the missing waker.
                clientDone.await()
                srv.close()
            }

            val (client, conn) = neton.http.h2.client.handshake(io)
            launch {
                // Separate task so the polled method won't resolve unless notify_send wakes it.
                runCatching { conn.run() }
            }

            val request = Request.builder().method(Method.POST).uri("https://example.com/").body(Unit)
            // `_resp` is held.
            val (resp, stream) = client.sendRequest(request, false)

            // 1. Exhaust the initial 65535-byte window.
            stream.reserveCapacity(65535)
            val cap1 = assertNotNull(stream.awaitCapacity())
            assertEquals(65535, cap1)
            stream.sendData(bytes(cap1), false)

            // 4. poll_capacity blocks until 3. replenishes windows, then send again.
            stream.reserveCapacity(65535)
            val cap2 = assertNotNull(stream.awaitCapacity())
            assertEquals(65535, cap2)
            stream.sendData(bytes(cap2), false)

            // 7. The polled method must be woken by the reset from 6.
            if (pollingCapacity) {
                stream.reserveCapacity(65535)
                val result = withTimeoutOrNull(1_000) { listOf(stream.awaitCapacity()) }
                    ?: fail("poll_capacity was not woken")
                assertNull(result[0])
            } else {
                val reason = withTimeoutOrNull(1_000) { stream.awaitReset() }
                    ?: fail("poll_reset was not woken")
                assertEquals(Reason.FLOW_CONTROL_ERROR, reason)
            }

            clientDone.complete(Unit)
            s.join()
            resp.close()
            stream.close()
            client.close()
        }
    }

    /**
     * A WINDOW_UPDATE followed by a SETTINGS decrease can cancel each other out, resulting
     * in zero capacity. `poll_capacity` must return `Pending` (not `Ready(Ok(0))`) in that case.
     */
    @Test
    fun pollCapacityWindowUpdateSettingsRace() = h2Test {
        val (io, srv) = mockNew()

        val s = launch {
            val settings = srv.assertClientHandshake(Frames.settings().initialWindowSize(0))
            assertFrameEq(settings, Settings())

            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            idleMs(50)

            // Give stream capacity then immediately take it back
            srv.sendFrame(Frames.windowUpdate(1, 1024))
            srv.sendFrame(Frames.settings().initialWindowSize(0))
            srv.recvFrame(Frames.settingsAck())

            // Now give real, usable capacity
            srv.sendFrame(Frames.windowUpdate(0, 11))
            srv.sendFrame(Frames.windowUpdate(1, 11))
            srv.recvFrame(Frames.data(1, "hello world").eos())
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.builder().method(Method.POST).uri("https://example.com/").body(Unit)

        val (response, stream) = client.sendRequest(request, false)
        stream.reserveCapacity(11)

        val connTask = async { h2.run() }
        // `wait_for_capacity` panics if `poll_capacity` ever returns `Ok(0)`
        waitForCapacity(stream, 11)
        stream.sendData(bytes("hello world"), true)

        val resp = response.await()
        assertEquals(StatusCode.OK, resp.status)

        connTask.await()
        s.join()
    }
}
