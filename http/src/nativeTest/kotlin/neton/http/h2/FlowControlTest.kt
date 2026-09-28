// Ported from h2 0.4.19 tests/h2-tests/tests/flow_control.rs (52 tests, 4 of them empty and ignored as in the
// reference).

package neton.http.h2

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import neton.http.Method
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.h2.client.ResponseFuture
import neton.http.h2.frame.DEFAULT_INITIAL_WINDOW_SIZE
import neton.http.h2.frame.Data
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Ping as PingFrame
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
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
import kotlin.test.assertTrue
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

class FlowControlTest {
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

    // ===== (second half of flow_control.rs) =====


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
