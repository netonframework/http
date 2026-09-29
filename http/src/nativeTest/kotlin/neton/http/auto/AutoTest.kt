package neton.http.auto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.http.Body
import neton.http.EmptyBody
import neton.http.FullBody
import neton.http.HttpError
import neton.http.Incoming
import neton.http.Request
import neton.http.Response
import neton.http.Version
import neton.http.h1.HyperScope
import neton.http.h1.Http1ServerConfig
import neton.http.h1.HttpService
import neton.http.h1.bytesOf
import neton.http.h1.concat
import neton.http.h1.http1Handshake
import neton.http.h1.hyperTest
import neton.http.h1.memoryServerConfig
import neton.http.h1.readToEnd
import neton.http.h1.readUntil
import neton.http.h1.s
import neton.http.h1.writeAll
import neton.http.h2.http2Handshake
import neton.http.testStreamPair
import neton.http.testTransportIsTcp
import neton.http.upgradeOn
import neton.io.bytes.Buffer
import neton.io.core.IoException
import neton.io.core.IoStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * hyper-util 0.1.20 `server::conn::auto` (its tests at the end of `auto/mod.rs`, then this library's additions: the
 * preface split and diverging late, EOF, h2c, upgrades, graceful shutdown per protocol, ALPN, the detection timeout).
 * Over memory streams, or loopback TCP with NETON_HTTP_TEST_TRANSPORT=tcp (SPEC §6).
 */
class AutoTest {
    private val body = "Hello, world!"
    private val hello = HttpService { Response.builder().body(FullBody(bytesOf(body)) as Body) }

    /** HTTP/1 timeouts off: memory streams have no read timeout (the timeout tests set their own). */
    private fun config() = AutoServerConfig(memoryServerConfig())

    private fun get(): Request<Body> = Request.get("http://127.0.0.1/").body(EmptyBody as Body)

    /** hyper-util `start_server` for one connection: the client end and the served connection's task. */
    private suspend fun HyperScope.startServer(h1Only: Boolean, h2Only: Boolean, service: HttpService = hello): Pair<IoStream, Deferred<Unit>> {
        val (server, client) = testStreamPair()
        val builder = config()
        val task = spawn {
            when {
                h1Only -> builder.http1Only().serveConnection(server, service).serve()
                h2Only -> builder.http2Only().serveConnection(server, service).serve()
                else -> builder.http2 { maxHeaderListSize(4096) }.serveConnectionWithUpgrades(server, service).serve()
            }
        }
        return client to task
    }

    private suspend fun HyperScope.h1Get(client: IoStream, req: Request<out Body> = get()): Response<Incoming> {
        val (sender, conn) = http1Handshake(client)
        spawn { conn.run() }
        return sender.sendRequest(req)
    }

    private suspend fun HyperScope.h2Get(client: IoStream, req: Request<out Body> = get()): Response<Incoming> {
        val (sender, conn) = http2Handshake(client)
        spawn { conn.run() }
        return sender.sendRequest(req)
    }

    /** Writes the first [slow] bytes one at a time with a pause after each, so the server reads them separately. */
    private class DribbleStream(inner: IoStream, private var slow: Int) : neton.http.h1.ForwardingStream(inner) {
        override suspend fun write(src: Buffer): Int {
            var n = 0
            while (slow > 0 && src.readableBytes > 0) {
                inner.write(Buffer(1).also { it.writeByte(src.readBytes(1)[0]) })
                inner.flush()
                delay(2)
                slow--
                n++
            }
            if (src.readableBytes > 0) n += inner.write(src)
            return n
        }

        override suspend fun writev(buffers: Array<Buffer>, count: Int): Long {
            var total = 0L
            for (i in 0 until count) if (buffers[i].readableBytes > 0) total += write(buffers[i])
            return total
        }
    }

    private suspend fun IoStream.sendSlowly(text: String) {
        for (b in text.encodeToByteArray()) {
            writeAll(byteArrayOf(b))
            delay(2)
        }
    }

    private val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"

    // ---- hyper-util's tests ----------------------------------------------------------------------------------------

    @Test
    fun configuration() {
        // One liner.
        AutoServerConfig().http1 { copy(keepAlive = true) }.http2 { keepAliveInterval(null) }
        // Using variable.
        val builder = AutoServerConfig()
        builder.http1 { copy(keepAlive = true) }
        builder.http2 { keepAliveInterval(null) }
        assertTrue(builder.http1.keepAlive)
        assertTrue(builder.isHttp1Available() && builder.isHttp2Available())
        assertTrue(AutoServerConfig().http1Only().let { it.isHttp1Available() && !it.isHttp2Available() })
        assertTrue(AutoServerConfig().http2Only().let { !it.isHttp1Available() && it.isHttp2Available() })
        assertFailsWith<IllegalStateException> { AutoServerConfig().http1Only().http2Only() }
    }

    @Test
    fun titleCaseHeadersConfiguration() = hyperTest {
        // Test title_case_headers can be set on the main builder
        assertTrue(AutoServerConfig().titleCaseHeaders(true).http1.titleCaseHeaders)
        // Can be combined with other configuration
        val builder = config().titleCaseHeaders(true).http1Only()
        // ... and reaches the wire.
        val (server, client) = testStreamPair()
        val task = spawn { builder.serveConnection(server, hello).serve() }
        client.writeAll("GET / HTTP/1.1\r\nhost: x\r\nconnection: close\r\n\r\n")
        val res = s(client.readToEnd())
        assertTrue(res.contains("Content-Length: 13\r\n"), res)
        task.await()
    }

    @Test
    fun preserveHeaderCaseConfiguration() {
        // Test preserve_header_case can be set on the main builder
        assertTrue(AutoServerConfig().preserveHeaderCase(true).http1.preserveHeaderCase)
        // Can be combined with other configuration
        val b = AutoServerConfig().preserveHeaderCase(true).http1Only()
        assertTrue(b.http1.preserveHeaderCase && !b.isHttp2Available())
    }

    @Test
    fun http1() = hyperTest {
        val (client, _) = startServer(h1Only = false, h2Only = false)
        val response = h1Get(client)
        assertEquals(Version.HTTP_11, response.version)
        assertEquals(body, s(response.body.concat()))
    }

    @Test
    fun http2() = hyperTest {
        val (client, _) = startServer(h1Only = false, h2Only = false)
        val response = h2Get(client)
        assertEquals(Version.HTTP_2, response.version)
        assertEquals(body, s(response.body.concat()))
    }

    @Test
    fun http2Only() = hyperTest {
        val (client, _) = startServer(h1Only = false, h2Only = true)
        assertEquals(body, s(h2Get(client).body.concat()))
    }

    @Test
    fun http2OnlyFailIfClientIsHttp1() = hyperTest {
        val (client, server) = startServer(h1Only = false, h2Only = true)
        assertFails("should fail") { h1Get(client) }
        assertFails { server.await() }
    }

    @Test
    fun http1Only() = hyperTest {
        val (client, _) = startServer(h1Only = true, h2Only = false)
        assertEquals(body, s(h1Get(client).body.concat()))
    }

    @Test
    fun http1OnlyFailIfClientIsHttp2() = hyperTest {
        val (client, server) = startServer(h1Only = true, h2Only = false)
        assertFails("should fail") { h2Get(client) }
        assertFails { server.await() }
    }

    @Test
    fun gracefulShutdown() = hyperTest {
        // Only connect a stream, do not send headers or anything
        val (stream, _client) = testStreamPair()
        val connection = config().serveConnection(stream, hello)
        connection.gracefulShutdown()
        val e = assertFailsWith<HttpError>("Connection should have been interrupted.") {
            withTimeout(200) { connection.serve() }
        }
        assertEquals(HttpError.Kind.Io, e.kind)
        assertEquals(4, (e.cause as IoException).errno, "the error should be Interrupted")
    }

    // ---- preface detection -----------------------------------------------------------------------------------------

    @Test
    fun prefaceSplitByteByByteIsHttp2() = hyperTest {
        val (server, client) = testStreamPair()
        val task = spawn { config().serveConnection(server, hello).serve() }
        val response = h2Get(DribbleStream(client, preface.length))
        assertEquals(Version.HTTP_2, response.version)
        assertEquals(body, s(response.body.concat()))
        client.close()
        withTimeout(5_000) { task.await() }
    }

    @Test
    fun http1RequestSharingThePrefaceDivergesLate() = hyperTest {
        // "PRI * HTTP/" matches the preface for 11 bytes; the '1' of the version is the first byte that differs.
        val (server, client) = testStreamPair()
        val task = spawn {
            config().serveConnection(server) { req ->
                Response.builder().body(FullBody(bytesOf("${req.method.asStr()} ${req.uri} ${req.version}")) as Body)
            }.serve()
        }
        client.sendSlowly("PRI * HTTP/1.1\r\nhost: x\r\nconnection: close\r\n\r\n")
        val res = s(client.readToEnd())
        assertTrue(res.startsWith("HTTP/1.1 200 OK\r\n") && res.endsWith("PRI * HTTP/1.1"), res)
        withTimeout(5_000) { task.await() }
    }

    @Test
    fun prefaceDifferingInItsLastByteIsHttp1() = hyperTest {
        val (server, client) = testStreamPair()
        val task = spawn { config().serveConnection(server, hello).serve() }
        client.sendSlowly(preface.dropLast(1) + "X")
        // HTTP/1 got every byte: an HTTP/2.0 request line, not the whole preface (so not hyper's `VersionH2`).
        val e = assertFailsWith<HttpError> { withTimeout(5_000) { task.await() } }
        assertEquals(HttpError.Kind.ParseVersion, e.kind)
        val res = s(client.readToEnd())
        assertTrue(res.startsWith("HTTP/1.1 400 Bad Request\r\n"), res)          // hyper `on_error`: Version → 400
    }

    @Test
    fun bytesAfterThePrefaceInTheSameReadAreReplayed() = hyperTest {
        // The whole client preface (connection preface + SETTINGS) and a request in one write.
        val (server, client) = testStreamPair()
        val task = spawn { config().serveConnection(server, hello).serve() }
        val response = h2Get(client)
        assertEquals(body, s(response.body.concat()))
        // And an HTTP/1 request longer than the preface, in one write, reaches HTTP/1 whole.
        val (server2, client2) = testStreamPair()
        val task2 = spawn { config().serveConnection(server2) { req -> Response.builder().body(FullBody(neton.io.bytes.Bytes.copyOf(req.body.concat())) as Body) }.serve() }
        val payload = "x".repeat(100)
        client2.writeAll("POST /upload HTTP/1.1\r\nhost: x\r\ncontent-length: 100\r\nconnection: close\r\n\r\n$payload")
        assertTrue(s(client2.readToEnd()).endsWith(payload))
        withTimeout(5_000) { task2.await() }
        client.close()
        withTimeout(5_000) { task.await() }
    }

    @Test
    fun emptyConnectionClosesImmediately() = hyperTest {
        val (server, client) = testStreamPair()
        val task = spawn { config().serveConnection(server, hello).serve() }
        client.shutdownOutput()
        withTimeout(2_000) { task.await() }                  // EOF before any byte: HTTP/1, which ends cleanly
        assertEquals(0, client.readToEnd().size)
    }

    @Test
    fun partialPrefaceThenEofIsAnIncompleteHttp1Message() = hyperTest {
        val (server, client) = testStreamPair()
        val task = spawn { config().serveConnection(server, hello).serve() }
        client.writeAll("PRI * HT")
        client.shutdownOutput()
        val e = assertFailsWith<HttpError> { withTimeout(2_000) { task.await() } }
        assertTrue(e.isIncompleteMessage(), e.toString())
    }

    @Test
    fun h2cPriorKnowledgeConcurrentStreams() = hyperTest {
        val (server, client) = testStreamPair()
        val task = spawn {
            config().serveConnection(server) { req ->
                Response.builder().body(FullBody(bytesOf("${req.uri.path}:${s(req.body.concat())}")) as Body)
            }.serve()
        }
        val (sender, conn) = http2Handshake(client)
        spawn { conn.run() }
        val all = (1..5).map { i ->
            spawn {
                val req = Request.post("http://127.0.0.1/s$i").body(FullBody(bytesOf("b$i")) as Body)
                val res = sender.sendRequest(req)
                assertEquals(Version.HTTP_2, res.version)
                s(res.body.concat())
            }
        }
        assertEquals((1..5).map { "/s$it:b$it" }, all.map { it.await() })
        sender.close()
        withTimeout(5_000) { task.await() }
    }

    @Test
    fun http1KeepAliveThroughAuto() = hyperTest {
        val (server, client) = testStreamPair()
        val task = spawn { config().serveConnection(server) { req -> Response.builder().body(FullBody(bytesOf(req.uri.path)) as Body) }.serve() }
        val (sender, conn) = http1Handshake(client)
        spawn { conn.run() }
        for (i in 1..3) {
            sender.ready()
            assertEquals("/r$i", s(sender.sendRequest(Request.get("http://127.0.0.1/r$i").body(EmptyBody as Body)).body.concat()))
        }
        client.close()
        withTimeout(5_000) { runCatching { task.await() } }
    }

    // ---- upgrades --------------------------------------------------------------------------------------------------

    @Test
    fun http1UpgradeThroughAuto() = hyperTest {
        val (server, client) = testStreamPair()
        val upgraded = CompletableDeferred<Pair<IoStream, String>>()
        val task = spawn {
            config().serveConnectionWithUpgrades(server) { req ->
                launch {
                    val up = upgradeOn(req.extensions)
                    // The original stream (hyper-util needs `auto::upgrade::downcast` for this), the early bytes with it.
                    val (io, early) = up.downcast()
                    val acc = Buffer()
                    acc.writeBytes(early)
                    while (acc.readableBytes < 5) if (io.read(acc) < 0) break
                    upgraded.complete(io to acc.readAll().decodeToString())
                    io.writeAll("pong")
                    io.close()
                }
                Response.builder().status(101).header("upgrade", "x").header("connection", "upgrade").body(EmptyBody as Body)
            }.serve()
        }
        // The head and the first bytes of the new protocol in one write.
        client.writeAll("GET / HTTP/1.1\r\nhost: x\r\nupgrade: x\r\nconnection: upgrade\r\n\r\nearly")
        val head = s(client.readUntil { s(it).contains("\r\n\r\n") })
        assertTrue(head.startsWith("HTTP/1.1 101 Switching Protocols\r\n"), head)
        val (io, early) = withTimeout(5_000) { upgraded.await() }
        assertSame(server, io)
        assertEquals("early", early)
        assertEquals("pong", (head + s(client.readToEnd())).substringAfter("\r\n\r\n"))
        withTimeout(5_000) { task.await() }
    }

    @Test
    fun upgradeRequestWithoutUpgradesIsManual() = hyperTest {
        val (server, client) = testStreamPair()
        val outcome = CompletableDeferred<Throwable>()
        val task = spawn {
            config().serveConnection(server) { req ->
                launch { outcome.complete(runCatching { upgradeOn(req.extensions) }.exceptionOrNull()!!) }
                Response.builder().status(101).header("upgrade", "x").header("connection", "upgrade").body(EmptyBody as Body)
            }.serve()
        }
        client.writeAll("GET / HTTP/1.1\r\nhost: x\r\nupgrade: x\r\nconnection: upgrade\r\n\r\n")
        val e = withTimeout(5_000) { outcome.await() }
        assertEquals(HttpError.Kind.UserManualUpgrade, (e as HttpError).kind)
        client.close()
        withTimeout(5_000) { runCatching { task.await() } }
    }

    @Test
    fun onlyIsNotUsedWithUpgrades() = hyperTest {
        // hyper-util: `http2_only` "does not do anything if used with serve_connection_with_upgrades".
        val (server, client) = testStreamPair()
        spawn { config().http2Only().serveConnectionWithUpgrades(server, hello).serve() }
        assertEquals(body, s(h1Get(client).body.concat()))
    }

    // ---- graceful shutdown -----------------------------------------------------------------------------------------

    @Test
    fun gracefulShutdownHttp1InFlight() = hyperTest {
        val (server, client) = testStreamPair()
        val received = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val conn = config().serveConnection(server) {
            received.complete(Unit)
            release.await()
            Response.builder().body(FullBody(bytesOf(body)) as Body)
        }
        val task = spawn { conn.serve() }
        client.writeAll("GET / HTTP/1.1\r\nhost: x\r\n\r\n")
        received.await()
        conn.gracefulShutdown()
        release.complete(Unit)
        val res = s(client.readToEnd())
        assertTrue(res.startsWith("HTTP/1.1 200 OK\r\n") && res.contains("connection: close\r\n") && res.endsWith(body), res)
        withTimeout(5_000) { task.await() }
    }

    @Test
    fun gracefulShutdownHttp1Idle() = hyperTest {
        val (server, client) = testStreamPair()
        val conn = config().serveConnection(server, hello)
        val task = spawn { conn.serve() }
        client.writeAll("GET / HTTP/1.1\r\nhost: x\r\n\r\n")
        client.readUntil { s(it).endsWith(body) }
        delay(20)
        assertFalse(task.isCompleted)
        conn.gracefulShutdown()
        withTimeout(5_000) { task.await() }
        assertEquals(0, client.readToEnd().size)
    }

    @Test
    fun gracefulShutdownHttp2InFlight() = hyperTest {
        val (server, client) = testStreamPair()
        val received = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val conn = config().serveConnection(server) {
            received.complete(Unit)
            release.await()
            Response.builder().body(FullBody(bytesOf(body)) as Body)
        }
        val task = spawn { conn.serve() }
        val (sender, clientConn) = http2Handshake(client)
        val clientTask = spawn { clientConn.run() }
        val response = spawn { sender.sendRequest(get()) }
        received.await()
        conn.gracefulShutdown()
        release.complete(Unit)
        assertEquals(body, s(response.await().body.concat()))
        // The GOAWAY ends the connection once the stream is done (the client may see the close as an error).
        withTimeout(5_000) { task.await() }
        withTimeout(5_000) { runCatching { clientTask.await() } }
    }

    @Test
    fun gracefulShutdownWhileDetecting() = hyperTest {
        val (server, client) = testStreamPair()
        val conn = config().serveConnection(server, hello)
        val task = spawn { conn.serve() }
        client.writeAll("PRI * ")                          // a partial preface: still detecting
        delay(50)
        assertFalse(task.isCompleted)
        conn.gracefulShutdown()
        val e = assertFailsWith<HttpError> { withTimeout(1_000) { task.await() } }
        assertEquals(4, (e.cause as IoException).errno)
        assertEquals(0, client.readToEnd().size)            // the stream is closed
    }

    @Test
    fun gracefulShutdownBeforeServeWithAKnownProtocol() = hyperTest {
        // hyper-util binds a decided protocol at once; its graceful shutdown then applies (an idle HTTP/1 connection
        // closes cleanly instead of the detection's `Cancelled`).
        val (server, _client) = testStreamPair()
        val conn = config().http1Only().serveConnection(server, hello)
        conn.gracefulShutdown()
        withTimeout(1_000) { conn.serve() }
    }

    // ---- ALPN (⚖️) -------------------------------------------------------------------------------------------------

    @Test
    fun alpnH2ServesHttp2WithoutReadingFirst() = hyperTest {
        val (server, client) = testStreamPair()
        val task = spawn { config().serveConnection(server, "h2", hello).serve() }
        // Nothing sent: the server's SETTINGS arrive anyway (detection would wait for the client's bytes).
        val acc = Buffer()
        while (acc.readableBytes < 9) check(client.read(acc) >= 0)
        assertEquals(4, acc.getByte(3).toInt(), "a SETTINGS frame")
        client.close()
        runCatching { withTimeout(5_000) { task.await() } }
        // And a full exchange.
        val (server2, client2) = testStreamPair()
        spawn { config().serveConnection(server2, "h2", hello).serve() }
        assertEquals(body, s(h2Get(client2).body.concat()))
    }

    @Test
    fun alpnHttp11ServesHttp1WithoutDetection() = hyperTest {
        val (server, client) = testStreamPair()
        spawn { config().serveConnection(server, "http/1.1", hello).serve() }
        assertEquals(body, s(h1Get(client).body.concat()))
        // A peer that negotiated http/1.1 but sends the HTTP/2 preface gets HTTP/1 (no detection): `VersionH2`.
        val (server2, client2) = testStreamPair()
        val task2 = spawn { config().serveConnection(server2, "http/1.1", hello).serve() }
        client2.writeAll(preface)
        val e = assertFailsWith<HttpError> { withTimeout(5_000) { task2.await() } }
        assertTrue(e.isParseVersionH2(), e.toString())
    }

    @Test
    fun alpnWithUpgradesAndOnly() = hyperTest {
        // With upgrades, ALPN still decides.
        val (server, client) = testStreamPair()
        spawn { config().serveConnectionWithUpgrades(server, "h2", hello).serve() }
        assertEquals(Version.HTTP_2, h2Get(client).version)
        // http1Only / http2Only come first (hyper-util never looks further).
        val (server2, client2) = testStreamPair()
        spawn { config().http1Only().serveConnection(server2, "h2", hello).serve() }
        assertEquals(Version.HTTP_11, h1Get(client2).version)
        // Another protocol name: detected.
        val (server3, client3) = testStreamPair()
        spawn { config().serveConnection(server3, "acme-tls/1", hello).serve() }
        assertEquals(Version.HTTP_2, h2Get(client3).version)
    }

    // ---- detection timeout (⚖️) ------------------------------------------------------------------------------------

    @Test
    fun detectionIsBoundedByTheHeaderReadTimeout() = hyperTest {
        val (server, client) = testStreamPair()
        val cfg = AutoServerConfig(Http1ServerConfig(headerReadTimeoutMillis = 200, keepAliveIdleTimeoutMillis = 0))
        if (!testTransportIsTcp) {
            // Memory streams have no read timeout: refused, as HTTP/1 refuses it.
            assertFailsWith<IllegalArgumentException> { cfg.serveConnection(server, hello).serve() }
            return@hyperTest
        }
        val task = spawn { cfg.serveConnection(server, hello).serve() }
        client.writeAll("PRI * HTTP/2")                     // a slow client stuck in the preface
        val e = assertFailsWith<HttpError> { withTimeout(5_000) { task.await() } }
        assertEquals(HttpError.Kind.HeaderTimeout, e.kind)
        assertEquals(0, client.readToEnd().size)
    }

    @Test
    fun detectionTimeoutIsLiftedForHttp2() = hyperTest {
        if (!testTransportIsTcp) return@hyperTest           // needs a read timeout (TCP)
        val (server, client) = testStreamPair()
        val cfg = AutoServerConfig(Http1ServerConfig(headerReadTimeoutMillis = 200, keepAliveIdleTimeoutMillis = 0))
        spawn { cfg.serveConnection(server, hello).serve() }
        val (sender, conn) = http2Handshake(client)
        spawn { conn.run() }
        assertEquals(body, s(sender.sendRequest(get()).body.concat()))
        delay(400)                                          // idle past the detection timeout
        assertEquals(body, s(sender.sendRequest(get()).body.concat()))
    }
}
