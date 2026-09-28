package neton.http.h2

import kotlinx.coroutines.Deferred
import neton.http.Body
import neton.http.EmptyBody
import neton.http.FullBody
import neton.http.HttpError
import neton.http.Incoming
import neton.http.Request
import neton.http.Response
import neton.http.Version
import neton.http.h1.CReq
import neton.http.h1.CRes
import neton.http.h1.HyperScope
import neton.http.h1.HttpService
import neton.http.h1.NONE
import neton.http.h1.SOME
import neton.http.h1.SReq
import neton.http.h1.SRes
import neton.http.h1.Serve
import neton.http.h1.bytesOf
import neton.http.h1.concat
import neton.http.h1.connectLocal
import neton.http.h1.listenLocal
import neton.http.h1.s
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.io.net.connect
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Support for the ports of hyper 1.11.1's HTTP/2 integration tests: the HTTP/2 parts of the `Serve` harness and
// `TestClient` (`tests/server.rs`) and of the `t!` harness (`tests/support/mod.rs`, `client_version: 2`). The HTTP/1
// support (`neton.http.h1.HyperSupport`) is reused: [HyperScope], [Serve], the request / response descriptions.

/** hyper `serve_opts().http2().serve()`: each connection served with HTTP/2 and the test service. */
internal suspend fun HyperScope.serveH2(config: Http2ServerConfig = Http2ServerConfig()): Serve {
    val (listener, port) = listenLocal()
    val server = Serve(port)
    spawn {
        while (true) {
            val stream = listener.accept().closeAtEnd()
            spawn { config.serveConnection(stream, server.service).serve() }
        }
    }
    return server
}

/**
 * hyper `TestClient::new().http2_only().request(req)`: a new connection, its task spawned (hyper expects it to end
 * without error), the request sent.
 */
internal suspend fun HyperScope.h2Request(port: Int, req: Request<out Body>): Response<Incoming> {
    val (sender, conn) = http2Handshake(connectLocal(port))
    spawn { conn.run() }
    return sender.sendRequest(req)
}

/** hyper `TestClient::new().http2_only().get(uri)`. */
internal suspend fun HyperScope.h2Get(port: Int): Response<Incoming> =
    h2Request(port, Request.get("http://127.0.0.1:$port/").body(EmptyBody as Body))

private fun checkHeaders(what: String, headers: HeaderMap<HeaderValue>, expected: List<Pair<String, String?>>) {
    for ((name, value) in expected) {
        val got = headers[name]
        when (value) {
            NONE -> assertNull(got, "$what headers[$name]")
            SOME -> assertNotNull(got, "$what headers[$name]")
            else -> assertEquals(value, got?.toStr(), "$what headers[$name]")
        }
    }
}

/**
 * hyper `__run_test` with `client_version: 2` / `server_version: 2`, directly or through the naive proxy when
 * [proxy]; the requests one after the other, or all at once when [parallel]. Each request has its own connection,
 * whose sender is closed once the response body is read (hyper drops it at the end of the request's future).
 */
internal suspend fun HyperScope.runT2(
    client: List<Pair<CReq, CRes>>,
    server: List<Pair<SReq, SRes>>,
    proxy: Boolean,
    parallel: Boolean = false,
) {
    val serveHandles = ArrayDeque(server)
    val (listener, serverPort) = listenLocal()
    val service = HttpService { req ->
        val (sreq, sres) = serveHandles.removeFirst()
        assertEquals(sreq.uri, req.uri.path, "client path")
        assertEquals(sreq.method, req.method.asStr(), "client method")
        assertEquals(Version.HTTP_2, req.version, "client version")
        checkHeaders("client", req.headers, sreq.headers)
        assertEquals(sreq.body, s(req.body.concat()), "client body")
        val res = Response.builder().status(sres.status).body(FullBody(bytesOf(sres.body)) as Body)
        for ((n, v) in sres.headers) res.headers.insert(n, HeaderValue.fromStr(v))
        res
    }
    val serverTasks = ArrayList<Deferred<Unit>>()
    spawn {
        while (true) {
            val stream = listener.accept().closeAtEnd()
            serverTasks.add(spawn { Http2ServerConfig().serveConnection(stream, service).serve() })
        }
    }
    val port = if (proxy) naiveProxyH2(serverPort) else serverPort
    val connTasks = ArrayList<Deferred<Unit>>()
    suspend fun makeRequest(creq: CReq, cres: CRes) {
        val req = Request.builder().method(creq.method).uri("http://127.0.0.1:$port${creq.uri}").body(FullBody(bytesOf(creq.body)) as Body)
        for ((n, v) in creq.headers) req.headers.insert(n, HeaderValue.fromStr(v))
        val (sender, conn) = http2Handshake(connectLocal(port))
        connTasks.add(spawn { conn.run() })
        val res = sender.sendRequest(req)
        assertEquals(cres.status, res.status.asU16(), "server status")
        assertEquals(Version.HTTP_2, res.version, "server version")
        checkHeaders("server", res.headers, cres.headers)
        assertEquals(cres.body, s(res.body.concat()), "server body")
        sender.close()
    }
    if (parallel) {
        val all = client.map { (creq, cres) -> spawn { makeRequest(creq, cres) } }
        for (r in all) r.await()
    } else {
        for ((creq, cres) in client) makeRequest(creq, cres)
    }
    // hyper: `if let Err(err) = conn.await { panic!(...) }` and the server's `.expect("server error")`.
    for (t in connTasks) if (t.isCompleted) t.await()
    for (t in serverTasks) if (t.isCompleted) t.await()
}

/** hyper `naive_proxy` with `version: 2`: forwards each request over a new HTTP/2 connection to 127.0.0.1:[dstPort]. */
private suspend fun HyperScope.naiveProxyH2(dstPort: Int): Int {
    val (listener, proxyPort) = listenLocal()
    val service = HttpService { req ->
        req.uri = neton.http.uri.Uri.parse("http://127.0.0.1:$dstPort${req.uri.path}")
        val (sender, conn) = http2Handshake(connect("127.0.0.1", dstPort).closeAtEnd())
        spawn { conn.run() }
        val resp = try {
            sender.sendRequest(req)
        } finally {
            sender.close()
        }
        val b = Response.builder().status(resp.status)
        for (n in resp.headers.keys()) for (v in resp.headers.getAll(n)) b.header(n, v)
        b.body(resp.body as Body)
    }
    spawn {
        // As the reference, one connection at a time.
        while (true) {
            val stream = listener.accept().closeAtEnd()
            Http2ServerConfig().serveConnection(stream, service).serve()
        }
    }
    return proxyPort
}

/** The `h2::Error` behind a hyper error (`err.source().downcast_ref::<h2::Error>()`). */
internal fun HttpError.h2Source(): H2Error = cause as? H2Error ?: error("no h2 error behind $this")
