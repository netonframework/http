package neton.http.h2

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import neton.http.Method
import neton.http.Request
import neton.http.StatusCode
import neton.http.h2.frame.Settings
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Ported from h2 0.4.19 tests/h2-tests/tests/push_promise.rs (10 tests, 1 of them empty and ignored as in the
// reference). The reference's streams of push promises (`and_then(..).collect()`) are loops over
// `PushPromises.pushPromise()`; handles dropped at the end of the reference's blocks are closed there.

class PushPromiseTest {
    private fun get(uri: String) = Request.builder().method(Method.GET).uri(uri).body(Unit)

    @Test
    fun recvPushWorks() = h2Test {
        val (io, srv) = mockNew()
        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(404))
            srv.sendFrame(Frames.pushPromise(1, 2).request("GET", "https://http2.akamai.com/style.css"))
            srv.sendFrame(Frames.data(1, "").eos())
            srv.sendFrame(Frames.headers(2).response(200))
            srv.sendFrame(Frames.data(2, "promised_data").eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        launch { h2.run() }
        val (resp, stream) = client.sendRequest(get("https://http2.akamai.com/"), true)
        stream.close()
        val pushed = resp.pushPromises()
        val checkRespStatus = launch {
            val r = resp.await()
            assertEquals(StatusCode.NOT_FOUND, r.status)
            r.body.close()
        }
        val checkPushedResponse = launch {
            var count = 0
            while (true) {
                val p = pushed.pushPromise() ?: break
                val (request, response) = p
                assertEquals(Method.GET, request.method)
                val r = response.await()
                assertEquals(StatusCode.OK, r.status)
                val b = concat(r.body)
                assertEquals("promised_data", b.decodeToString())
                r.body.close()
                count++
            }
            assertEquals(1, count)
            pushed.close()
        }
        checkRespStatus.join()
        checkPushedResponse.join()
        mock.join()
    }

    @Test
    fun multipleInformationalResponsesOnPushedStream() = h2Test {
        val (io, srv) = mockNew()
        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            srv.sendFrame(Frames.pushPromise(1, 2).request("GET", "https://example.com/push"))
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.sendFrame(Frames.headers(2).response(103))
            srv.sendFrame(Frames.headers(2).response(100))
            srv.sendFrame(Frames.headers(2).response(200).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val conn = async { h2.run() }
        val (resp, stream) = client.sendRequest(get("https://example.com/"), true)
        stream.close()
        val pushed = resp.pushPromises()

        val r = resp.await()
        assertEquals(StatusCode.OK, r.status)
        val p = pushed.pushPromise()!!
        val (_, response) = p
        val pr = response.await()
        assertEquals(StatusCode.OK, pr.status)
        // The values of the reference's `check` block are dropped when it ends.
        r.body.close()
        pr.body.close()
        pushed.close()

        conn.await()
        client.close()
        mock.join()
    }

    @Test
    fun pushedStreamsArentDroppedTooEarly() = h2Test {
        // tests that by default, received push promises work
        val (io, srv) = mockNew()
        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(404))
            srv.sendFrame(Frames.pushPromise(1, 2).request("GET", "https://http2.akamai.com/style.css"))
            srv.sendFrame(Frames.pushPromise(1, 4).request("GET", "https://http2.akamai.com/style2.css"))
            srv.sendFrame(Frames.data(1, "").eos())
            idleMs(10)
            srv.sendFrame(Frames.headers(2).response(200))
            srv.sendFrame(Frames.headers(4).response(200).eos())
            srv.sendFrame(Frames.data(2, "").eos())
            srv.recvFrame(Frames.goAway(4))
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        val conn = async { h2.run() }
        val (resp, stream) = client.sendRequest(get("https://http2.akamai.com/"), true)
        stream.close()
        val pushed = resp.pushPromises()
        val checkStatus = launch {
            val r = resp.await()
            assertEquals(StatusCode.NOT_FOUND, r.status)
            r.body.close()
        }
        val checkPushed = launch {
            var count = 0
            while (true) {
                val p = pushed.pushPromise() ?: break
                val (request, response) = p
                assertEquals(Method.GET, request.method)
                val r = response.await()
                assertEquals(StatusCode.OK, r.status)
                r.body.close()
                count++
            }
            assertEquals(2, count)
            pushed.close()
        }

        client.close()

        checkPushed.join()
        checkStatus.join()
        conn.await()
        mock.join()
    }

    @Test
    fun recvPushWhenPushDisabledIsConnError() = h2Test {
        val (io, srv) = mockNew()
        val mock = launch {
            srv.assertClientHandshake()
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.pushPromise(1, 3).request("GET", "https://http2.akamai.com/style.css"))
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.recvFrame(Frames.goAway(0).protocolError())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.Builder().enablePush(false).handshake(io)

        val req = launch {
            val (fut, stream) = client.sendRequest(get("https://http2.akamai.com/"), true)
            stream.close()
            val err = assertFailsWith<H2Error> { fut.await() }
            assertEquals("connection error detected: unspecific protocol error detected", err.message)
        }

        // client should see a protocol error
        val err = assertFailsWith<H2Error> { h2.run() }
        assertEquals("connection error detected: unspecific protocol error detected", err.message)

        req.join()
        mock.join()
    }

    @Test
    fun pendingPushPromisesResetWhenDropped() = h2Test {
        val (io, srv) = mockNew()
        val server = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.pushPromise(1, 2).request("GET", "https://http2.akamai.com/style.css"))
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.recvFrame(Frames.reset(2).cancel())
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val connTask = async { conn.run() }
        val (fut, stream) = client.sendRequest(get("https://http2.akamai.com/"), true)
        stream.close()
        val resp = fut.await()
        assertEquals(StatusCode.OK, resp.status)
        // The response is dropped at the end of the reference's block: nothing can reach the promise any more.
        resp.body.close()

        connTask.await()
        client.close()
        server.join()
    }

    @Test
    fun recvPushPromiseOverMaxHeaderListSize() = h2Test {
        val (io, srv) = mockNew()

        val server = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Frames.settings().maxHeaderListSize(64))
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.pushPromise(1, 2).request("GET", "https://http2.akamai.com/style.css"))
            srv.recvFrame(Frames.reset(2).protocolError())
            srv.sendFrame(Frames.headers(1).response(200).eos())
            idleMs(10)
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.Builder().maxHeaderListSize(64).handshake(io)
        val connTask = async { conn.run() }
        val request = Request.builder().uri("https://http2.akamai.com/").body(Unit)

        val req = launch {
            val (fut, stream) = client.sendRequest(request, true)
            stream.close()
            val resp = fut.await()
            assertEquals(StatusCode.OK, resp.status)
            resp.body.close()
            client.close()
        }

        req.join()
        connTask.await()
        server.join()
    }

    @Test
    fun recvInvalidPushPromiseHeadersIsStreamProtocolError() = h2Test {
        // Unsafe method or content length is stream protocol error
        val (io, srv) = mockNew()
        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(404))
            srv.sendFrame(Frames.pushPromise(1, 2).request("POST", "https://http2.akamai.com/style.css"))
            srv.sendFrame(
                Frames.pushPromise(1, 4).request("GET", "https://http2.akamai.com/style.css").field("content-length", "1"),
            )
            srv.sendFrame(
                Frames.pushPromise(1, 6).request("GET", "https://http2.akamai.com/style.css").field("content-length", "0"),
            )
            srv.sendFrame(Frames.headers(1).response(404).eos())
            srv.recvFrame(Frames.reset(2).protocolError())
            srv.recvFrame(Frames.reset(4).protocolError())
            srv.sendFrame(Frames.headers(6).response(200).eos())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)
        launch { h2.run() }
        val (resp, stream) = client.sendRequest(get("https://http2.akamai.com/"), true)
        stream.close()

        val pushed = resp.pushPromises()
        var count = 0
        while (true) {
            val p = pushed.pushPromise() ?: break
            p.response.await().body.close()
            count++
        }
        // CONTENT_LENGTH = 0 is ok
        assertEquals(1, count)
        pushed.close()
        resp.close()
        mock.join()
    }

    @Test
    @Ignore // ignored in the reference (empty): if server is foo.com, :authority = bar.com is stream error
    fun recvPushPromiseWithWrongAuthorityIsStreamError() {
    }

    @Test
    fun recvPushPromiseSkippedStreamId() = h2Test {
        val (io, srv) = mockNew()
        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.pushPromise(1, 4).request("GET", "https://http2.akamai.com/style.css"))
            srv.sendFrame(Frames.pushPromise(1, 2).request("GET", "https://http2.akamai.com/style.css"))
            srv.recvFrame(Frames.goAway(0).protocolError())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)

        val req = launch {
            val (fut, stream) = client.sendRequest(get("https://http2.akamai.com/"), true)
            stream.close()
            val err = assertFailsWith<H2Error> { fut.await() }
            assertEquals("connection error detected: unspecific protocol error detected", err.message)
        }

        // client should see a protocol error
        val err = assertFailsWith<H2Error> { h2.run() }
        assertEquals("connection error detected: unspecific protocol error detected", err.message)

        req.join()
        mock.join()
    }

    @Test
    fun recvPushPromiseDupStreamId() = h2Test {
        val (io, srv) = mockNew()
        val mock = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.pushPromise(1, 2).request("GET", "https://http2.akamai.com/style.css"))
            srv.sendFrame(Frames.pushPromise(1, 2).request("GET", "https://http2.akamai.com/style.css"))
            srv.recvFrame(Frames.goAway(0).protocolError())
            srv.close()
        }

        val (client, h2) = neton.http.h2.client.handshake(io)

        val req = launch {
            val (fut, stream) = client.sendRequest(get("https://http2.akamai.com/"), true)
            stream.close()
            val err = assertFailsWith<H2Error> { fut.await() }
            assertEquals("connection error detected: unspecific protocol error detected", err.message)
        }

        // client should see a protocol error
        val err = assertFailsWith<H2Error> { h2.run() }
        assertEquals("connection error detected: unspecific protocol error detected", err.message)

        req.join()
        mock.join()
    }
}
