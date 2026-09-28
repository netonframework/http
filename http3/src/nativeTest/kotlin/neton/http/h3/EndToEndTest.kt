package neton.http.h3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import neton.http.Request
import neton.http.StatusCode
import neton.http.h3.quic.ALPN_H3
import neton.http.h3.quic.ConnectionErrorIncoming
import neton.io.bytes.Bytes
import neton.io.net.SocketAddress
import neton.io.net.bindUdp
import neton.quic.Endpoint
import neton.quic.proto.ClientConfig
import neton.quic.proto.ConnectionError as QuicConnectionError
import neton.quic.proto.EndpointConfig
import neton.quic.proto.ServerConfig
import neton.quic.proto.VarInt
import neton.quic.proto.default
import neton.quic.proto.withCrypto
import neton.quic.testkit.MockClientCrypto
import neton.quic.testkit.MockServerCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import neton.http.h3.client.Connection as ClientConnection
import neton.http.h3.client.SendRequest
import neton.http.h3.client.newClient
import neton.http.h3.server.Connection as ServerConnection
import neton.http.h3.server.RequestStream as ServerRequestStream
import neton.http.h3.server.builder as serverBuilder
import neton.http.h3.server.newConnection

// SPEC §5 layer 3: an HTTP/3 client and server of this library over two neton.quic endpoints on loopback UDP. The
// QUIC handshake runs with the TLS test double of quic-testkit (both sides offer ALPN "h3"); there is no real TLS
// yet, so none of this is interop evidence (phase D). The connection-layer scenarios of phase B also run over
// neton.quic: ConnectionTestQuic, RequestTestQuic, ConnectionLayerTestQuic.

/** Deterministic body bytes: the byte at stream offset `i` is a function of `i`, so any slice can be checked. */
private fun patternByte(offset: Long): Byte = ((offset * 31 + 7) % 251).toByte()

private fun pattern(offset: Long, size: Int): Bytes = Bytes.wrap(ByteArray(size) { patternByte(offset + it) })

/** Checks that [chunk] continues the pattern at [offset]; returns the offset after it. */
private fun checkPattern(chunk: Bytes, offset: Long): Long {
    for (i in 0 until chunk.size) {
        if (chunk[i] != patternByte(offset + i)) fail("body byte ${offset + i} differs")
    }
    return offset + chunk.size
}

/** An HTTP/3 server and client over one neton.quic connection, with both drivers running. */
private class H3Pair(
    val loop: QuicLoopback,
    val server: ServerConnection,
    val serverDrive: kotlinx.coroutines.Deferred<ConnectionError>,
    val client: ClientConnection,
    val send: SendRequest,
    val clientDrive: kotlinx.coroutines.Deferred<ConnectionError>,
)

private suspend fun CoroutineScope.h3Pair(
    streamWindow: Int? = null,
    server: neton.http.h3.server.Builder = serverBuilder(),
): H3Pair {
    val loop = quicLoopback { if (streamWindow != null) streamReceiveWindow(VarInt(streamWindow.toLong())) }
    val s = server.build(loop.server)
    val serverDrive = async { s.run() }
    val (c, send) = newClient(loop.client)
    val clientDrive = async { c.run() }
    return H3Pair(loop, s, serverDrive, c, send, clientDrive)
}

/** Serves every request with [handler] (each in its own coroutine) until [accept] reports the end or the connection closes. */
private fun CoroutineScope.serve(
    conn: ServerConnection,
    handler: suspend (Request<Unit>, ServerRequestStream) -> Unit,
) = launch {
    while (true) {
        val resolver = try {
            conn.accept()
        } catch (e: ConnectionError) {
            null // the connection closed: the test checks how
        } ?: return@launch
        launch {
            val (req, stream) = resolver.resolveRequest()
            handler(req, stream)
        }
    }
}

class EndToEndTest {
    @Test
    fun simple_get() = h3Test {
        val h = h3Pair()
        serve(h.server) { req, stream ->
            assertEquals("/hello", req.uri.path)
            assertEquals(neton.http.Version.HTTP_3, req.version)
            stream.sendResponse(ok())
            stream.sendData(bytes("hello, h3"))
            stream.finish()
        }
        val stream = h.send.sendRequest(Request.get("https://localhost/hello").body(Unit))
        stream.finish()
        val resp = stream.recvResponse()
        assertEquals(StatusCode.OK, resp.status)
        assertEquals("hello, h3", stream.recvData()!!.decodeToString())
        assertNull(stream.recvData())
        assertNull(stream.recvTrailers())
        // The client closes its only sender: H3_NO_ERROR reaches the server.
        h.send.close()
        assertLocal(Code.H3_NO_ERROR, h.clientDrive.await())
        assertRemote(Code.H3_NO_ERROR, h.serverDrive.await())
        h.loop.shutdown()
    }

    /** Both bodies stream: every request chunk is echoed before the next is sent, so neither side buffers a body. */
    @Test
    fun post_streaming_body_both_ways() = h3Test {
        val h = h3Pair()
        val chunks = 64
        val chunkSize = 16 * 1024
        serve(h.server) { _, stream ->
            stream.sendResponse(ok())
            while (true) stream.sendData(stream.recvData() ?: break)
            stream.finish()
        }
        val stream = h.send.sendRequest(Request.post("https://localhost/echo").body(Unit))
        assertEquals(StatusCode.OK, stream.recvResponse().status, "the response starts before the request body")
        var received = 0L
        for (i in 0 until chunks) {
            val offset = i.toLong() * chunkSize
            stream.sendData(pattern(offset, chunkSize))
            // The echo of this chunk arrives while the request is still open.
            while (received < offset + chunkSize) received = checkPattern(stream.recvData()!!, received)
        }
        stream.finish()
        assertNull(stream.recvData())
        assertEquals(chunks.toLong() * chunkSize, received)
        h.loop.shutdown()
    }

    @Test
    fun trailers_both_ways() = h3Test {
        val h = h3Pair()
        serve(h.server) { _, stream ->
            val body = StringBuilder()
            while (true) body.append((stream.recvData() ?: break).decodeToString())
            val trailers = stream.recvTrailers()!!
            assertEquals("request body", body.toString())
            assertTrue(trailers["x-request-checksum"]!!.contentEquals("abc"))
            stream.sendResponse(ok())
            stream.sendData(bytes("response body"))
            stream.sendTrailers(headers("x-response-checksum" to "def", "x-status" to "done"))
            stream.finish()
        }
        val stream = h.send.sendRequest(Request.post("https://localhost/t").body(Unit))
        stream.sendData(bytes("request "))
        stream.sendData(bytes("body"))
        stream.sendTrailers(headers("x-request-checksum" to "abc"))
        stream.finish()
        assertEquals(StatusCode.OK, stream.recvResponse().status)
        assertEquals("response body", stream.recvData()!!.decodeToString())
        assertNull(stream.recvData())
        val trailers = stream.recvTrailers()!!
        assertTrue(trailers["x-response-checksum"]!!.contentEquals("def"))
        assertTrue(trailers["x-status"]!!.contentEquals("done"))
        h.loop.shutdown()
    }

    /**
     * 300 concurrent requests on one connection, three times QUIC's default limit of 100 concurrent bidirectional
     * streams: opening the 101st waits for the peer's MAX_STREAMS, and every request completes with its own body.
     */
    @Test
    fun many_concurrent_requests_on_one_connection() = h3Test(120.seconds) {
        val h = h3Pair()
        val n = 300
        val inFlight = intArrayOf(0, 0) // current, peak (server side)
        serve(h.server) { req, stream ->
            inFlight[0]++
            inFlight[1] = maxOf(inFlight[1], inFlight[0])
            val body = StringBuilder()
            while (true) body.append((stream.recvData() ?: break).decodeToString())
            assertEquals("request ${req.uri.path}", body.toString())
            delay(1) // keep requests overlapping
            stream.sendResponse(ok())
            stream.sendData(bytes("response ${req.uri.path}"))
            stream.finish()
            inFlight[0]--
        }
        val results = (0 until n).map { i ->
            async {
                val stream = h.send.sendRequest(Request.post("https://localhost/$i").body(Unit))
                stream.sendData(bytes("request /$i"))
                stream.finish()
                assertEquals(StatusCode.OK, stream.recvResponse().status)
                val body = StringBuilder()
                while (true) body.append((stream.recvData() ?: break).decodeToString())
                assertEquals("response /$i", body.toString())
                stream.id.value
            }
        }.awaitAll()
        assertEquals(n, results.toSet().size, "one stream per request")
        println("many_concurrent_requests_on_one_connection: peak ${inFlight[1]} requests in progress on the server")
        assertTrue(inFlight[1] > 1, "requests overlapped (peak ${inFlight[1]})")
        assertNull(h.client.error)
        assertNull(h.server.error)
        h.loop.shutdown()
    }

    /**
     * A body of several MiB each way over a 64 KiB stream receive window: while the reader does not read, the writer
     * is held back by QUIC flow control (a bounded amount is sent), and once it reads, every byte arrives intact.
     */
    @Test
    fun large_bodies_with_backpressure() = h3Test(120.seconds) {
        val window = 64 * 1024
        val total = 8L * 1024 * 1024
        val chunk = 32 * 1024
        val h = h3Pair(streamWindow = window)
        val serverMayRead = CompletableDeferred<Unit>()
        var serverSent = 0L
        var serverReceived = 0L
        serve(h.server) { _, stream ->
            serverMayRead.await()
            while (true) serverReceived = checkPattern(stream.recvData() ?: break, serverReceived)
            stream.sendResponse(ok())
            while (serverSent < total) {
                stream.sendData(pattern(serverSent, chunk))
                serverSent += chunk
            }
            stream.finish()
        }
        val stream = h.send.sendRequest(Request.post("https://localhost/upload").body(Unit))
        var clientSent = 0L
        val upload = launch {
            while (clientSent < total) {
                stream.sendData(pattern(clientSent, chunk))
                clientSent += chunk
            }
            stream.finish()
        }
        // The server does not read: the upload stalls within about one window.
        assertStalls("upload") { clientSent }
        println("large_bodies_with_backpressure: upload stalled at $clientSent bytes (window $window)")
        assertTrue(clientSent <= 2L * window, "upload held back by flow control: $clientSent bytes sent")
        serverMayRead.complete(Unit)
        upload.join()

        assertEquals(StatusCode.OK, stream.recvResponse().status)
        // The client does not read the response body: the download stalls within about one window.
        assertStalls("download") { serverSent }
        println("large_bodies_with_backpressure: download stalled at $serverSent bytes (window $window)")
        assertTrue(serverSent <= 2L * window, "download held back by flow control: $serverSent bytes sent")
        var received = 0L
        while (true) received = checkPattern(stream.recvData() ?: break, received)
        assertEquals(total, serverReceived)
        assertEquals(total, received)
        h.loop.shutdown()
    }

    /** Waits until [progress] stops moving (the writer is blocked), failing if it reaches nothing or never settles. */
    private suspend fun assertStalls(what: String, progress: () -> Long) {
        eventually(message = "$what never started") { progress() > 0 }
        var last = -1L
        var stableRounds = 0
        repeat(200) {
            val now = progress()
            if (now == last) stableRounds++ else stableRounds = 0
            last = now
            if (stableRounds >= 5) return
            delay(40.milliseconds)
        }
        fail("$what never stalled: $last bytes")
    }

    /** One of three streaming requests is cancelled by the client; the other two and the connection carry on. */
    @Test
    fun request_cancellation_is_isolated() = h3Test {
        val h = h3Pair()
        val cancelledSeen = CompletableDeferred<Code>()
        serve(h.server) { req, stream ->
            stream.sendResponse(ok())
            if (req.uri.path == "/cancel") {
                // The client stopped both directions: reading reports the reset, writing the stop.
                val err = failsWith<StreamError.RemoteTerminate> { while (true) stream.recvData() ?: break }
                failsWith<StreamError.RemoteTerminate> { while (true) stream.sendData(bytes("still there?")) }
                cancelledSeen.complete(err.code)
                return@serve
            }
            while (true) stream.sendData(stream.recvData() ?: break)
            stream.finish()
        }
        val streams = listOf("/a", "/cancel", "/b").associateWith {
            h.send.sendRequest(Request.post("https://localhost$it").body(Unit))
        }
        for ((_, s) in streams) {
            s.sendData(bytes("first "))
            assertEquals(StatusCode.OK, s.recvResponse().status)
        }
        val victim = streams.getValue("/cancel")
        victim.stopStream(Code.H3_REQUEST_CANCELLED)
        victim.stopSending(Code.H3_REQUEST_CANCELLED)
        assertEquals(Code.H3_REQUEST_CANCELLED, cancelledSeen.await())
        for (path in listOf("/a", "/b")) {
            val s = streams.getValue(path)
            s.sendData(bytes("second"))
            s.finish()
            val body = StringBuilder()
            while (true) body.append((s.recvData() ?: break).decodeToString())
            assertEquals("first second", body.toString())
        }
        // A new request on the same connection still works.
        val next = h.send.sendRequest(Request.post("https://localhost/after").body(Unit))
        next.sendData(bytes("after"))
        next.finish()
        assertEquals(StatusCode.OK, next.recvResponse().status)
        assertEquals("after", next.recvData()!!.decodeToString())
        assertNull(h.client.error)
        assertNull(h.server.error)
        h.loop.shutdown()
    }

    /**
     * Graceful shutdown: requests 0, 4 and 8 are in progress when the server sends GOAWAY; stream 12, sent before
     * the client knew, is exactly the GOAWAY ID and is rejected (the ⚖️ `>=` boundary, RFC 9114 §5.2); the three
     * complete; afterwards new requests fail with RemoteClosing and the server's accept() reports the end.
     */
    @Test
    fun goaway_graceful_shutdown() = h3Test {
        val h = h3Pair()
        val requests = (0 until 4).map { h.send.sendRequest(Request.get("https://localhost/$it").body(Unit)) }
        assertEquals(listOf(0L, 4L, 8L, 12L), requests.map { it.id.value })
        for (s in requests) s.finish()
        val accepted = (0 until 3).map { h.server.accept()!!.resolveRequest() }
        assertEquals(listOf(0L, 4L, 8L), accepted.map { it.second.id.value })
        // shutdown(0): nothing beyond the accepted ones; the GOAWAY carries 12.
        h.server.shutdown(0)
        val rejected = failsWith<StreamError.RemoteTerminate> { requests[3].recvResponse() }
        assertEquals(Code.H3_REQUEST_REJECTED, rejected.code)
        eventually(message = "client saw the GOAWAY") { h.client.isClosing }
        failsWith<StreamError.RemoteClosing> { h.send.sendRequest(Request.get("https://localhost/late").body(Unit)) }
        // The requests in progress complete normally.
        for ((req, stream) in accepted) {
            stream.sendResponse(ok())
            stream.sendData(bytes("done ${req.uri.path}"))
            stream.finish()
        }
        for ((i, s) in requests.take(3).withIndex()) {
            assertEquals(StatusCode.OK, s.recvResponse().status)
            assertEquals("done /$i", s.recvData()!!.decodeToString())
            assertNull(s.recvData())
        }
        assertNull(h.server.accept(), "no request left: accept() reports the end")
        h.server.close()
        assertRemote(Code.H3_NO_ERROR, h.clientDrive.await())
        assertLocal(Code.H3_NO_ERROR, h.serverDrive.await())
        h.loop.shutdown()
    }

    /** The server closes the connection while a response is awaited: the client's stream, driver and sender see it. */
    @Test
    fun connection_close_propagates() = h3Test {
        val h = h3Pair()
        val stream = h.send.sendRequest(Request.get("https://localhost/pending").body(Unit))
        stream.finish()
        h.server.accept()!!.resolveRequest()
        h.server.close()
        val err = failsWith<StreamError.Connection> { stream.recvResponse() }
        assertRemote(Code.H3_NO_ERROR, err.error)
        assertRemote(Code.H3_NO_ERROR, h.clientDrive.await())
        assertLocal(Code.H3_NO_ERROR, h.serverDrive.await())
        failsWith<StreamError> { h.send.sendRequest(Request.get("https://localhost/after").body(Unit)) }
        h.loop.shutdown()
    }

    /** A QUIC-level close (the whole server endpoint) reaches HTTP/3 as the peer's application close code. */
    @Test
    fun endpoint_close_propagates() = h3Test {
        val h = h3Pair()
        val stream = h.send.sendRequest(Request.get("https://localhost/pending").body(Unit))
        stream.finish()
        h.server.accept()!!.resolveRequest()
        h.loop.serverEndpoint.close(VarInt(Code.H3_INTERNAL_ERROR.value), "going away".encodeToByteArray())
        val err = assertIs<ConnectionError.Remote>(h.clientDrive.await())
        assertEquals(ConnectionErrorIncoming.ApplicationClose(Code.H3_INTERNAL_ERROR.value), err.error)
        failsWith<StreamError.Connection> { stream.recvResponse() }
        // The server side sees its own endpoint's close as a local QUIC close, which is not an HTTP/3 code.
        val local = assertIs<ConnectionError.Remote>(h.serverDrive.await())
        val undefined = assertIs<ConnectionErrorIncoming.Undefined>(local.error)
        assertEquals(QuicConnectionError.LocallyClosed, undefined.error)
        h.loop.shutdown()
    }

    /** Too many header fields (default limit 100): the server answers 431 and the connection serves the next request. */
    @Test
    fun too_many_fields_gets_431_and_connection_continues() = h3Test {
        val h = h3Pair()
        val refused = CompletableDeferred<StreamError.HeaderTooBig>()
        launch {
            val resolver = h.server.accept()!!
            refused.complete(failsWith<StreamError.HeaderTooBig> { resolver.resolveRequest() })
            val (_, stream) = h.server.accept()!!.resolveRequest()
            stream.sendResponse(ok())
            stream.finish()
        }
        val big = Request.get("https://localhost/big").apply { repeat(150) { header("x-field-$it", "v") } }.body(Unit)
        val s1 = h.send.sendRequest(big)
        assertEquals(StatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE, s1.recvResponse().status)
        assertNull(s1.recvData())
        val err = refused.await()
        assertEquals(101L, err.actualSize)
        assertEquals(100L, err.maxSize)
        val s2 = h.send.sendRequest(Request.get("https://localhost/small").body(Unit))
        s2.finish()
        assertEquals(StatusCode.OK, s2.recvResponse().status)
        assertNull(h.client.error)
        assertNull(h.server.error)
        h.loop.shutdown()
    }

    /** Without "h3" in common the QUIC handshake fails (no_application_protocol); HTTP/3 never starts. */
    @Test
    fun alpn_mismatch_fails_the_handshake() = h3Test {
        val serverEndpoint = Endpoint.create(
            EndpointConfig.default(),
            ServerConfig.withCrypto(MockServerCrypto(alpn = listOf(ALPN_H3))),
            bindUdp(SocketAddress.IPV4_LOCALHOST_ANY_PORT),
        )
        val clientEndpoint = Endpoint.create(EndpointConfig.default(), null, bindUdp(SocketAddress.IPV4_LOCALHOST_ANY_PORT))
        val h2Only = ClientConfig(MockClientCrypto(alpn = listOf("h2".encodeToByteArray())))
        val client = async { runCatching { clientEndpoint.connectWith(h2Only, serverEndpoint.localAddr(), "localhost").await() } }
        val server = runCatching { checkNotNull(serverEndpoint.accept()).await() }
        assertTrue(client.await().isFailure, "client handshake must fail")
        assertTrue(server.isFailure, "server handshake must fail")
        for (e in listOf(clientEndpoint, serverEndpoint)) {
            e.close(VarInt(0), ByteArray(0))
            e.close()
        }
    }
}
