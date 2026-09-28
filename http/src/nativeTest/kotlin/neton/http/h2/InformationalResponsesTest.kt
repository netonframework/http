package neton.http.h2

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.http.Method
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.h2.frame.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// Ported from h2 0.4.19 tests/h2-tests/tests/informational_responses.rs (7 tests).

class InformationalResponsesTest {
    @Test
    fun send100Continue() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())

            // Send a POST request
            client.sendFrame(Frames.headers(1).request("POST", "https://example.com/"))

            // Expect 100 Continue response first
            client.recvFrame(Frames.headers(1).response(100))

            // Send request body after receiving 100 Continue
            client.sendFrame(Frames.data(1, "request body").eos())

            // Expect final response
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val srv = neton.http.h2.server.handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!
        assertEquals(Method.POST, req.method)

        // Send 100 Continue informational response
        stream.sendInformational(Response.builder().status(StatusCode.CONTINUE).body(Unit))

        // Send final response
        stream.sendResponse(Response.builder().status(StatusCode.OK).body(Unit), true).close()

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun send103EarlyHints() = h2Test {
        val (io, client) = mockNew()
        val link = "</style.css>; rel=preload; as=style, </script.js>; rel=preload; as=script"

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())

            // Send a GET request
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())

            // Expect 103 Early Hints response first
            client.recvFrame(Frames.headers(1).response(103).field("link", link))

            // Expect final response
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val srv = neton.http.h2.server.handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!
        assertEquals(Method.GET, req.method)

        // Send 103 Early Hints informational response
        stream.sendInformational(Response.builder().status(StatusCode.EARLY_HINTS).header("link", link).body(Unit))

        // Send final response
        stream.sendResponse(Response.builder().status(StatusCode.OK).body(Unit), true).close()

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun sendMultipleInformationalResponses() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())

            client.sendFrame(Frames.headers(1).request("POST", "https://example.com/"))

            // Expect 100 Continue
            client.recvFrame(Frames.headers(1).response(100))

            client.sendFrame(Frames.data(1, "request body").eos())

            // Expect 103 Early Hints
            client.recvFrame(Frames.headers(1).response(103).field("link", "</style.css>; rel=preload; as=style"))

            // Expect final response
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val srv = neton.http.h2.server.handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!
        assertEquals(Method.POST, req.method)

        // Send 100 Continue
        stream.sendInformational(Response.builder().status(StatusCode.CONTINUE).body(Unit))

        // Send 103 Early Hints
        stream.sendInformational(
            Response.builder().status(StatusCode.EARLY_HINTS).header("link", "</style.css>; rel=preload; as=style").body(Unit),
        )

        // Send final response
        stream.sendResponse(Response.builder().status(StatusCode.OK).body(Unit), true).close()

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun invalidInformationalStatusReturnsError() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())

            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())

            // Should only receive the final response since invalid informational response errors out
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val srv = neton.http.h2.server.handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!
        assertEquals(Method.GET, req.method)

        // Try to send invalid informational response (200 is not 1xx): an error
        val err = assertFailsWith<H2Error> {
            stream.sendInformational(Response.builder().status(StatusCode.OK).body(Unit))
        }
        assertTrue(err.message.contains("invalid informational status code"))

        // Send actual final response after error
        stream.sendResponse(Response.builder().status(StatusCode.OK).body(Unit), true).close()

        assertNull(srv.accept())
        c.join()
    }

    @Test
    fun clientPollInformationalResponsesNone() = h2Test {
        val (io, srv) = mockNew()
        val sync = CompletableDeferred<Unit>()

        val server = launch {
            val recvSettings = srv.assertClientHandshake()
            assertFrameEq(recvSettings, Settings())

            srv.recvFrame(Frames.headers(1).request("GET", "https://example.com/").eos())

            // Send final response directly
            srv.sendFrame(Frames.headers(1).response(200))

            // The server may not close the stream immediately. Let's simulate this by waiting from client.
            // Continue after the client received the response headers
            withTimeout(4_000) { sync.await() }
            srv.sendFrame(Frames.data(1, "request body").eos())
            srv.close()
        }

        val (client, connection) = neton.http.h2.client.handshake(io)
        val request = Request.builder().method("GET").uri("https://example.com/").body(Unit)
        client.ready()
        val (responseFuture, stream) = client.sendRequest(request, true)
        stream.close()

        launch { connection.run() }

        // Poll for informational responses
        while (true) {
            val rsp = responseFuture.informational() ?: break
            fail("Unexpected informational response $rsp")
        }
        // Let the server continue sending responses
        sync.complete(Unit)

        // Get the final response
        val response = responseFuture.await()
        assertEquals(StatusCode.OK, response.status)
        val data = response.body.data()!!
        assertEquals("request body", data.decodeToString())
        server.join()
    }

    @Test
    fun clientPollInformationalResponses() = h2Test {
        val (io, srv) = mockNew()

        val server = launch {
            val recvSettings = srv.assertClientHandshake()
            assertFrameEq(recvSettings, Settings())

            srv.recvFrame(Frames.headers(1).request("GET", "https://example.com/").eos())

            // Send 103 Early Hints
            srv.sendFrame(Frames.headers(1).response(103).field("link", "</style.css>; rel=preload"))

            // Send final response
            srv.sendFrame(Frames.headers(1).response(200).eos())
            srv.close()
        }

        val (client, connection) = neton.http.h2.client.handshake(io)
        val request = Request.builder().method("GET").uri("https://example.com/").body(Unit)
        client.ready()
        val (responseFuture, stream) = client.sendRequest(request, true)
        stream.close()

        val conn = async { connection.run() }

        // Poll for informational responses
        while (true) {
            val info = responseFuture.informational() ?: break
            assertEquals(StatusCode.EARLY_HINTS, info.status)
            assertEquals("</style.css>; rel=preload", info.headers["link"]!!.toStr())
            break
        }

        // Get the final response
        val response = responseFuture.await()
        assertEquals(StatusCode.OK, response.status)
        conn.await()
        server.join()
    }

    @Test
    fun informationalResponsesWithBodyStreaming() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())

            client.sendFrame(Frames.headers(1).request("POST", "https://example.com/"))

            // Expect 100 Continue
            client.recvFrame(Frames.headers(1).response(100))

            client.sendFrame(Frames.data(1, "chunk1"))

            // Expect 103 Early Hints while still receiving body
            client.recvFrame(Frames.headers(1).response(103).field("link", "</resource.js>; rel=preload"))

            client.sendFrame(Frames.data(1, "chunk2").eos())

            // Expect final response with streaming body
            client.recvFrame(Frames.headers(1).response(200))

            client.recvFrame(Frames.data(1, "response data").eos())
            client.close()
        }

        val srv = neton.http.h2.server.handshake(io)
        launch { srv.run() }
        val (req, stream) = srv.accept()!!
        assertEquals(Method.POST, req.method)

        // Send 100 Continue
        stream.sendInformational(Response.builder().status(StatusCode.CONTINUE).body(Unit))

        // Send 103 Early Hints while processing
        stream.sendInformational(
            Response.builder().status(StatusCode.EARLY_HINTS).header("link", "</resource.js>; rel=preload").body(Unit),
        )

        // Send final response with body
        val sendStream = stream.sendResponse(Response.builder().status(StatusCode.OK).body(Unit), false)
        sendStream.sendData(bytes("response data"), true)

        assertNull(srv.accept())
        c.join()
    }
}
