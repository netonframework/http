package neton.http.h2

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import neton.http.Body
import neton.http.EmptyBody
import neton.http.Frame
import neton.http.HttpError
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.h1.HttpService
import neton.http.h1.HyperScope
import neton.http.h1.bytesOf
import neton.http.h1.connectLocal
import neton.http.h1.helloWorld
import neton.http.h1.hyperTest
import neton.http.h1.listenLocal
import neton.http.h1.readOnce
import neton.http.h1.readToEnd
import neton.http.h1.s
import neton.http.h1.serve
import neton.http.h1.unreachableService
import neton.http.h1.writeAll
import neton.http.h2.frame.Reason
import neton.http.upgradeOn
import neton.io.core.IoException
import neton.io.core.IoStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * hyper 1.11.1 `tests/server.rs`, the HTTP/2 tests, test for test (names are the Rust names in camelCase). The raw h2
 * clients of the reference are the h2 client of this package (`neton.http.h2.client`); hyper's clients are
 * [http2Handshake] / [h2Get] (`TestClient::new().http2_only()`), its servers [Http2ServerConfig] / [serveH2]
 * (`serve_opts().http2()`). `http2` and `http2_only` in `tests/server.rs` are not tests but these harness options.
 *
 * - What hyper runs in spawned tasks is [HyperScope.spawn]ed; a spawned task whose outcome the reference ignores (a
 *   panic in a detached tokio task does not fail the test) is awaited here where that makes the test stricter, noted
 *   per test. Handles the reference drops at the end of a task are closed there.
 * - The keep-alive tests are scaled down four times (hyper's 1 s interval and timeout are [KA] here).
 * - Ported with the HTTP/1 tests (`neton.http.h1.HyperServerTest`): `http1_response_with_http2_version`, and
 *   `http1_only` in its preface form; [http1Only] here runs it with the HTTP/2 client as the reference does.
 */
class HyperH2ServerTest {

    // ---- the `response_body_lengths` module -------------------------------------------------------------------

    @Test
    fun http2AutoResponseWithKnownLength() = hyperTest {
        val server = serveH2()
        server.reply().body("Hello, World!")

        val res = h2Get(server.port)
        assertEquals("13", res.headers["content-length"]?.toStr())
        assertEquals(13L, res.body.sizeHint.exact)
    }

    @Test
    fun http2AutoResponseWithConflictingLengths() = hyperTest {
        val server = serveH2()
        server.reply().header("content-length", "10").body("Hello, World!")

        val res = h2Get(server.port)
        assertEquals("10", res.headers["content-length"]?.toStr())
        assertEquals(10L, res.body.sizeHint.exact)
    }

    @Test
    fun http2ImplicitEmptySizeHint() = hyperTest {
        val server = serveH2()
        server.reply()

        val res = h2Get(server.port)
        assertNull(res.headers["content-length"])
        assertEquals(0L, res.body.sizeHint.exact)
    }

    // ---- CONNECT tunnels over a raw h2 client ------------------------------------------------------------------

    /** A raw h2 client connection to [port] (`h2::client::handshake`), its connection task spawned, ready. */
    private suspend fun HyperScope.h2Client(
        port: Int,
        builder: neton.http.h2.client.Builder = neton.http.h2.client.Builder(),
    ): neton.http.h2.client.SendRequest {
        val (h2, connection) = builder.handshake(connectLocal(port))
        spawn { connection.run() }
        h2.ready()
        return h2
    }

    private fun connectRequest(authority: String = "localhost"): Request<Unit> = Request.connect(authority).body(Unit)

    /** The reference's `connect_and_recv_bread`. */
    private suspend fun connectAndRecvBread(h2: neton.http.h2.client.SendRequest): Pair<RecvStream, SendStream> {
        val (response, sendStream) = h2.sendRequest(connectRequest(), false)
        val res = response.await()
        assertEquals(StatusCode.OK, res.status)

        val body = res.body
        val bytes = body.data()!!
        assertEquals("Bread?", s(bytes.toByteArray()))
        releaseCapacity(body, bytes.size)
        return body to sendStream
    }

    /** `let _ = body.flow_control().release_capacity(n)`. */
    private fun releaseCapacity(body: RecvStream, n: Int) {
        try {
            body.flowControl().releaseCapacity(n)
        } catch (_: H2Error) {
        }
    }

    /** A service answering 200 with an empty body, handing each request to [onRequest] in a spawned task. */
    private fun HyperScope.connectService(tasks: MutableList<Deferred<Unit>>, onRequest: suspend (Request<neton.http.Incoming>) -> Unit) =
        HttpService { req ->
            tasks.add(spawn { onRequest(req) })
            Response.builder().status(200).body(EmptyBody as Body)
        }

    /** `TokioIo::new(on_upgrade.await.expect("on_upgrade"))`, closed (dropped) after [block]. */
    private suspend fun withUpgraded(req: Request<neton.http.Incoming>, block: suspend (IoStream) -> Unit) {
        val upgraded = upgradeOn(req.extensions)
        try {
            block(upgraded)
        } finally {
            upgraded.close()
        }
    }

    /** Serves the next connection of [listener] with hyper's HTTP/2 server; returns its result. */
    private suspend fun HyperScope.serveOne(listener: neton.io.net.TcpListener, svc: HttpService, config: Http2ServerConfig = Http2ServerConfig()) {
        val socket = listener.accept().closeAtEnd()
        config.serveConnection(socket, svc).serve()
    }

    /** The client and server tasks of the reference are awaited (stricter than the reference, which ignores them). */
    @Test
    fun h2Connect() = hyperTest {
        val (listener, port) = listenLocal()
        val h2 = h2Client(port)

        val client = spawn {
            val (recvStream, sendStream) = connectAndRecvBread(h2)

            sendStream.sendData(bytesOf("Baguette!"), true)

            assertEquals(0, recvStream.data()!!.size)
            recvStream.close()
            sendStream.close()
            h2.close()
        }

        val tasks = ArrayList<Deferred<Unit>>()
        val svc = connectService(tasks) { req ->
            withUpgraded(req) { upgraded ->
                upgraded.writeAll("Bread?")

                assertEquals("Baguette!", s(upgraded.readToEnd()))

                upgraded.shutdownOutput()
            }
        }

        serveOne(listener, svc)
        client.await()
        tasks.awaitAll()
    }

    /**
     * The client's per-stream futures are awaited, as the reference's spawned task awaits them; the server tasks (not
     * observed by the reference) are awaited too. For `localhost_1` / `localhost_2` see the comment inside: the
     * reference's expectation contradicts its own implementation.
     */
    @Test
    fun h2ConnectMultiplex() = hyperTest {
        val (listener, port) = listenLocal()
        val h2 = h2Client(port)

        val client = spawn {
            val streams = (0 until 80).map { i ->
                val (response, sendStream) = h2.sendRequest(connectRequest("localhost_${i % 4}"), false)
                Triple(i, response, sendStream)
            }

            coroutineScope {
                streams.map { (i, response, sendStream) ->
                    async {
                        var body: RecvStream? = null
                        try {
                            if (i % 4 == 0) return@async

                            val res = response.await()
                            assertEquals(StatusCode.OK, res.status)
                            body = res.body

                            if (i % 4 == 1) return@async

                            val bytes = res.body.data()!!
                            assertEquals("Bread?", s(bytes.toByteArray()))
                            releaseCapacity(res.body, bytes.size)

                            if (i % 4 == 2) return@async

                            sendStream.sendData(bytesOf("Baguette!"), true)

                            assertEquals(0, res.body.data()!!.size)
                        } finally {
                            // The future ends: what it holds is dropped.
                            body?.close()
                            response.close()
                            sendStream.close()
                        }
                    }
                }.awaitAll()
            }
            h2.close()
        }

        val tasks = ArrayList<Deferred<Unit>>()
        val svc = HttpService { req ->
            val authority = req.uri.authority!!.toString()
            tasks += spawn {
                val upgradeRes = runCatching { upgradeOn(req.extensions) }
                if (authority == "localhost_0") {
                    val e = upgradeRes.exceptionOrNull() as? HttpError ?: error("upgrade cancelled: $upgradeRes")
                    assertTrue(e.isCanceled(), "$e")
                    return@spawn
                }
                val upgraded = upgradeRes.getOrThrow()
                try {
                    upgraded.writeAll("Bread?")

                    val readRes = runCatching { upgraded.readToEnd() }

                    if (authority == "localhost_1" || authority == "localhost_2") {
                        // The client dropped the stream: RST_STREAM(CANCEL). The reference expects the read to fail
                        // with an h2 error of reason CANCEL, but its own `H2Upgraded::poll_read`
                        // (`src/proto/h2/upgrade.rs`) ends the read cleanly (EOF) on a NO_ERROR or CANCEL reset, so its
                        // `expect_err` panics, in a detached task whose panic the test never sees. This port follows
                        // the implementation: the read ends with nothing read.
                        assertEquals(0, readRes.getOrThrow().size)
                        return@spawn
                    }

                    assertEquals("Baguette!", s(readRes.getOrThrow()))

                    upgraded.shutdownOutput()
                } finally {
                    upgraded.close()
                }
            }
            Response.builder().status(200).body(EmptyBody as Body)
        }

        serveOne(listener, svc)
        client.await()
        tasks.awaitAll()
    }

    /** The client task of the reference is awaited. */
    @Test
    fun h2ConnectLargeBody() = hyperTest {
        val noBread = "All work and no bread makes nox a dull boy.\n"
        val (listener, port) = listenLocal()
        val h2 = h2Client(port)

        val client = spawn {
            val (recvStream, sendStream) = connectAndRecvBread(h2)

            val largeBody = bytesOf(noBread.repeat(9000))

            sendStream.sendData(largeBody, false)
            sendStream.sendData(largeBody, true)

            assertEquals(0, recvStream.data()!!.size)
            recvStream.close()
            sendStream.close()
            h2.close()
        }

        val tasks = ArrayList<Deferred<Unit>>()
        val svc = connectService(tasks) { req ->
            withUpgraded(req) { upgraded ->
                upgraded.writeAll("Bread?")

                val vec = try {
                    upgraded.readToEnd()
                } catch (_: IoException) {
                    return@withUpgraded
                }
                assertEquals(noBread.length * 9000 * 2, vec.size)

                upgraded.shutdownOutput()
            }
        }

        serveOne(listener, svc)
        client.await()
        tasks.awaitAll()
    }

    /** The client and server tasks of the reference are awaited. */
    @Test
    fun h2ConnectEmptyFrames() = hyperTest {
        val (listener, port) = listenLocal()
        val h2 = h2Client(port)

        val client = spawn {
            val (recvStream, sendStream) = connectAndRecvBread(h2)

            sendStream.sendData(bytesOf(""), false)
            sendStream.sendData(bytesOf(""), false)
            sendStream.sendData(bytesOf(""), false)
            sendStream.sendData(bytesOf("Baguette!"), false)
            sendStream.sendData(bytesOf(""), true)

            assertEquals(0, recvStream.data()!!.size)
            recvStream.close()
            sendStream.close()
            h2.close()
        }

        val tasks = ArrayList<Deferred<Unit>>()
        val svc = connectService(tasks) { req ->
            withUpgraded(req) { upgraded ->
                upgraded.writeAll("Bread?")

                assertEquals("Baguette!", s(upgraded.readToEnd()))

                upgraded.shutdownOutput()
            }
        }

        serveOne(listener, svc)
        client.await()
        tasks.awaitAll()
    }

    /** Reads a tunnel's DATA until an empty frame or the end, releasing capacity; the bytes received. */
    private suspend fun readTunnel(body: RecvStream, onChunk: (ByteArray) -> Unit = {}): Int {
        var received = 0
        while (true) {
            val chunk = body.data() ?: break
            if (chunk.size == 0) break
            val len = chunk.size
            received += len
            onChunk(chunk.toByteArray())
            releaseCapacity(body, len)
        }
        return received
    }

    @Test
    fun h2ConnectBackpressureRespected() = hyperTest {
        val chunk = "backpressure test data chunk!\n"
        val totalLen = chunk.length * 2000
        val (listener, port) = listenLocal()
        val h2 = h2Client(port, neton.http.h2.client.Builder().initialWindowSize(1024).initialConnectionWindowSize(1024))

        val clientHandle = spawn {
            val (response, sendStream) = h2.sendRequest(connectRequest(), false)
            val res = response.await()
            assertEquals(StatusCode.OK, res.status)

            val received = readTunnel(res.body)

            assertEquals(totalLen, received)
            res.body.close()
            sendStream.close()
            h2.close()
        }

        val tasks = ArrayList<Deferred<Unit>>()
        val svc = connectService(tasks) { req ->
            withUpgraded(req) { upgraded ->
                repeat(2000) { upgraded.writeAll(chunk) }

                upgraded.shutdownOutput()
            }
        }

        serveOne(listener, svc)

        clientHandle.await()
        tasks.awaitAll()
    }

    @Test
    fun h2ConnectZeroWindowThenRelease() = hyperTest {
        val data = "Hello from upgraded stream"
        val (listener, port) = listenLocal()
        val h2 = h2Client(port, neton.http.h2.client.Builder().initialWindowSize(65535))

        val clientHandle = spawn {
            val (response, sendStream) = h2.sendRequest(connectRequest(), false)
            val res = response.await()
            assertEquals(StatusCode.OK, res.status)

            val received = ArrayList<Byte>()
            readTunnel(res.body) { received.addAll(it.toList()) }

            assertEquals(data, s(received.toByteArray()))
            res.body.close()
            sendStream.close()
            h2.close()
        }

        val tasks = ArrayList<Deferred<Unit>>()
        val svc = connectService(tasks) { req ->
            withUpgraded(req) { upgraded ->
                upgraded.writeAll(data)
                upgraded.shutdownOutput()
            }
        }

        serveOne(listener, svc)

        clientHandle.await()
        tasks.awaitAll()
    }

    @Test
    fun h2ConnectShutdownWhileSendBackpressured() = hyperTest {
        val (listener, port) = listenLocal()
        val h2 = h2Client(port, neton.http.h2.client.Builder().initialWindowSize(1024).initialConnectionWindowSize(1024))

        val shutdownTx = kotlinx.coroutines.CompletableDeferred<Boolean>()

        val clientHandle = spawn {
            val (response, sendStream) = h2.sendRequest(connectRequest(), false)
            val res = response.await()
            assertEquals(StatusCode.OK, res.status)

            val body = res.body
            val bytes = body.data()!!
            assertEquals(1024, bytes.size)

            // Do not release capacity. The server-side upgraded writer should still observe shutdown of its mpsc
            // sender instead of waiting for more h2 send capacity.
            val shutdownCompleted = runCatching { shutdownTx.await() }.getOrDefault(false)
            assertTrue(shutdownCompleted, "upgraded shutdown should not wait for h2 capacity after the writer closes")
            body.close()
            sendStream.close()
            h2.close()
        }

        val svc = HttpService { req ->
            spawn {
                withUpgraded(req) { upgraded ->
                    upgraded.writeAll(ByteArray(1024) { 'x'.code.toByte() })

                    // Regression trigger: shutdown closes the mpsc sender while the send task is already parked
                    // waiting for h2 capacity.
                    val shutdownCompleted = withTimeoutOrNull(1000) { upgraded.shutdownOutput() } != null

                    shutdownTx.complete(shutdownCompleted)
                }
            }
            Response.builder().status(200).body(EmptyBody as Body)
        }

        runCatching { serveOne(listener, svc) }

        clientHandle.await()
    }

    @Test
    fun h2ConnectResetDuringBackpressure() = hyperTest {
        val (listener, port) = listenLocal()
        val h2 = h2Client(port, neton.http.h2.client.Builder().initialWindowSize(1024).initialConnectionWindowSize(1024))

        val writeErrTx = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val resetTx = kotlinx.coroutines.CompletableDeferred<Unit>()

        val clientHandle = spawn {
            val (response, sendStream) = h2.sendRequest(connectRequest(), false)
            val res = response.await()
            assertEquals(StatusCode.OK, res.status)

            val body = res.body
            var received = 0
            while (received < 1024) {
                val bytes = body.data()!!
                received += bytes.size
            }
            assertEquals(1024, received)

            sendStream.sendReset(Reason.CANCEL)
            resetTx.complete(Unit)
            body.close()
            sendStream.close()

            val gotErr = runCatching { writeErrTx.await() }.getOrDefault(false)
            assertTrue(gotErr, "server write side should have observed RST_STREAM")
            h2.close()
        }

        val svc = HttpService { req ->
            spawn {
                withUpgraded(req) { upgraded ->
                    upgraded.writeAll(ByteArray(1024) { 'x'.code.toByte() })

                    resetTx.await()

                    val largeData = ByteArray(1024 * 1024) { 'x'.code.toByte() }
                    val write = runCatching { upgraded.writeAll(largeData) }
                    val shutdown = runCatching { upgraded.shutdownOutput() }

                    writeErrTx.complete(write.isFailure || shutdown.isFailure)
                }
            }
            Response.builder().status(200).body(EmptyBody as Body)
        }

        runCatching { serveOne(listener, svc) }

        clientHandle.await()
    }

    /** The server task of the reference is awaited. */
    @Test
    fun h2ConnectBackpressureBidirectional() = hyperTest {
        val pattern = "All work and no bread makes nox a dull boy.\n"
        val repeat = 500
        val expectedLen = pattern.length * repeat
        val (listener, port) = listenLocal()
        val h2 = h2Client(port, neton.http.h2.client.Builder().initialWindowSize(2048).initialConnectionWindowSize(4096))

        val clientHandle = spawn {
            val (response, sendStream) = h2.sendRequest(connectRequest(), false)
            val res = response.await()
            assertEquals(StatusCode.OK, res.status)

            val received = readTunnel(res.body)

            assertEquals(expectedLen, received)

            sendStream.sendData(bytesOf("client done"), true)
            res.body.close()
            sendStream.close()
            h2.close()
        }

        val tasks = ArrayList<Deferred<Unit>>()
        val svc = connectService(tasks) { req ->
            withUpgraded(req) { upgraded ->
                repeat(repeat) { upgraded.writeAll(pattern) }

                upgraded.shutdownOutput()

                assertEquals("client done", s(upgraded.readOnce()))
            }
        }

        serveOne(listener, svc)

        clientHandle.await()
        tasks.awaitAll()
    }

    // ---- versions --------------------------------------------------------------------------------------------------

    /** hyper connects with an HTTP/2-only client to an HTTP/1 server and expects an error. */
    @Test
    fun http1Only() = hyperTest {
        val server = serve()
        assertFailsWith<HttpError> { h2Get(server.port) }
    }

    // ---- errors sent as resets -------------------------------------------------------------------------------------

    @Test
    fun http2ServiceErrorSendsResetReason() = hyperTest {
        val server = serveH2()
        server.reply().error(H2Error.fromReason(Reason.INADEQUATE_SECURITY))

        val err = assertFailsWith<HttpError>("client.get") { h2Get(server.port) }

        assertEquals(Reason.INADEQUATE_SECURITY, err.h2Source().reason())
    }

    @Test
    fun http2BodyUserErrorSendsResetReason() = hyperTest {
        val server = serveH2()
        server.reply().body = object : Body {
            override suspend fun nextFrame(): Frame? = throw H2Error.fromReason(Reason.INADEQUATE_SECURITY)
        }

        val err = assertFailsWith<HttpError> {
            val res = h2Get(server.port)
            while (res.body.nextFrame() != null) {
            }
        }

        assertEquals(Reason.INADEQUATE_SECURITY, err.h2Source().reason())
    }

    // ---- keep-alive ------------------------------------------------------------------------------------------------

    /** The client preface and a SETTINGS frame header one byte short, as the reference writes it. */
    private suspend fun writePrefaceAndSettings(conn: IoStream, settings: ByteArray) {
        conn.writeAll("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")
        conn.writeAll(settings)
    }

    @Test
    fun http2KeepAliveDetectsUnresponsiveClient() = hyperTest {
        val (listener, port) = listenLocal()

        // Spawn a "client" conn that only reads until EOF
        spawn {
            val conn = connectLocal(port)

            // write h2 magic preface and settings frame
            writePrefaceAndSettings(conn, byteArrayOf(0, 0, 0, 4, 0, 0, 0, 0))

            // read until eof
            while (conn.readOnce().isNotEmpty()) {
            }
        }

        val socket = listener.accept().closeAtEnd()

        val err = assertFailsWith<HttpError>("serve_connection should error") {
            Http2ServerConfig()
                .keepAliveInterval(KA)
                .keepAliveTimeout(KA)
                .autoDateHeader(true)
                .serveConnection(socket, unreachableService)
                .serve()
        }

        assertTrue(err.isTimeout(), "$err")
    }

    /** hyper's server task `.expect("serve_connection")`: checked if it has ended by the end of the test. */
    @Test
    fun http2KeepAliveWithResponsiveClient() = hyperTest {
        val (listener, port) = listenLocal()

        val server = spawn {
            serveOne(listener, helloWorld, Http2ServerConfig().keepAliveInterval(KA).keepAliveTimeout(KA))
        }

        val (client, conn) = http2Handshake(connectLocal(port))
        spawn { conn.run() }

        delay(KA * 4)

        client.sendRequest(Request(EmptyBody as Body))
        if (server.isCompleted) server.await()
    }

    @Test
    fun http2CheckDateHeaderDisabled() = hyperTest {
        val (listener, port) = listenLocal()

        val server = spawn {
            serveOne(
                listener, helloWorld,
                Http2ServerConfig().keepAliveInterval(KA).autoDateHeader(false).keepAliveTimeout(KA),
            )
        }

        val (client, conn) = http2Handshake(connectLocal(port))
        spawn { conn.run() }

        delay(KA * 4)

        val resp = client.sendRequest(Request(EmptyBody as Body))

        assertNull(resp.headers["Date"])
        if (server.isCompleted) server.await()
    }

    private fun isPingFrame(buf: ByteArray): Boolean = buf[3] == 6.toByte()

    private fun assertPingFrame(buf: ByteArray, len: Int) {
        // Assert the StreamId is zero
        val unpacked = ((buf[5].toInt() and 0xff) shl 24) or ((buf[6].toInt() and 0xff) shl 16) or
            ((buf[7].toInt() and 0xff) shl 8) or (buf[8].toInt() and 0xff)
        assertEquals(0, unpacked and (1 shl 31).inv())

        // Assert ACK flag is unset (only set for PONG).
        val flags = buf[4].toInt()
        assertEquals(0, flags and 0x1)

        // Assert total frame size
        assertEquals(17, len)
    }

    private suspend fun writePongFrame(conn: IoStream) {
        conn.writeAll(
            byteArrayOf(
                0, 0, 8, // len
                6, // kind
                0x1, // flag
                0, 0, 0, 0, // stream id
                0x3b, 0x7c, 0xdb.toByte(), 0x7a, 0x0b, 0x87.toByte(), 0x16, 0xb4.toByte(), // payload
            ),
        )
    }

    @Test
    fun http2KeepAliveCountServerPings() = hyperTest {
        val (listener, port) = listenLocal()

        spawn {
            serveOne(listener, unreachableService, Http2ServerConfig().keepAliveInterval(KA).keepAliveTimeout(KA))
        }

        // Spawn a "client" conn that only reads until EOF
        val conn = connectLocal(port)

        // write h2 magic preface and settings frame
        writePrefaceAndSettings(conn, byteArrayOf(0, 0, 0, 4, 0, 0, 0, 0, 0))

        // Expect all pings to occurs under 5 intervals
        withTimeout(KA * 5) {
            // read until 3 pings are received
            var pings = 0
            while (pings < 3) {
                val buf = conn.readOnce()
                val n = buf.size
                assertTrue(n != 0)

                if (isPingFrame(buf)) {
                    assertPingFrame(buf, n)
                    writePongFrame(conn)
                    pings += 1
                }
            }
        }
    }

    private companion object {
        /** hyper's keep-alive interval and timeout (1 s), scaled down. */
        val KA: Duration = 250.milliseconds
    }
}
