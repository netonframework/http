package neton.http.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.http.Body
import neton.http.Frame
import neton.http.FullBody
import neton.http.Incoming
import neton.http.Request
import neton.http.Response
import neton.http.EmptyBody
import neton.http.h1.Http1ServerConfig
import neton.http.h1.HttpService
import neton.http.h1.serveHttp1
import neton.http.h2.serveHttp2
import neton.http.header.HeaderName
import neton.io.bytes.Bytes
import neton.io.net.listen
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The pooling client (SPEC §11 "HTTP 客户端连接池") against this library's servers over loopback TCP. */
class ClientPoolTest {
    /** A loopback server that counts the connections it accepted and tells each response which connection it came on. */
    private class TestServer(val port: Int) {
        var accepted = 0
        val requests = ArrayList<String>() // "<connection> <method> <target> host=<Host>"
    }

    private suspend fun CoroutineScope.server(
        h2: Boolean = false,
        config: Http1ServerConfig = Http1ServerConfig(),
        delayResponse: kotlin.time.Duration = kotlin.time.Duration.ZERO,
    ): TestServer {
        val listener = listen("127.0.0.1", 0)
        val s = TestServer(listener.localAddress.port)
        launch {
            try {
                while (true) {
                    val stream = listener.accept()
                    val n = s.accepted++
                    val service = HttpService { req ->
                        val target = req.uri.toString()
                        s.requests += "$n ${req.method} $target host=${req.headers.get(HeaderName.fromStr("host"))?.toStr()}"
                        if (delayResponse.isPositive()) delay(delayResponse)
                        Response.builder().body(FullBody(Bytes.wrap("conn $n".encodeToByteArray())) as Body)
                    }
                    launch {
                        try {
                            if (h2) serveHttp2(stream, service = service) else serveHttp1(stream, config, service)
                        } catch (e: Exception) {
                        }
                    }
                }
            } finally {
                listener.close()
            }
        }
        return s
    }

    private suspend fun Incoming.text(): String {
        val sb = StringBuilder()
        while (true) {
            val f = nextFrame() ?: break
            if (f is Frame.Data) sb.append(f.bytes.toByteArray().decodeToString())
        }
        return sb.toString()
    }

    private suspend fun Client.getText(url: String): String = request(Request.get(url).body(EmptyBody as Body)).body.text()

    /** The idle connections once those just used are back (they return within a few dispatches). */
    private suspend fun Client.idleSoon(): Map<String, Int> {
        repeat(50) {
            delay(2.milliseconds)
        }
        return idleConnections()
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runReactor {
        withTimeout(30.seconds) {
            coroutineScope {
                block()
                coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() }
            }
        }
    }

    @Test
    fun sequentialRequestsShareOneConnection() = test {
        val s = server()
        val client = Client.builder().build(this)
        val base = "http://127.0.0.1:${s.port}"
        repeat(5) { i -> assertEquals("conn 0", client.getText("$base/item?n=$i")) }
        assertEquals(1, s.accepted)
        assertEquals(1L, client.connectionsOpened)
        // Origin form and a Host header with the port
        assertEquals("0 GET /item?n=0 host=127.0.0.1:${s.port}", s.requests[0])
        assertEquals(mapOf("http://127.0.0.1:${s.port}" to 1), client.idleSoon())
        client.close()
    }

    @Test
    fun concurrentRequestsOpenConnectionsThatAllReturn() = test {
        val s = server(delayResponse = 100.milliseconds)
        val client = Client.builder().build(this)
        val base = "http://127.0.0.1:${s.port}"
        (0 until 3).map { async { client.getText("$base/c$it") } }.awaitAll()
        assertEquals(3, s.accepted, "${s.requests}")
        assertEquals(3, client.idleSoon().values.single())
        // Then sequential requests reuse them
        repeat(3) { client.getText("$base/again") }
        assertEquals(3, s.accepted)
        client.close()
    }

    @Test
    fun maxIdlePerHostBoundsWhatIsKept() = test {
        val s = server(delayResponse = 100.milliseconds)
        val client = Client.builder().poolMaxIdlePerHost(1).build(this)
        val base = "http://127.0.0.1:${s.port}"
        (0 until 3).map { async { client.getText("$base/c$it") } }.awaitAll()
        assertEquals(1, client.idleSoon().values.single())
        client.close()
    }

    @Test
    fun idleConnectionsExpire() = test {
        val s = server()
        val client = Client.builder().poolIdleTimeout(200.milliseconds).build(this)
        val base = "http://127.0.0.1:${s.port}"
        client.getText("$base/a")
        assertEquals(1, client.idleSoon().values.single())
        delay(600.milliseconds)
        assertEquals(emptyMap(), client.idleConnections())
        client.getText("$base/b")
        assertEquals(2, s.accepted)
        client.close()
    }

    @Test
    fun aConnectionTheServerClosedIsReplacedTransparently() = test {
        // The server ends kept-alive connections after 100 ms of quiet
        val s = server(config = Http1ServerConfig(keepAliveIdleTimeoutMillis = 100))
        val client = Client.builder().build(this)
        val base = "http://127.0.0.1:${s.port}"
        assertEquals("conn 0", client.getText("$base/a"))
        delay(400.milliseconds)
        assertEquals("conn 1", client.getText("$base/b"))
        assertEquals(2, s.accepted)
        client.close()
    }

    @Test
    fun http2SharesOneConnectionAmongConcurrentRequests() = test {
        val s = server(h2 = true, delayResponse = 50.milliseconds)
        val client = Client.builder().http2Only(true).build(this)
        val base = "http://127.0.0.1:${s.port}"
        val bodies = (0 until 10).map { async { client.getText("$base/s$it") } }.awaitAll()
        assertTrue(bodies.all { it == "conn 0" }, "$bodies")
        assertEquals(1, s.accepted)
        assertEquals(1L, client.connectionsOpened)
        client.close()
    }

    @Test
    fun eachAuthorityHasItsOwnConnections() = test {
        val a = server()
        val b = server()
        val client = Client.builder().build(this)
        client.getText("http://127.0.0.1:${a.port}/x")
        client.getText("http://127.0.0.1:${b.port}/x")
        client.getText("http://127.0.0.1:${a.port}/y")
        assertEquals(1, a.accepted)
        assertEquals(1, b.accepted)
        assertEquals(2, client.idleSoon().size)
        client.close()
    }

    @Test
    fun aRelativeUriIsRejected() = test {
        val client = Client.builder().build(this)
        assertFailsWith<IllegalArgumentException> { client.request(Request.get("/only/a/path").body(EmptyBody as Body)) }
        client.close()
    }

    @Test
    fun httpsNeedsAConnectorThatDoesTls() = test {
        val client = Client.builder().build(this)
        assertFailsWith<ConnectError> { client.request(Request.get("https://127.0.0.1:1/").body(EmptyBody as Body)) }
        client.close()
    }
}
