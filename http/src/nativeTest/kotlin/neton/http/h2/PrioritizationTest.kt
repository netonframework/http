package neton.http.h2

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.http.Method
import neton.http.Request
import neton.http.StatusCode
import neton.http.h2.frame.DEFAULT_INITIAL_WINDOW_SIZE
import neton.http.h2.frame.DEFAULT_MAX_FRAME_SIZE
import neton.http.h2.frame.Data
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import neton.http.h2.frame.WindowUpdate
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

// Ported from h2 0.4.19 tests/h2-tests/tests/prioritization.rs (7 tests). Where the reference polls the connection by
// hand until it is idle (with a no-op waker) before going on, the connection runs in its own coroutine and the test
// waits until the scripted transport has consumed the handshake.

class PrioritizationTest {
    private val defaultWindowSize = DEFAULT_INITIAL_WINDOW_SIZE

    private suspend fun waitForCapacity(stream: SendStream, target: Int) {
        while (true) {
            assertNotNull(stream.awaitCapacity())
            val act = stream.capacity()
            // A non-0 capacity was requested before: 0 is never returned.
            assertNotEquals(0, act)
            if (act >= target) return
        }
    }

    private fun post() = Request.builder().method(Method.POST).uri("https://http2.akamai.com/").body(Unit)

    @Test
    fun singleStreamSendLargeBody() = h2Test {
        val payload = ByteArray(1024)

        val mock = MockIoBuilder()
            .handshake()
            .write(Frames.SETTINGS_ACK)
            .write(
                raw(
                    // POST /
                    0, 0, 16, 1, 4, 0, 0, 0, 1, 131, 135, 65, 139, 157, 41, 172, 75, 143, 168, 233, 25, 151,
                    33, 233, 132,
                ),
            )
            .write(raw(0, 4, 0, 0, 1, 0, 0, 0, 1)) // DATA
            .write(payload)
            // Read response
            .read(raw(0, 0, 1, 1, 5, 0, 0, 0, 1, 0x89))
            .build()

        val (client, h2) = neton.http.h2.client.handshake(mock)
        val conn = async { h2.run() }
        // ⚖️ adapted: "poll h2 until idle" — the handshake (including our SETTINGS ACK) is done.
        mock.awaitConsumed(5)

        val (response, stream) = client.sendRequest(post(), false)

        // Reserve capacity to send the payload
        stream.reserveCapacity(payload.size)

        // The capacity should be immediately allocated
        waitForCapacity(stream, payload.size)

        // Send the data
        stream.sendData(Bytes.wrap(payload), true)

        // Get the response
        val resp = response.await()
        assertEquals(StatusCode.NO_CONTENT, resp.status)

        conn.await()
        mock.assertDone()
    }

    @Test
    fun multipleStreamsWithPayloadGreaterThanDefaultWindow() = h2Test {
        val payload = ByteArray(16384 * 5 - 1)

        val (io, srv) = mockNew()

        val server = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            srv.recvFrame(Frames.headers(3).request("POST", "https://http2.akamai.com/"))
            srv.recvFrame(Frames.headers(5).request("POST", "https://http2.akamai.com/"))
            srv.recvFrame(Frames.data(1, payload.copyOfRange(0, 16_384)))
            srv.recvFrame(Frames.data(1, payload.copyOfRange(16_384, 16_384 * 2)))
            srv.recvFrame(Frames.data(1, payload.copyOfRange(16_384 * 2, 16_384 * 3)))
            srv.recvFrame(Frames.data(1, payload.copyOfRange(16_384 * 3, 16_384 * 4 - 1)))
            srv.sendFrame(Frames.settings())
            srv.recvFrame(Frames.settingsAck())
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.sendFrame(Frames.headers(3).response(200).eos())
            srv.sendFrame(Frames.headers(5).response(200).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val conn = async { h2.run() }
        val (response1, stream1) = client.sendRequest(post(), false)
        val (response2, stream2) = client.sendRequest(post(), false)
        val (response3, stream3) = client.sendRequest(post(), false)

        // The capacity should be immediately allocated to default window size (smaller than payload)
        stream1.reserveCapacity(payload.size)
        waitForCapacity(stream1, defaultWindowSize)

        stream2.reserveCapacity(payload.size)
        assertEquals(0, stream2.capacity())

        stream3.reserveCapacity(payload.size)
        assertEquals(0, stream3.capacity())

        stream1.sendData(Bytes.wrap(payload), true)

        // hold onto streams so they don't close; stream1 doesn't close because response1 is used
        response1.await()
        conn.await()
        server.join()
        listOf(response2, response3).forEach { it.close() }
        listOf(stream1, stream2, stream3).forEach { it.close() }
        client.close()
    }

    @Test
    fun singleStreamSendExtraLargeBodyMultiFramesOneBuffer() = h2Test {
        val payload = ByteArray(32_768)

        val mock = MockIoBuilder()
            .handshake()
            .write(Frames.SETTINGS_ACK)
            .write(
                raw(
                    // POST /
                    0, 0, 16, 1, 4, 0, 0, 0, 1, 131, 135, 65, 139, 157, 41, 172, 75, 143, 168, 233, 25, 151,
                    33, 233, 132,
                ),
            )
            .write(raw(0, 64, 0, 0, 0, 0, 0, 0, 1)) // DATA
            .write(payload.copyOfRange(0, 16_384))
            .write(raw(0, 64, 0, 0, 1, 0, 0, 0, 1)) // DATA
            .write(payload.copyOfRange(16_384, payload.size))
            // Read response
            .read(raw(0, 0, 1, 1, 5, 0, 0, 0, 1, 0x89))
            .build()

        val (client, h2) = neton.http.h2.client.handshake(mock)
        val conn = async { h2.run() }
        // ⚖️ adapted: "poll h2 until idle" — the handshake (including our SETTINGS ACK) is done.
        mock.awaitConsumed(5)

        val (response, stream) = client.sendRequest(post(), false)
        stream.reserveCapacity(payload.size)

        // The capacity should be immediately allocated
        waitForCapacity(stream, payload.size)

        // Send the data
        stream.sendData(Bytes.wrap(payload), true)

        // Get the response
        val resp = response.await()
        assertEquals(StatusCode.NO_CONTENT, resp.status)

        conn.await()
        mock.assertDone()
    }

    @Test
    fun singleStreamSendBodyGreaterThanDefaultWindow() = h2Test {
        val payload = ByteArray(16384 * 5 - 1)

        val mock = MockIoBuilder()
            .handshake()
            .write(Frames.SETTINGS_ACK)
            .write(
                raw(
                    // POST /
                    0, 0, 16, 1, 4, 0, 0, 0, 1, 131, 135, 65, 139, 157, 41, 172, 75, 143, 168, 233, 25, 151,
                    33, 233, 132,
                ),
            )
            .write(raw(0, 64, 0, 0, 0, 0, 0, 0, 1)) // DATA
            .write(payload.copyOfRange(0, 16_384))
            .write(raw(0, 64, 0, 0, 0, 0, 0, 0, 1)) // DATA
            .write(payload.copyOfRange(16_384, 16_384 * 2))
            .write(raw(0, 64, 0, 0, 0, 0, 0, 0, 1)) // DATA
            .write(payload.copyOfRange(16_384 * 2, 16_384 * 3))
            .write(raw(0, 63, 255, 0, 0, 0, 0, 0, 1)) // DATA
            .write(payload.copyOfRange(16_384 * 3, 16_384 * 4 - 1))
            // Read window update
            .read(raw(0, 0, 4, 8, 0, 0, 0, 0, 0, 0, 0, 64, 0))
            .read(raw(0, 0, 4, 8, 0, 0, 0, 0, 1, 0, 0, 64, 0))
            .write(raw(0, 64, 0, 0, 1, 0, 0, 0, 1)) // DATA
            .write(payload.copyOfRange(16_384 * 4 - 1, 16_384 * 5 - 1))
            // Read response
            .read(raw(0, 0, 1, 1, 5, 0, 0, 0, 1, 0x89))
            .build()

        val (client, h2) = neton.http.h2.client.handshake(mock)
        val conn = async { h2.run() }
        // ⚖️ adapted: "poll h2 until idle" — the handshake (including our SETTINGS ACK) is done.
        mock.awaitConsumed(5)

        val (response, stream) = client.sendRequest(post(), false)

        // Flush request head
        mock.awaitConsumed(6)

        // Send the data
        stream.sendData(Bytes.wrap(payload), true)

        // Get the response
        val resp = response.await()
        assertEquals(StatusCode.NO_CONTENT, resp.status)

        conn.await()
        mock.assertDone()
    }

    @Test
    fun singleStreamSendExtraLargeBodyMultiFramesMultiBuffer() = h2Test {
        val payload = ByteArray(32_768)

        val mock = MockIoBuilder()
            .write(PREFACE_BYTES)
            .write(Frames.SETTINGS)
            .read(Frames.SETTINGS)
            // Add wait to force the data writes to chill
            .wait(10)
            // Rest
            .write(
                raw(
                    // POST /
                    0, 0, 16, 1, 4, 0, 0, 0, 1, 131, 135, 65, 139, 157, 41, 172, 75, 143, 168, 233, 25, 151,
                    33, 233, 132,
                ),
            )
            .write(Frames.SETTINGS_ACK)
            .read(Frames.SETTINGS_ACK)
            .write(raw(0, 64, 0, 0, 0, 0, 0, 0, 1)) // DATA
            .write(payload.copyOfRange(0, 16_384))
            .wait(10)
            .write(raw(0, 64, 0, 0, 1, 0, 0, 0, 1)) // DATA
            .write(payload.copyOfRange(16_384, payload.size))
            // Read response
            .read(raw(0, 0, 1, 1, 5, 0, 0, 0, 1, 0x89))
            .build()

        val (client, h2) = neton.http.h2.client.handshake(mock)

        val (response, stream) = client.sendRequest(post(), false)
        stream.reserveCapacity(payload.size)

        val conn = async { h2.run() }

        // The capacity should be immediately allocated
        waitForCapacity(stream, payload.size)

        // Send the data
        stream.sendData(Bytes.wrap(payload), true)

        // Get the response
        val resp = response.await()
        assertEquals(StatusCode.NO_CONTENT, resp.status)

        conn.await()
        mock.assertDone()
    }

    @Test
    fun sendDataReceiveWindowUpdate() = h2Test {
        val (m, mock) = mockNew()

        val h2Side = launch {
            val (client, h2) = neton.http.h2.client.handshake(m)
            val conn = async { h2.run() }

            // Send request
            val (response, stream) = client.sendRequest(post(), false)

            // Send data frame
            stream.sendData(bytes("hello"), false)

            stream.reserveCapacity(DEFAULT_INITIAL_WINDOW_SIZE)

            // Wait for capacity
            waitForCapacity(stream, DEFAULT_INITIAL_WINDOW_SIZE)
            stream.sendData(Bytes.wrap(ByteArray(DEFAULT_INITIAL_WINDOW_SIZE)), true)

            // `stream` (and the response) are kept (the reference forgets them) so that no RST_STREAM is sent.
            conn.await()
            response.close()
            client.close()
        }

        mock.assertClientHandshake()

        val request = assertIs<Headers>(mock.next())
        assertFalse(request.isEndStream)
        val data = assertIs<Data>(mock.next())

        // Update the windows
        val len = data.payload.size
        mock.send(WindowUpdate(StreamId.ZERO, len))
        mock.send(WindowUpdate(data.streamId, len))

        repeat(3) {
            val d = assertIs<Data>(mock.next())
            assertEquals(DEFAULT_MAX_FRAME_SIZE, d.payload.size)
        }
        val d = assertIs<Data>(mock.next())
        assertEquals(DEFAULT_MAX_FRAME_SIZE - 1, d.payload.size)
        mock.close()

        h2Side.join()
    }

    @Test
    fun streamCountOverMaxStreamLimitDoesNotStarveCapacity() = h2Test {
        val (io, srv) = mockNew()
        val tx = CompletableDeferred<Unit>()

        val server = launch {
            srv.assertClientHandshake(Frames.settings().maxConcurrentStreams(1)) // super tiny server
            srv.recvFrame(Frames.headers(1).request("POST", "http://example.com/"))

            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16384)))
            srv.recvFrame(Frames.data(1, ByteArray(16383)).eos())
            srv.sendFrame(Frames.headers(1).response(200).eos())

            // All of these connection capacities should be assigned to stream 3
            srv.sendFrame(Frames.windowUpdate(0, 16384))
            srv.sendFrame(Frames.windowUpdate(0, 16384))
            srv.sendFrame(Frames.windowUpdate(0, 16384))
            srv.sendFrame(Frames.windowUpdate(0, 16383))

            // StreamId(3) should be able to send all of its request with the conn capacity
            srv.recvFrame(Frames.headers(3).request("POST", "http://example.com/"))
            srv.recvFrame(Frames.data(3, ByteArray(16384)))
            srv.recvFrame(Frames.data(3, ByteArray(16384)))
            srv.recvFrame(Frames.data(3, ByteArray(16384)))
            srv.recvFrame(Frames.data(3, ByteArray(16383)).eos())
            srv.sendFrame(Frames.headers(3).response(200).eos())

            // Then all the future stream is guaranteed to be send-able by induction
            tx.complete(Unit)
            srv.close()
        }

        fun request() = Request.builder().method(Method.POST).uri("http://example.com/").body(Unit)

        withTimeout(5_000) {
            val (client, conn) = neton.http.h2.client.Builder().handshake(io)
            launch { conn.run() }

            val (req1, send1) = client.sendRequest(request(), false)
            val (req2, send2) = client.sendRequest(request(), false)

            // Use up the connection window.
            send1.sendData(Bytes.wrap(ByteArray(65535)), true)
            // Queue up for more connection window.
            send2.sendData(Bytes.wrap(ByteArray(65535)), true)

            // Queue up more pending open streams
            repeat(5) {
                val (fut, send) = client.sendRequest(request(), false)
                fut.close()
                send.sendData(Bytes.wrap(ByteArray(65535)), true)
                send.close()
            }

            val response1 = req1.await()
            assertEquals(StatusCode.OK, response1.status)

            val response2 = req2.await()
            assertEquals(StatusCode.OK, response2.status)

            tx.await()
            server.join()
        }
    }
}
