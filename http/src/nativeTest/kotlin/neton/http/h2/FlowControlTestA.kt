// Ported from h2 0.4.19 tests/h2-tests/tests/flow_control.rs (24 tests).
// First half of the file: from `send_data_without_requesting_capacity` up to `recv_settings_keeps_assigned_capacity`.

package neton.http.h2

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import neton.http.Method
import neton.http.Request
import neton.http.StatusCode
import neton.http.h2.client.ResponseFuture
import neton.http.h2.frame.DEFAULT_INITIAL_WINDOW_SIZE
import neton.http.h2.frame.Settings
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.core.StreamCapability
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val LOST_WAKE_FIRST_DATA: ByteArray = "lost-wakeup-first-data".encodeToByteArray()
private val LOST_WAKE_SECOND_DATA: ByteArray = "lost-wakeup-second-data".encodeToByteArray()

private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    for (i in 0..size - needle.size) {
        var j = 0
        while (j < needle.size && this[i + j] == needle[j]) j++
        if (j == needle.size) return true
    }
    return false
}

/**
 * `FlushInterleave` + `FlushInterleaveIo`: once armed, and once a write carried the first DATA payload, the next
 * flush suspends (flush "entered") until the test resumes it. ⚖️ The reference blocks the connection's thread on
 * barriers while another thread queues DATA; here everything runs on one thread, so the flush suspends instead and
 * another coroutine queues the DATA while the connection is parked inside its flush.
 */
private class FlushInterleaveIo(private val inner: IoStream) : IoStream {
    // 0: idle, 1: armed, 2: first DATA written (the next flush blocks).
    private var state = 0
    private val entered = CompletableDeferred<Unit>()
    private val resume = CompletableDeferred<Unit>()

    fun arm() {
        assertEquals(0, state)
        state = 1
    }

    private fun observeWrite(src: Buffer) {
        if (state == 1 && src.peekAll().containsSlice(LOST_WAKE_FIRST_DATA)) state = 2
    }

    suspend fun waitForFlush() = entered.await()

    fun resumeFlush() {
        resume.complete(Unit)
    }

    override val capabilities: Set<StreamCapability> get() = inner.capabilities

    override suspend fun read(dst: Buffer): Int = inner.read(dst)

    // (`writev` keeps the interface default, which goes through this `write`.)
    override suspend fun write(src: Buffer): Int {
        observeWrite(src)
        return inner.write(src)
    }

    override suspend fun flush() {
        if (state == 2) {
            state = 0
            entered.complete(Unit)
            resume.await()
        }
        inner.flush()
    }

    override fun close() = inner.close()

    override suspend fun shutdownOutput() = inner.shutdownOutput()
}

/** `util::wait_for_capacity`. */
private suspend fun waitForCapacity(stream: SendStream, target: Int): SendStream {
    while (true) {
        assertNotNull(stream.awaitCapacity())
        val act = stream.capacity()
        // If a non-0 capacity was requested for the stream before calling
        // wait_for_capacity, then poll_capacity should return Pending
        // until there is a non-0 capacity.
        assertNotEquals(0, act)
        if (act >= target) return stream
    }
}

class FlowControlTestA {
    // In this case, the stream & connection both have capacity, but capacity is not
    // explicitly requested.
    @Test
    fun sendDataWithoutRequestingCapacity() = h2Test {
        val payload = ByteArray(1024)

        val mock = MockIoBuilder()
            .handshake()
            .write(
                raw(
                    // POST /
                    0, 0, 16, 1, 4, 0, 0, 0, 1, 131, 135, 65, 139, 157, 41, 172, 75, 143, 168, 233, 25, 151,
                    33, 233, 132,
                ),
            )
            .write(
                raw(
                    // DATA
                    0, 4, 0, 0, 1, 0, 0, 0, 1,
                ),
            )
            .write(payload)
            .write(Frames.SETTINGS_ACK)
            // Read response
            .read(raw(0, 0, 1, 1, 5, 0, 0, 0, 1, 0x89))
            .build()

        val (client, h2) = neton.http.h2.client.handshake(mock)

        val request = Request.builder()
            .method(Method.POST)
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val (response, stream) = client.sendRequest(request, false)

        // The capacity should be immediately allocated
        assertEquals(0, stream.capacity())

        // Send the data
        stream.sendData(Bytes.wrap(payload), true)

        // Get the response
        val connTask = launch { h2.run() }
        val resp = response.await()
        assertEquals(StatusCode.NO_CONTENT, resp.status)

        connTask.join()
        mock.assertDone()
    }

    @Test
    fun releaseCapacitySendsWindowUpdate() = h2Test {
        val payload = ByteArray(16_384)
        val payloadLen = payload.size

        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, payload))
            srv.sendFrame(Frames.data(1, payload))
            srv.sendFrame(Frames.data(1, payload))
            srv.recvFrame(Frames.windowUpdate(0, 32_768))
            srv.recvFrame(Frames.windowUpdate(1, 32_768))
            srv.sendFrame(Frames.data(1, payload).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .method(Method.GET)
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val conn = launch { h2.run() }

        // req
        val (fut, tx) = client.sendRequest(request, true)
        tx.close()
        val resp = fut.await()
        // Get the response
        assertEquals(StatusCode.OK, resp.status)
        val body = resp.body

        // read some body to use up window size to below half
        var buf = body.data()!!
        assertEquals(payloadLen, buf.size)

        buf = body.data()!!
        assertEquals(payloadLen, buf.size)

        buf = body.data()!!
        assertEquals(payloadLen, buf.size)
        body.flowControl().releaseCapacity(buf.size * 2)

        buf = body.data()!!
        assertEquals(payloadLen, buf.size)
        // End of the `req` block: `body` and `client` are dropped.
        body.close()
        client.close()

        conn.join()
        mock.join()
    }

    @Test
    fun windowUpdatesIncludePaddedLength() = h2Test {
        // Our manual way of sending padding frames, not supported publicly
        val payloadLen = 16_378 // 16_384; does padding + payload count for max frame size?
        val payload = ByteArray(payloadLen + 6)
        payload[0] = 5
        for (i in 1..payloadLen) payload[i] = 'z'.code.toByte()
        for (i in payloadLen + 1 until payloadLen + 6) payload[i] = '0'.code.toByte()

        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, payload).padded())
            srv.sendFrame(Frames.data(1, payload).padded())
            srv.sendFrame(Frames.data(1, payload).padded())
            // the other 6 was auto-released earlier
            srv.recvFrame(Frames.windowUpdate(0, 32_774))
            srv.recvFrame(Frames.windowUpdate(1, 32_774))
            srv.sendFrame(Frames.data(1, payload).padded().eos())
            // but not double released here
            srv.recvFrame(Frames.windowUpdate(0, 32_762))
            // and no one cares about closed stream window
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .method(Method.GET)
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val conn = launch { h2.run() }

        // req
        val (fut, tx) = client.sendRequest(request, true)
        tx.close()
        val resp = fut.await()
        // Get the response
        assertEquals(StatusCode.OK, resp.status)
        val body = resp.body

        // read some body to use up window size to below half
        var buf = body.data()!!
        assertEquals(payloadLen, buf.size)

        buf = body.data()!!
        assertEquals(payloadLen, buf.size)

        buf = body.data()!!
        assertEquals(payloadLen, buf.size)
        body.flowControl().releaseCapacity(buf.size * 2)

        buf = body.data()!!
        assertEquals(payloadLen, buf.size)
        body.close()
        idleMs(20)
        // End of the `req` block: `client` is dropped.
        client.close()

        conn.join()
        mock.join()
    }

    @Test
    fun releaseCapacityOfSmallAmountDoesNotSendWindowUpdate() = h2Test {
        val payload = ByteArray(16)

        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, payload).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .method(Method.GET)
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val conn = launch { h2.run() }

        // req
        val (fut, tx) = client.sendRequest(request, true)
        tx.close()
        val resp = fut.await()
        // Get the response
        assertEquals(StatusCode.OK, resp.status)
        val body = resp.body
        assertFalse(body.isEndStream)
        val buf = body.data()!!
        // read the small body and then release it
        assertEquals(16, buf.size)
        body.flowControl().releaseCapacity(buf.size)
        assertNull(body.data())
        // End of the `req` block: `body` and `client` are dropped.
        body.close()
        client.close()

        conn.join()
        mock.join()
    }

    // ignored in the reference
    @Ignore
    @Test
    fun expandWindowSendsWindowUpdate() {
    }

    // ignored in the reference
    @Ignore
    @Test
    fun expandWindowCallsAreCoalesced() {
    }

    @Test
    fun recvDataOverflowsConnectionWindow() = h2Test {
        val (io, srv) = mockNew()

        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200))
            // fill the whole window
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, ByteArray(16_383)))
            // this frame overflows the window!
            srv.sendFrame(Frames.data(1, ByteArray(128)).eos())
            // expecting goaway for the conn, not stream
            srv.recvFrame(Frames.goAway(0).flowControl())
            // connection is ended by client
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .method(Method.GET)
            .uri("https://http2.akamai.com/")
            .body(Unit)

        // client should see a flow control error
        val conn = launch {
            val err = assertFailsWith<H2Error> { h2.run() }
            assertEquals("connection error detected: flow-control protocol violated", err.message)
        }

        // req
        val (fut, tx) = client.sendRequest(request, true)
        tx.close()
        val resp = fut.await()
        assertEquals(StatusCode.OK, resp.status)
        val body = resp.body
        val err = assertFailsWith<H2Error> { concat(body) }
        assertEquals("connection error detected: flow-control protocol violated", err.message)
        // End of the `req` block: `body` and `client` are dropped.
        body.close()
        client.close()

        conn.join()
        mock.join()
    }

    @Test
    fun recvDataOverflowsStreamWindow() = h2Test {
        // this tests for when streams have smaller windows than their connection
        val (io, srv) = mockNew()

        val mock = launch {
            srv.assertClientHandshake()
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200))
            // fill the whole window
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            // this frame overflows the window!
            srv.sendFrame(Frames.data(1, ByteArray(16)).eos())
            srv.recvFrame(Frames.reset(1).flowControl())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.Builder()
            .initialWindowSize(16_384)
            .handshake(io)
        val request = Request.builder()
            .method(Method.GET)
            .uri("https://http2.akamai.com/")
            .body(Unit)

        val connTask = launch { conn.run() }

        // req
        val (fut, tx) = client.sendRequest(request, true)
        tx.close()
        val resp = fut.await()
        assertEquals(StatusCode.OK, resp.status)
        val body = resp.body
        val err = assertFailsWith<H2Error> { concat(body) }
        assertEquals("stream error detected: flow-control protocol violated", err.message)
        // End of the `req` block: `body` and `client` are dropped.
        body.close()
        client.close()

        connTask.join()
        mock.join()
    }

    // ignored in the reference
    @Ignore
    @Test
    fun recvWindowUpdateCausesOverflow() {
        // A received window update causes the window to overflow.
    }

    @Test
    fun streamErrorReleaseConnectionCapacity() = h2Test {
        val (io, srv) = mockNew()

        val srvTask = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            // we're sending the wrong content-length
            srv.sendFrame(
                Frames.headers(1)
                    .response(200)
                    .field("content-length", (16_384 * 3).toString()),
            )
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, ByteArray(10)).eos())
            // mismatched content-length is a protocol error
            srv.recvFrame(Frames.reset(1).protocolError())
            // but then the capacity should be released automatically
            srv.recvFrame(Frames.windowUpdate(0, 16_384 * 2 + 10))
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        // conn.drive(req) then conn.await.expect("client")
        val connTask = launch { conn.run() }

        // req
        val (fut, tx) = client.sendRequest(request, true)
        tx.close()
        val resp = fut.await()
        assertEquals(StatusCode.OK, resp.status)
        val body = resp.body
        val cap = body.flowControl().clone()
        val toRelease = 16_384 * 2
        var shouldRecvBytes = toRelease
        var shouldRecvFrames = 2

        val err = assertFailsWith<H2Error>("body") {
            // try_for_each
            while (true) {
                val bytes = body.data() ?: break
                shouldRecvBytes -= bytes.size
                shouldRecvFrames -= 1
                if (shouldRecvBytes == 0) {
                    assertEquals(0, shouldRecvFrames)
                }
            }
        }
        assertEquals("stream error detected: unspecific protocol error detected", err.message)
        cap.releaseCapacity(toRelease)
        // End of the `req` block: `body` and `cap` are dropped.
        body.close()
        cap.close()

        connTask.join()
        srvTask.join()
    }

    @Test
    fun recvStreamDropReleasesOnlyBufferedConnectionCapacity() = h2Test {
        val frameLen = 16_384
        val totalLen = frameLen * 2

        // Exercise all relationships between buffered and in-flight capacity:
        // equal, buffered > in-flight, and buffered < in-flight.
        for (readFrames in 0..1) {
            for (releasedFrames in 0..1) {
                val expectedUsed = maxOf(0, readFrames - releasedFrames) * frameLen
                val (io, peer) = mockNew()

                val peerTask = launch {
                    peer.assertServerHandshake()
                    peer.sendFrame(Frames.headers(1).request("POST", "https://example.com/"))
                    repeat(2) {
                        peer.sendFrame(Frames.data(1, ByteArray(frameLen)))
                    }

                    peer.recvFrame(Frames.windowUpdate(0, totalLen - expectedUsed))
                    if (releasedFrames > 0) {
                        peer.recvFrame(Frames.windowUpdate(1, frameLen))
                    }
                    peer.close()
                }

                // server
                val server = neton.http.h2.server.handshake(io)
                // The result of the connection is not checked by the reference (`let _ = server.next().await`).
                val serverRun = launch { runCatching { server.run() } }
                val (request, respond) = server.accept()!!
                val body = request.body

                repeat(readFrames) {
                    assertEquals(frameLen, body.data()!!.size)
                }

                val flow = body.flowControl().clone()
                repeat(releasedFrames) {
                    flow.releaseCapacity(frameLen)
                }
                body.close()

                assertEquals(expectedUsed, flow.usedCapacity())

                runCatching { server.accept() }
                // End of the server block: `flow`, `_respond` and `server` are dropped.
                flow.close()
                respond.close()
                serverRun.cancel()

                peerTask.join()
            }
        }
    }

    // Regression test for TODO
    @Test
    fun paddedDataStreamErrorReleasesConnectionCapacity() = h2Test {
        val (io, srv) = mockNew()

        // Padded EOS frame: 1 byte pad_len + 8 bytes data + 1 byte padding.
        // flow_controlled_len = 10, payload (data only) = 8.
        val paddedEos = ByteArray(10)
        paddedEos[0] = 1

        val srvTask = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            // Wrong content-length triggers a stream error on the padded EOS frame.
            srv.sendFrame(
                Frames.headers(1)
                    .response(200)
                    .field("content-length", (16_384 * 3).toString()),
            )
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, paddedEos).padded().eos())
            srv.recvFrame(Frames.reset(1).protocolError())
            // Released capacity must include the padded frame's full
            // flow_controlled_len (10), not just its payload (8).
            srv.recvFrame(Frames.windowUpdate(0, 16_384 * 2 + 10))
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val request = Request.builder()
            .uri("https://http2.akamai.com/")
            .body(Unit)

        // conn.drive(req) then conn.await.expect("client")
        val connTask = launch { conn.run() }

        // req
        val (fut, tx) = client.sendRequest(request, true)
        tx.close()
        val resp = fut.await()
        assertEquals(StatusCode.OK, resp.status)
        val body = resp.body
        val cap = body.flowControl().clone()
        val toRelease = 16_384 * 2
        var shouldRecvBytes = toRelease
        var shouldRecvFrames = 2

        val err = assertFailsWith<H2Error>("body") {
            // try_for_each
            while (true) {
                val bytes = body.data() ?: break
                shouldRecvBytes -= bytes.size
                shouldRecvFrames -= 1
                if (shouldRecvBytes == 0) {
                    assertEquals(0, shouldRecvFrames)
                }
            }
        }
        assertEquals("stream error detected: unspecific protocol error detected", err.message)
        cap.releaseCapacity(toRelease)
        // End of the `req` block: `body` and `cap` are dropped.
        body.close()
        cap.close()

        connTask.join()
        srvTask.join()
    }

    // Regression test for TODO
    @Test
    fun paddedDataOnForgottenStreamReleasesConnectionCapacity() = h2Test {
        val (io, srv) = mockNew()

        // Padded frame: 1 byte pad_len + 16378 bytes data + 5 bytes padding.
        // flow_controlled_len = 16384, payload (data only) = 16378.
        val padded = ByteArray(16_384)
        padded[0] = 5

        val srvTask = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.recvFrame(Frames.reset(1).cancel())
            // Wait for the reset to expire so the stream is forgotten.
            idleMs(50)
            srv.pingPong(ByteArray(8) { 1 })
            // Stream 1 has been evicted. Send a padded DATA frame for it.
            srv.sendFrame(Frames.data(1, padded).padded().eos())
            // Released capacity must cover both frames using their full
            // flow_controlled_len. There used to be a bug where the padded
            // frame would release only 16378 (payload) instead of 16384.
            srv.recvFrame(Frames.windowUpdate(0, 16_384 * 2))
            srv.recvFrame(Frames.reset(1).streamClosed())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.Builder()
            .resetStreamDuration(10.milliseconds)
            .handshake(io)

        // conn.drive(req) then conn.await (expect("client"))
        val connTask = launch { conn.run() }

        // req
        val resp = client.get("https://example.com/").await()
        assertEquals(StatusCode.OK, resp.status)
        // This drop sends RST_STREAM(CANCEL)
        resp.body.close()

        connTask.join()
        client.close()
        srvTask.join()
    }

    @Test
    fun streamCloseByDataFrameReleasesCapacity() = h2Test {
        val (io, srv) = mockNew()

        val windowSize = DEFAULT_INITIAL_WINDOW_SIZE

        val h2Task = launch {
            val (client, h2) = neton.http.h2.client.handshake(io)
            // The reference only drives the connection through `h2.drive(..)` and never checks its result.
            launch { runCatching { h2.run() } }
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://http2.akamai.com/")
                .body(Unit)

            // Send request
            val (resp1, s1) = client.sendRequest(request, false)

            // This effectively reserves the entire connection window
            s1.reserveCapacity(windowSize)

            // The capacity should be immediately available as nothing else is
            // happening on the stream.
            waitForCapacity(s1, windowSize)

            val request2 = Request.builder()
                .method(Method.POST)
                .uri("https://http2.akamai.com/")
                .body(Unit)

            // Create a second stream
            val (resp2, s2) = client.sendRequest(request2, false)

            // Request capacity
            s2.reserveCapacity(5)

            // There should be no available capacity (as it is being held up by
            // the previous stream
            assertEquals(0, s2.capacity())

            // Closing the previous stream by sending an empty data frame will
            // release the capacity to s2
            s1.sendData(Bytes.EMPTY, true)

            // The capacity should be available
            waitForCapacity(s2, 5)

            // Send the frame
            s2.sendData(bytes("hello"), true)

            // Drive both streams to prevent the handles from being dropped
            // (which will send a RST_STREAM) before the connection is closed.
            resp1.await()
            resp2.await()
            // (The reference then drops everything together with the connection, so no RST_STREAM is ever
            // written: the handles are deliberately not closed here.)
        }

        val srvTask = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            srv.sendFrame(Frames.headers(1).response(200))
            srv.recvFrame(Frames.headers(3).request("POST", "https://http2.akamai.com/"))
            srv.sendFrame(Frames.headers(3).response(200))
            srv.recvFrame(Frames.data(1, "").eos())
            srv.recvFrame(Frames.data(3, "hello").eos())
            srv.close()
        }
        srvTask.join()
        h2Task.join()
    }

    @Test
    fun streamCloseByTrailersFrameReleasesCapacity() = h2Test {
        val (io, srv) = mockNew()

        val windowSize = DEFAULT_INITIAL_WINDOW_SIZE

        val h2Task = launch {
            val (client, h2) = neton.http.h2.client.handshake(io)
            // The reference only drives the connection through `h2.drive(..)` and never checks its result.
            launch { runCatching { h2.run() } }
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://http2.akamai.com/")
                .body(Unit)

            // Send request
            val (resp1, s1) = client.sendRequest(request, false)

            // This effectively reserves the entire connection window
            s1.reserveCapacity(windowSize)

            waitForCapacity(s1, windowSize)

            val request2 = Request.builder()
                .method(Method.POST)
                .uri("https://http2.akamai.com/")
                .body(Unit)

            // Create a second stream
            val (resp2, s2) = client.sendRequest(request2, false)

            // Request capacity
            s2.reserveCapacity(5)

            // There should be no available capacity (as it is being held up by
            // the previous stream
            assertEquals(0, s2.capacity())

            // Closing the previous stream by sending a trailers frame will
            // release the capacity to s2
            s1.sendTrailers(headerMapOf())

            // The capacity should be available
            waitForCapacity(s2, 5)

            // Send the frame
            s2.sendData(bytes("hello"), true)

            // Drive both streams to prevent the handles from being dropped
            // (which will send a RST_STREAM) before the connection is closed.
            resp1.await()
            resp2.await()
            // (The reference then drops everything together with the connection, so no RST_STREAM is ever
            // written: the handles are deliberately not closed here.)
        }

        val srvTask = launch {
            val settings = srv.assertClientHandshake()
            // Get the first frame
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            srv.sendFrame(Frames.headers(1).response(200))
            srv.recvFrame(Frames.headers(3).request("POST", "https://http2.akamai.com/"))
            srv.sendFrame(Frames.headers(3).response(200))
            srv.recvFrame(Frames.headers(1).eos())
            srv.recvFrame(Frames.data(3, "hello").eos())
            srv.close()
        }
        srvTask.join()
        h2Task.join()
    }

    @Test
    fun streamCloseBySendResetFrameReleasesCapacity() = h2Test {
        val (io, srv) = mockNew()

        val srvTask = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())

            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)))
            srv.sendFrame(Frames.data(1, ByteArray(16_384)).eos())
            srv.recvFrame(Frames.windowUpdate(0, 16_384 * 2))
            srv.recvFrame(Frames.headers(3).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(3).response(200).eos())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        // conn.drive(..) then conn.await.expect("client conn")
        val connTask = launch { conn.run() }
        run {
            val request = Request.builder()
                .uri("https://http2.akamai.com/")
                .body(Unit)
            val (resp, tx) = client.sendRequest(request, true)
            tx.close()
            val res = runCatching { resp.await() }
            //  ^-- ignore the response body
            // End of the block: `_res` (the response and its body) is dropped.
            res.getOrNull()?.body?.close()
        }
        val resp = run {
            val request = Request.builder()
                .uri("https://http2.akamai.com/")
                .body(Unit)
            val (resp, tx) = client.sendRequest(request, true)
            tx.close()
            client.close()
            resp
        }
        @Suppress("UNUSED_VARIABLE")
        val res = runCatching { resp.await() }
        connTask.join()
        srvTask.join()
    }

    // ignored in the reference
    @Ignore
    @Test
    fun streamCloseByRecvResetFrameReleasesCapacity() {
    }

    @Test
    fun recvWindowUpdateOnStreamClosedByDataFrame() = h2Test {
        val (io, srv) = mockNew()

        val h2Task = launch {
            val (client, h2) = neton.http.h2.client.handshake(io)
            val connTask = launch { h2.run() }
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://http2.akamai.com/")
                .body(Unit)

            val (response, stream) = client.sendRequest(request, false)

            // Wait for the response
            val resp = response.await()
            assertEquals(StatusCode.OK, resp.status)

            // Send a data frame, this will also close the connection
            stream.sendData(bytes("hello"), true)

            // keep `stream` from being dropped in order to prevent
            // it from sending an RST_STREAM frame.
            //
            // i know this is kind of evil, but it's necessary to
            // ensure that the stream is closed by the EOS frame,
            // and not by the RST_STREAM.
            // (`std::mem::forget(stream)`: the handle is simply never closed.)

            // Wait for the connection to close
            connTask.join()
        }
        val srvTask = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            srv.sendFrame(Frames.headers(1).response(200))
            srv.recvFrame(Frames.data(1, "hello").eos())
            srv.sendFrame(Frames.windowUpdate(1, 5))
            srv.close()
        }
        srvTask.join()
        h2Task.join()
    }

    @Test
    fun smallDataFramesReclaimedBeforeBufferingNext() = h2Test {
        val (io, srv) = mockNew()

        val h2Task = launch {
            val (client, h2) = neton.http.h2.client.handshake(io)
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://http2.akamai.com/")
                .body(Unit)

            val (response, stream) = client.sendRequest(request, false)

            stream.sendData(bytes("hello"), false)
            stream.sendData(bytes("world"), true)

            val connTask = launch { h2.run() }
            val resp = response.await()
            assertEquals(StatusCode.NO_CONTENT, resp.status)

            connTask.join()
        }

        val srvTask = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            srv.recvFrame(Frames.data(1, "hello"))
            srv.recvFrame(Frames.data(1, "world").eos())
            srv.sendFrame(Frames.headers(1).response(204).eos())
            srv.close()
        }

        srvTask.join()
        h2Task.join()
    }

    // ⚖️ adapted: the reference races a flush that runs without the streams lock (the connection thread blocked on
    // a barrier inside `poll_flush`) against another thread queueing the second DATA frame, and checks the
    // connection is still woken (lost wakeup). Here everything runs on one thread: the IoStream wrapper suspends the
    // connection inside its flush right after the first DATA payload was written, a separate coroutine queues the
    // second DATA frame meanwhile, then resumes the flush; the connection must still wake up and send it. The
    // reference's `wakened()` (suppressing spurious connection polls) has no equivalent: the Kotlin driver only
    // wakes when notified, and nothing else notifies it in this exchange.
    @Test
    fun sendEnqueuedDuringUnlockedFlushWakesConnection() = h2Test {
        val (mockIo, srv) = mockNew()
        val io = FlushInterleaveIo(mockIo)
        val headersReceived = CompletableDeferred<Unit>()

        val h2Task = launch {
            val (client, h2) = neton.http.h2.client.handshake(io)
            val connTask = launch { h2.run() }
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://http2.akamai.com/")
                .body(Unit)

            val (response, stream) = client.sendRequest(request, false)

            // Ensure the connection has finished flushing HEADERS and registered
            // its stream waker before the first DATA frame consumes that waker.
            headersReceived.await()

            io.arm()
            stream.sendData(Bytes.copyOf(LOST_WAKE_FIRST_DATA), false)

            val producer = launch {
                io.waitForFlush()
                val result = runCatching { stream.sendData(Bytes.copyOf(LOST_WAKE_SECOND_DATA), true) }
                io.resumeFlush()
                result.getOrThrow()
                // End of the producer: `stream` is dropped.
                stream.close()
            }

            val resp = withTimeoutOrNull(5.seconds) { response.await() }
                ?: fail("connection was not woken for DATA queued during flush")
            assertEquals(StatusCode.NO_CONTENT, resp.status)

            producer.join()
            client.close()
            connTask.join()
        }

        val srvTask = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            headersReceived.complete(Unit)
            srv.recvFrame(Frames.data(1, LOST_WAKE_FIRST_DATA))
            srv.recvFrame(Frames.data(1, LOST_WAKE_SECOND_DATA).eos())
            srv.sendFrame(Frames.headers(1).response(204).eos())
            srv.close()
        }

        srvTask.join()
        h2Task.join()
    }

    @Test
    fun reservedCapacityAssignedInMultiWindowUpdates() = h2Test {
        val (io, srv) = mockNew()

        val h2Task = launch {
            val (client, h2) = neton.http.h2.client.handshake(io)
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://http2.akamai.com/")
                .body(Unit)

            val (response, stream) = client.sendRequest(request, false)

            // Consume the capacity
            val payload = ByteArray(DEFAULT_INITIAL_WINDOW_SIZE)
            stream.sendData(Bytes.wrap(payload), false)

            // Reserve more data than we want
            stream.reserveCapacity(10)

            val connTask = launch { h2.run() }
            waitForCapacity(stream, 5)
            stream.sendData(bytes("hello"), false)
            stream.sendData(bytes("world"), true)

            val resp = response.await()
            assertEquals(StatusCode.NO_CONTENT, resp.status)

            // Wait for the connection to close
            connTask.join()
        }

        val srvTask = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            srv.recvFrame(Frames.data(1, ByteArray(16_384)))
            srv.recvFrame(Frames.data(1, ByteArray(16_384)))
            srv.recvFrame(Frames.data(1, ByteArray(16_384)))
            srv.recvFrame(Frames.data(1, ByteArray(16_383)))
            idleMs(100)
            // Increase the connection window
            srv.sendFrame(Frames.windowUpdate(0, 10))
            // Incrementally increase the stream window
            srv.sendFrame(Frames.windowUpdate(1, 4))
            idleMs(50)
            srv.sendFrame(Frames.windowUpdate(1, 1))
            // Receive first chunk
            srv.recvFrame(Frames.data(1, "hello"))
            srv.sendFrame(Frames.windowUpdate(1, 5))
            // Receive second chunk
            srv.recvFrame(Frames.data(1, "world").eos())
            srv.sendFrame(Frames.headers(1).response(204).eos())
            /*
            .recv_frame(frames::data(1, "hello").eos())
            .send_frame(frames::window_update(1, 5))
             */
            srv.close()
        }
        srvTask.join()
        h2Task.join()
    }

    @Test
    fun connectionNotifiedOnReleasedCapacity() = h2Test {
        val (io, srv) = mockNew()

        // We're going to run the connection on a thread in order to isolate task
        // notifications. This test is here, in part, to ensure that the connection
        // receives the appropriate notifications to send out window updates.

        val tx = Channel<ResponseFuture>(Channel.UNLIMITED)

        // Because threading is fun
        val settingsTx = CompletableDeferred<Unit>()

        val th1 = CompletableDeferred<Unit>()

        launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            settingsTx.complete(Unit)
            // Get the first request
            srv.recvFrame(Frames.headers(1).request("GET", "https://example.com/a").eos())
            // Get the second request
            srv.recvFrame(Frames.headers(3).request("GET", "https://example.com/b").eos())
            // Send the first response
            srv.sendFrame(Frames.headers(1).response(200))
            // Send the second response
            srv.sendFrame(Frames.headers(3).response(200))

            // Fill the connection window
            srv.sendFrame(Frames.data(1, ByteArray(16_384)).eos())
            idleMs(100)
            srv.sendFrame(Frames.data(3, ByteArray(16_384)).eos())

            // The window update is sent
            srv.recvFrame(Frames.windowUpdate(0, 16_384))

            th1.complete(Unit)
            srv.close()
        }

        val th2 = CompletableDeferred<Unit>()

        val (client, h2) = neton.http.h2.client.handshake(io)

        // (The reference drives the connection for `settings_rx`, then spawns it: here it runs from the start.)
        launch {
            // Run the connection to completion
            h2.run()

            th2.complete(Unit)
            client.close()
        }

        settingsTx.await()
        val request = Request.get("https://example.com/a").body(Unit)
        client.sendRequest(request, true).let { (fut, stream) ->
            stream.close()
            tx.send(fut)
        }

        val request2 = Request.get("https://example.com/b").body(Unit)
        client.sendRequest(request2, true).let { (fut, stream) ->
            stream.close()
            tx.send(fut)
        }

        // Get the two requests
        val a = tx.receive()
        val b = tx.receive()

        // Get the first response
        val response = a.await()
        assertEquals(StatusCode.OK, response.status)
        val aBody = response.body

        // Get the next chunk
        val chunk = aBody.data()
        assertEquals(16_384, chunk!!.size)

        // Get the second response
        val response2 = b.await()
        assertEquals(StatusCode.OK, response2.status)
        val bBody = response2.body

        // Get the next chunk
        val chunk2 = bBody.data()
        assertEquals(16_384, chunk2!!.size)

        // Wait a bit
        idleMs(100)

        // Release the capacity
        aBody.flowControl().releaseCapacity(16_384)

        th1.await()
        th2.await()

        // Explicitly drop this after the joins so that the capacity doesn't get
        // implicitly released before.
        bBody.close()
    }

    @Test
    fun recvSettingsRemovesAvailableCapacity() = h2Test {
        val (io, srv) = mockNew()

        val settings = Settings()
        settings.initialWindowSize = 0L

        val srvTask = launch {
            val theirs = srv.assertClientHandshake(settings)
            assertFrameEq(theirs, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            idleMs(100)
            srv.sendFrame(Frames.windowUpdate(0, 11))
            srv.sendFrame(Frames.windowUpdate(1, 11))
            srv.recvFrame(Frames.data(1, "hello world").eos())
            srv.sendFrame(Frames.headers(1).response(204).eos())
            srv.close()
        }

        val h2Task = launch {
            val (client, h2) = neton.http.h2.client.handshake(io)
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://http2.akamai.com/")
                .body(Unit)

            val (response, stream) = client.sendRequest(request, false)

            stream.reserveCapacity(11)

            val connTask = launch { h2.run() }
            waitForCapacity(stream, 11)
            assertEquals(11, stream.capacity())

            stream.sendData(bytes("hello world"), true)

            val resp = response.await()
            assertEquals(StatusCode.NO_CONTENT, resp.status)

            // Wait for the connection to close
            // Hold on to the `client` handle to avoid sending a GO_AWAY frame.
            connTask.join()
        }
        srvTask.join()
        h2Task.join()
    }

    @Test
    fun recvSettingsKeepsAssignedCapacity() = h2Test {
        val (io, srv) = mockNew()

        val sentSettings = CompletableDeferred<Unit>()

        val srvTask = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            srv.sendFrame(Frames.settings().initialWindowSize(64))
            srv.recvFrame(Frames.settingsAck())
            sentSettings.complete(Unit)
            srv.recvFrame(Frames.data(1, "hello world").eos())
            srv.sendFrame(Frames.headers(1).response(204).eos())
            srv.close()
        }

        val h2Task = launch {
            val (client, h2) = neton.http.h2.client.handshake(io)
            val request = Request.builder()
                .method(Method.POST)
                .uri("https://http2.akamai.com/")
                .body(Unit)

            val (response, stream) = client.sendRequest(request, false)

            stream.reserveCapacity(11)

            // join(h2.await.expect("h2"), f)
            val connTask = launch { h2.run() }
            // f
            waitForCapacity(stream, 11)
            sentSettings.await()
            stream.sendData(bytes("hello world"), true)
            val resp = response.await()
            assertEquals(StatusCode.NO_CONTENT, resp.status)
            // End of `f`: `stream` and `resp` are dropped.
            stream.close()
            resp.body.close()

            connTask.join()
        }

        srvTask.join()
        h2Task.join()
    }
}
