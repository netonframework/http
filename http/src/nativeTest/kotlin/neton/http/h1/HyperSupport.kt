package neton.http.h1

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import neton.http.Body
import neton.http.Frame
import neton.http.FullBody
import neton.http.HttpError
import neton.http.Incoming
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.Version
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.net.TcpListener
import neton.io.net.connect
import neton.io.net.listen
import neton.io.net.runReactor
import kotlin.random.Random

// Support code for the ports of hyper 1.11.1's integration tests (`tests/support/mod.rs`, `tests/support/trailers.rs`,
// `tests/h1_server/`, and the harnesses at the bottom of `tests/server.rs` / the top of `tests/client.rs`).
//
// hyper's tests run clients on OS threads with blocking sockets; here every "thread" is a coroutine on the test's
// reactor. Loopback TCP is used where hyper uses TCP, memory streams where it uses mocks / duplex pipes.

internal fun bytesOf(s: String): Bytes = Bytes.copyOf(s.encodeToByteArray())

/** `std::str::from_utf8(buf).unwrap()`. */
internal fun s(b: ByteArray): String = b.decodeToString()

/** hyper `has_header`: [name] appears in the head (before the first blank line). */
internal fun hasHeader(msg: String, name: String): Boolean {
    val n = msg.indexOf("\r\n\r\n").let { if (it < 0) msg.length else it }
    return msg.substring(0, n).contains(name)
}

/**
 * The scope a ported test runs in: [spawn] starts what hyper runs on a thread or a spawned task (its failure is kept in
 * the [Deferred], as a panicking hyper thread only fails the test when joined); everything spawned is cancelled and
 * every registered stream / listener closed when the test ends.
 */
internal class HyperScope(main: CoroutineScope, private val bg: CoroutineScope) : CoroutineScope by main {
    private val closers = ArrayList<() -> Unit>()

    fun <T> spawn(block: suspend CoroutineScope.() -> T): Deferred<T> = bg.async(block = block)

    fun <T : IoStream> T.closeAtEnd(): T = also { s -> closers.add { s.close() } }
    fun TcpListener.closeAtEnd(): TcpListener = also { l -> closers.add { l.close() } }

    internal fun closeAll() { for (c in closers.asReversed()) runCatching { c() } }
}

/** Runs a ported test on a fresh reactor with a deadline (hyper's blocking sockets use 1 s / 5 s timeouts). */
internal fun hyperTest(timeoutMillis: Long = 20_000, block: suspend HyperScope.() -> Unit) = runReactor {
    val bg = SupervisorJob(coroutineContext.job)
    val scope = HyperScope(this, CoroutineScope(coroutineContext + bg))
    try {
        withTimeout(timeoutMillis) { scope.block() }
    } finally {
        scope.closeAll()
        bg.cancel()
        bg.join()
    }
}

/**
 * `TcpListener::bind("127.0.0.1:0")`: neton-io's listener does not report its port, so a random free one is taken
 * (retrying on a port in use).
 */
internal suspend fun HyperScope.listenLocal(): Pair<TcpListener, Int> {
    var last: Throwable? = null
    repeat(100) {
        val port = Random.nextInt(20_000, 60_000)
        try {
            return listen("127.0.0.1", port).closeAtEnd() to port
        } catch (e: IllegalStateException) {
            last = e
        }
    }
    throw last!!
}

/** `TcpStream::connect(addr)`. */
internal suspend fun HyperScope.connectLocal(port: Int): IoStream = connect("127.0.0.1", port).closeAtEnd()

internal suspend fun IoStream.writeAll(data: ByteArray) { write(Buffer(data.size.coerceAtLeast(1)).also { it.writeBytes(data) }) }
internal suspend fun IoStream.writeAll(text: String) = writeAll(text.encodeToByteArray())

/** One `read` (hyper `req.read(&mut buf)`): whatever arrives next; empty at EOF. */
internal suspend fun IoStream.readOnce(): ByteArray {
    val b = Buffer()
    return if (read(b) < 0) ByteArray(0) else b.readAll()
}

/** `read_to_end`. */
internal suspend fun IoStream.readToEnd(): ByteArray {
    val b = Buffer()
    while (read(b) >= 0) { }
    return b.readAll()
}

/** `read_exact`: exactly [n] bytes (fails at an early EOF). */
internal suspend fun IoStream.readExact(n: Int, acc: Buffer = Buffer()): ByteArray {
    while (acc.readableBytes < n) check(read(acc) >= 0) { "EOF after ${acc.readableBytes} of $n bytes" }
    return acc.readBytes(n)
}

/** hyper `read_until`: reads until [done] holds for everything read so far (fails at EOF). */
internal suspend fun IoStream.readUntil(done: (ByteArray) -> Boolean): ByteArray {
    val b = Buffer()
    while (true) {
        check(read(b) >= 0) { "EOF before the condition held: ${s(b.peekAll())}" }
        if (done(b.peekAll())) return b.readAll()
    }
}

internal fun ByteArray.endsWith(suffix: String): Boolean = s(this).endsWith(suffix)

/** A body of the given chunks with an unknown length (`StreamBody` over `stream::iter` / `stream::once`). */
internal class StreamBody(chunks: List<ByteArray>, private var trailers: HeaderMap<HeaderValue>? = null) : Body {
    private val rest = ArrayDeque(chunks)
    override suspend fun nextFrame(): Frame? {
        rest.removeFirstOrNull()?.let { return Frame.Data(Bytes.copyOf(it)) }
        return trailers?.let { trailers = null; Frame.Trailers(it) }
    }
}

/** `support::trailers::StreamBodyWithTrailers::with_trailers`. */
internal fun streamBodyWithTrailers(chunks: List<ByteArray>, trailers: HeaderMap<HeaderValue>): Body = StreamBody(chunks, trailers)

/** A body fed through a channel (`StreamBody` over a `futures_channel::mpsc` receiver); an exception is a body error. */
internal class ChannelBody(private val ch: Channel<Result<Frame>>) : Body {
    override suspend fun nextFrame(): Frame? {
        val r = ch.receiveCatching()
        return if (r.isClosed) null else r.getOrThrow().getOrThrow()
    }
}

internal fun headerMapOf(vararg pairs: Pair<String, String>): HeaderMap<HeaderValue> =
    HeaderMap<HeaderValue>().also { m -> for ((k, v) in pairs) m.insert(k, HeaderValue.fromStr(v)) }

/** `BodyExt::collect`: the data and the trailers of a body. */
internal suspend fun Body.collect(): Pair<ByteArray, HeaderMap<HeaderValue>?> {
    val acc = Buffer()
    var trailers: HeaderMap<HeaderValue>? = null
    while (true) {
        when (val f = nextFrame() ?: break) {
            is Frame.Data -> acc.writeBytes(f.bytes)
            is Frame.Trailers -> trailers = f.headers
        }
    }
    return acc.readAll() to trailers
}

internal suspend fun Body.concat(): ByteArray = collect().first

internal fun HeaderValue.str(): String = toStr()

// ---- `tests/server.rs`: the `Serve` harness --------------------------------------------------------------------

/** hyper `ServeOptions` (the HTTP/1 ones). */
internal class ServeOptions(val keepAlive: Boolean = true, val pipeline: Boolean = false)

/** What the test service reports about a request body (hyper `Msg`). */
internal sealed class Msg {
    class Chunk(val bytes: ByteArray) : Msg()
    class Error(val error: HttpError) : Msg()
    object End : Msg()
}

/** hyper `ReplyBuilder`: queued at creation, consumed by the next request. */
internal class ReplyBuilder {
    var status: StatusCode = StatusCode.OK
    var reason: ReasonPhrase? = null
    var version: Version? = null
    val headers = ArrayList<Pair<HeaderName, HeaderValue>>()
    var body: Body? = null
    var error: Throwable? = null

    fun status(s: StatusCode) = apply { status = s }
    fun reasonPhrase(r: String) = apply { reason = ReasonPhrase(r.encodeToByteArray()) }
    fun version(v: Version) = apply { version = v }
    fun header(name: String, value: String) = apply { headers.add(HeaderName.fromStr(name) to HeaderValue.fromStr(value)) }
    fun body(b: String) { body = FullBody(bytesOf(b)) }
    fun body(b: ByteArray) { body = FullBody(Bytes.copyOf(b)) }
    fun bodyStream(chunks: List<ByteArray>) { body = StreamBody(chunks) }
    fun bodyStreamWithTrailers(chunks: List<ByteArray>, trailers: HeaderMap<HeaderValue>) { body = StreamBody(chunks, trailers) }
    fun error(e: Throwable) { error = e }

    /** hyper `TestService::build_reply`. */
    fun build(): Response<Body> {
        error?.let { throw it }
        val res = Response.builder().status(status).body(body ?: neton.http.EmptyBody as Body)
        version?.let { res.parts.version = it }
        reason?.let { res.extensions.insert(it) }
        for ((n, v) in headers) res.headers.insert(n, v)
        return res
    }
}

/** hyper `Serve`: a server accepting connections, each served with the test service. */
internal class Serve(val port: Int) {
    val msgs = Channel<Msg>(Channel.UNLIMITED)
    val trailersRx = Channel<HeaderMap<HeaderValue>>(Channel.UNLIMITED)
    private val replies = ArrayDeque<ReplyBuilder>()

    fun reply(): ReplyBuilder = ReplyBuilder().also { replies.addLast(it) }

    suspend fun tryBody(): Result<ByteArray> {
        val acc = Buffer()
        while (true) {
            when (val m = msgs.receive()) {
                is Msg.Chunk -> acc.writeBytes(m.bytes)
                is Msg.Error -> return Result.failure(m.error)
                Msg.End -> return Result.success(acc.readAll())
            }
        }
    }

    suspend fun body(): ByteArray = tryBody().getOrThrow()
    suspend fun bodyErr(): HttpError = tryBody().exceptionOrNull() as? HttpError ?: error("expected a body error")
    suspend fun trailers(): HeaderMap<HeaderValue> = trailersRx.receive()

    /** hyper `TestService::call`: read the whole request body, then answer with the next queued reply. */
    val service = HttpService { req ->
        while (true) {
            val f = try {
                req.body.nextFrame()
            } catch (e: HttpError) {
                msgs.send(Msg.Error(e))
                throw IllegalStateException("req body error", e)
            } ?: break
            when (f) {
                is Frame.Data -> msgs.send(Msg.Chunk(f.bytes.toByteArray()))
                is Frame.Trailers -> trailersRx.send(f.headers)
            }
        }
        msgs.send(Msg.End)
        (replies.removeFirstOrNull() ?: ReplyBuilder()).build()
    }
}

/**
 * hyper `serve_opts().serve()`: HTTP/1 with the default (⚖️ baseline) options plus [opts]; [config] replaces them all
 * (for the ⚖️ double runs).
 */
internal suspend fun HyperScope.serve(opts: ServeOptions = ServeOptions(), config: Http1ServerConfig? = null): Serve {
    val (listener, port) = listenLocal()
    val server = Serve(port)
    val cfg = config ?: Http1ServerConfig(keepAlive = opts.keepAlive, pipelineFlush = opts.pipeline)
    spawn {
        while (true) {
            val stream = listener.accept().closeAtEnd()
            spawn { runCatching { cfg.serveConnection(stream, server.service).serve() } }
        }
    }
    return server
}

/** hyper `HelloWorld`. */
internal const val HELLO = "hello"
internal val helloWorld = HttpService { Response.builder().body(FullBody(bytesOf(HELLO)) as Body) }

/** hyper `unreachable_service`. */
internal val unreachableService = HttpService { throw IllegalStateException("request shouldn't be received") }

/** Server options with the timeouts off, for memory streams (they have no read timeout). */
internal fun memoryServerConfig(
    keepAlive: Boolean = true,
    upgrades: Boolean = false,
    maxBufSize: Int = H1Io.DEFAULT_MAX_BUFFER_SIZE,
) = Http1ServerConfig(headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0, keepAlive = keepAlive, upgrades = upgrades, maxBufSize = maxBufSize)

/** A client connection over TCP whose connection task runs in the background (hyper `TestClient`). */
internal suspend fun HyperScope.clientRequest(port: Int, req: Request<out Body>, config: Http1ClientConfig = Http1ClientConfig()): Response<Incoming> {
    val (sender, conn) = config.handshake(connectLocal(port))
    spawn { conn.run() }
    return sender.sendRequest(req)
}

/** An [IoStream] wrapper that forwards everything (hyper's `DebugStream` / `CountingStream` wrappers build on it). */
internal open class ForwardingStream(val inner: IoStream) : IoStream {
    override val capabilities get() = inner.capabilities
    override suspend fun read(dst: Buffer): Int = inner.read(dst)
    override suspend fun write(src: Buffer): Int = inner.write(src)
    override suspend fun flush() = inner.flush()
    override fun close() = inner.close()
    override suspend fun shutdownOutput() = inner.shutdownOutput()
    override fun setTimeouts(readTimeoutMillis: Long, writeTimeoutMillis: Long, idleTimeoutMillis: Long) =
        inner.setTimeouts(readTimeoutMillis, writeTimeoutMillis, idleTimeoutMillis)
    override fun setReadTimeout(millis: Long) = inner.setReadTimeout(millis)
}

// ---- `tests/support/mod.rs`: the `t!` harness ------------------------------------------------------------------

/** hyper `__CReq`. */
internal class CReq(val method: String = "GET", val uri: String = "/", val headers: List<Pair<String, String>> = emptyList(), val body: String = "")

/** hyper `__CRes`: [headers] maps a name to an expected value, or [SOME] / [NONE]. */
internal class CRes(val status: Int = 200, val body: String = "", val headers: List<Pair<String, String?>> = emptyList())

/** hyper `__SReq`. */
internal class SReq(val method: String = "GET", val uri: String = "/", val headers: List<Pair<String, String?>> = emptyList(), val body: String = "")

/** hyper `__SRes`. */
internal class SRes(val status: Int = 200, val body: String = "", val headers: List<Pair<String, String>> = emptyList())

internal const val SOME = "\u0000SOME"
internal val NONE: String? = null

private fun checkHeaders(what: String, headers: HeaderMap<HeaderValue>, expected: List<Pair<String, String?>>) {
    for ((name, value) in expected) {
        val got = headers[name]
        when (value) {
            null -> kotlin.test.assertNull(got, "$what headers[$name]")
            SOME -> kotlin.test.assertNotNull(got, "$what headers[$name]")
            else -> kotlin.test.assertEquals(value, got?.str(), "$what headers[$name]")
        }
    }
}

/** hyper `__run_test` for HTTP/1 (`client_version: 1`), directly or through [naiveProxy] when [proxy]. */
internal suspend fun HyperScope.runT(client: List<Pair<CReq, CRes>>, server: List<Pair<SReq, SRes>>, proxy: Boolean) {
    val serveHandles = ArrayDeque(server)
    val (listener, serverPort) = listenLocal()
    val service = HttpService { req ->
        val (sreq, sres) = serveHandles.removeFirst()
        kotlin.test.assertEquals(sreq.uri, req.uri.path, "client path")
        kotlin.test.assertEquals(sreq.method, req.method.asStr(), "client method")
        kotlin.test.assertEquals(Version.HTTP_11, req.version, "client version")
        checkHeaders("client", req.headers, sreq.headers)
        kotlin.test.assertEquals(sreq.body, s(req.body.concat()), "client body")
        val res = Response.builder().status(sres.status).body(FullBody(bytesOf(sres.body)) as Body)
        for ((n, v) in sres.headers) res.headers.insert(n, HeaderValue.fromStr(v))
        res
    }
    val serverTasks = ArrayList<Deferred<Unit>>()
    spawn {
        while (true) {
            val stream = listener.accept().closeAtEnd()
            serverTasks.add(spawn { Http1ServerConfig().serveConnection(stream, service).serve() })
        }
    }
    val port = if (proxy) naiveProxy(serverPort) else serverPort
    for ((creq, cres) in client) {
        val req = Request.builder().method(creq.method).uri("http://127.0.0.1:$port${creq.uri}").body(FullBody(bytesOf(creq.body)) as Body)
        for ((n, v) in creq.headers) req.headers.insert(n, HeaderValue.fromStr(v))
        val (sender, conn) = http1Handshake(connectLocal(port))
        val connTask = spawn { conn.run() }
        val res = sender.sendRequest(req)
        kotlin.test.assertEquals(cres.status, res.status.asU16(), "server status")
        kotlin.test.assertEquals(Version.HTTP_11, res.version, "server version")
        checkHeaders("server", res.headers, cres.headers)
        kotlin.test.assertEquals(cres.body, s(res.body.concat()), "server body")
        // hyper: `if let Err(err) = conn.await { panic!(...) }` in the spawned connection task.
        if (connTask.isCompleted) connTask.await()
    }
    for (t in serverTasks) if (t.isCompleted) t.await()        // hyper: `.expect("server error")`
}

/** hyper `naive_proxy`: forwards each request over a new client connection to 127.0.0.1:[dstPort]. */
private suspend fun HyperScope.naiveProxy(dstPort: Int): Int {
    val (listener, proxyPort) = listenLocal()
    val service = HttpService { req ->
        val uri = "http://127.0.0.1:$dstPort${req.uri.path}"
        val out = Request.builder().method(req.method).uri(uri).version(req.version).body(req.body as Body)
        for (n in req.headers.keys()) for (v in req.headers.getAll(n)) out.headers.append(n, v)
        val (sender, conn) = http1Handshake(connect("127.0.0.1", dstPort).closeAtEnd())
        spawn { conn.run() }
        val resp = sender.sendRequest(out)
        // Remove the Connection header for HTTP/1.1 proxy connections.
        resp.headers.remove("Connection")
        val b = Response.builder().status(resp.status)
        for (n in resp.headers.keys()) for (v in resp.headers.getAll(n)) b.header(n, v)
        b.body(resp.body as Body)
    }
    spawn {
        while (true) {
            val stream = listener.accept().closeAtEnd()
            spawn { Http1ServerConfig().serveConnection(stream, service).serve() }
        }
    }
    return proxyPort
}
