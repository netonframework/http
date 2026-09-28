package neton.http.h2

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import neton.http.Method
import neton.http.Request
import neton.http.StatusCode
import neton.http.h2.frame.Settings
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Ported from h2 0.4.19 tests/h2-tests/tests/trailers.rs (5 tests, 1 of them empty and ignored as in the reference).

class TrailersTest {
    @Test
    fun recvTrailersOnly() = h2Test {
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
            .read(
                raw(
                    0, 0, 1, 1, 4, 0, 0, 0, 1, 0x88, 0, 0, 9, 1, 5, 0, 0, 0, 1, 0x40, 0x84, 0x42, 0x46,
                    0x9B, 0x51, 0x82, 0x3F, 0x5F,
                ),
            )
            .build()

        val (client, h2) = neton.http.h2.client.handshake(mock)
        val conn = async { h2.run() }

        // Send the request
        val request = Request.builder().uri("https://http2.akamai.com/").body(Unit)
        val (response, stream) = client.sendRequest(request, true)
        stream.close()

        val res = response.await()
        assertEquals(StatusCode.OK, res.status)
        val body = res.body

        // Make sure there is no body
        assertNull(body.data())

        val trailers = assertNotNull(body.trailers())
        assertEquals(1, trailers.len())
        assertEquals("ok", trailers["status"]!!.toStr())

        conn.await()
        mock.assertDone()
    }

    @Test
    fun sendTrailersImmediately() = h2Test {
        val mock = MockIoBuilder()
            .handshake()
            // Write GET /
            .write(
                raw(
                    0, 0, 0x10, 1, 4, 0, 0, 0, 1, 0x82, 0x87, 0x41, 0x8B, 0x9D, 0x29, 0xAC, 0x4B, 0x8F,
                    0xA8, 0xE9, 0x19, 0x97, 0x21, 0xE9, 0x84, 0, 0, 0x0A, 1, 5, 0, 0, 0, 1, 0x40, 0x83,
                    0xF6, 0x7A, 0x66, 0x84, 0x9C, 0xB4, 0x50, 0x7F,
                ),
            )
            .write(Frames.SETTINGS_ACK)
            // Read response
            .read(
                raw(
                    0, 0, 1, 1, 4, 0, 0, 0, 1, 0x88, 0, 0, 0x0B, 0, 1, 0, 0, 0, 1, 0x68, 0x65, 0x6C, 0x6C,
                    0x6F, 0x20, 0x77, 0x6F, 0x72, 0x6C, 0x64,
                ),
            )
            .build()

        val (client, h2) = neton.http.h2.client.handshake(mock)
        val conn = async { h2.run() }

        // Send the request
        val request = Request.builder().uri("https://http2.akamai.com/").body(Unit)
        val (response, stream) = client.sendRequest(request, false)

        val trailers = HeaderMap<HeaderValue>()
        trailers.insert("zomg", HeaderValue.fromStr("hello"))
        stream.sendTrailers(trailers)

        val res = response.await()
        assertEquals(StatusCode.OK, res.status)
        val body = res.body

        // There is a data chunk
        assertNotNull(body.data())
        assertNull(body.data())
        assertNull(body.trailers())

        conn.await()
        mock.assertDone()
    }

    @Test
    @Ignore // ignored in the reference (empty: "This should be a protocol error?")
    fun recvTrailersWithoutEos() {
    }

    @Test
    fun pollTrailersBeforeDataIsConsumed() = h2Test {
        val (io, srv) = mockNew()
        val framesReady = CompletableDeferred<Unit>()

        val server = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())

            // 2. Receive the request.
            srv.recvFrame(Frames.headers(1).request("GET", "https://example.com/").eos())

            // 3. Send response HEADERS followed by DATA and trailers.
            srv.sendFrame(Frames.headers(1).response(200))
            srv.sendFrame(Frames.data(1, "hello"))
            srv.sendFrame(Frames.headers(1).field("trailer-key", "trailer-val").eos())

            // 4. Ensure all preceding frames have been processed by the client.
            srv.pingPong(ByteArray(8) { 1 })
            framesReady.complete(Unit)
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val conn = async { h2.run() }

        // 1. Send the request and wait for response HEADERS.
        val resp = client.get("https://example.com/").await()
        assertEquals(StatusCode.OK, resp.status)
        val body = resp.body
        framesReady.await()

        // ⚖️ adapted: the reference polls by hand to check which waker gets notified. Here: 5. wait for the trailers
        // while DATA is at the front of the queue (the waiter parks); 6. consume the DATA from another coroutine;
        // 7. the parked waiter must be woken by the end of the data.
        val trailersTask = async(start = CoroutineStart.UNDISPATCHED) { body.trailers() }
        yield()
        assertFalse(trailersTask.isCompleted, "trailers should wait while DATA is buffered")
        assertEquals("hello", body.data()!!.decodeToString())
        assertNull(body.data())

        val trailers = assertNotNull(withTimeout(1_000) { trailersTask.await() }, "should have trailers")
        assertEquals("trailer-val", trailers["trailer-key"]!!.toStr())

        conn.await()
        server.join()
        client.close()
    }

    @Test
    fun sendTrailersRejectsConnectionSpecificHeaders() = h2Test {
        // RFC 9113 §8.2.2: endpoints MUST NOT *generate* an HTTP/2 message containing connection-specific header
        // fields. That obligation applies to trailer HEADERS blocks just as it does to the main header block.
        val (io, srv) = mockNew()

        val server = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            // The client opens the stream, then locally rejects every bad trailer (nothing hits the wire for those),
            // and finally sends a *valid* trailer to close cleanly.
            srv.recvFrame(Frames.headers(1).request("POST", "https://example.com/"))
            srv.recvFrame(Frames.headers(1).field("x-trailer", "ok").eos())
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val conn = async { h2.run() }
        val request = Request.builder().method(Method.POST).uri("https://example.com/").body(Unit)
        val (response, stream) = client.sendRequest(request, false)

        // Every connection-specific header must be rejected in a trailer block.
        for (name in listOf("connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade")) {
            val err = assertFailsWith<H2Error>(name) { stream.sendTrailers(headerMapOf(name to "x")) }
            assertEquals("user error: malformed headers", err.message)
        }
        // TE is connection-specific unless it is exactly `TE: trailers`.
        val err = assertFailsWith<H2Error>("te: chunked") { stream.sendTrailers(headerMapOf("te" to "chunked")) }
        assertEquals("user error: malformed headers", err.message)

        // The rejections above must not have corrupted stream state: a clean trailer still sends and the exchange
        // completes normally.
        stream.sendTrailers(headerMapOf("x-trailer" to "ok"))

        val res = response.await()
        assertEquals(StatusCode.OK, res.status)
        conn.await()
        server.join()
        client.close()
    }
}
