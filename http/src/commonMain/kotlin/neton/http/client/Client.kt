package neton.http.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import neton.http.Body
import neton.http.HttpError
import neton.http.Incoming
import neton.http.Method
import neton.http.Request
import neton.http.Response
import neton.http.Version
import neton.http.h1.Http1ClientConfig
import neton.http.h2.Http2ClientConfig
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.uri.Authority
import neton.http.uri.PathAndQuery
import neton.http.uri.Uri
import neton.io.core.IoStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import neton.http.h1.SendRequest as H1SendRequest
import neton.http.h2.SendRequest as H2SendRequest

/**
 * An HTTP client that keeps connections and reuses them (hyper-util 0.1.20 `client::legacy::Client` with its
 * `pool::Pool`): requests to the same scheme and authority share connections — an HTTP/1 connection carries one request
 * at a time and returns to the pool when its response has been read to the end; an HTTP/2 connection carries any number
 * at once. Idle connections are closed after [ClientBuilder.poolIdleTimeout] and at most
 * [ClientBuilder.poolMaxIdlePerHost] are kept per host. Connections come from a [Connector] ([HttpConnector]: TCP).
 *
 * Built with [Client.builder]; the connections run in coroutines of the scope given to [ClientBuilder.build], so the
 * client is used on that scope's reactor thread. [close] closes every connection.
 *
 * As with hyper, a response body must be read to its end (or closed) before its HTTP/1 connection can be reused.
 */
class Client internal constructor(
    parent: CoroutineScope,
    private val connector: Connector,
    private val config: ClientBuilder,
) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val pool = Pool(scope, config.poolIdleTimeout, config.poolMaxIdlePerHost)

    /** Connections opened so far (all keys), for tests and statistics. */
    var connectionsOpened = 0L
        private set

    /**
     * Sends [request] and returns the response once its head arrived (hyper-util `Client::request`). The URI must be
     * absolute (`http://host[:port]/path`). For HTTP/1 the client adds `Host` (unless [ClientBuilder.setHost] is off or the
     * request has one) and sends the URI in origin form (authority form for CONNECT).
     *
     * A request is retried on a new connection when the pooled connection it was given turned out to be closed before
     * anything was written (hyper-util `retry_canceled_requests`); a request that was written is never sent again.
     * @throws HttpError for the request's failures; [ConnectError] (or the connector's own) when no connection could be
     * made; [IllegalArgumentException] for a URI without scheme or authority.
     */
    suspend fun request(request: Request<out Body>): Response<Incoming> {
        check(job.isActive) { "the client is closed" }
        val uri = request.uri
        val scheme = requireNotNull(uri.schemeStr) { "the request URI must be absolute: $uri" }
        val authority = requireNotNull(uri.authority) { "the request URI must have an authority: $uri" }
        if (request.version == Version.HTTP_2 && !config.http2Only) {
            throw HttpError(HttpError.Kind.UserUnexpectedHeader, IllegalArgumentException("HTTP/2 requests need http2Only"))
        }
        val key = "$scheme://$authority"
        while (true) {
            val conn = checkout(key) ?: connect(key, uri)
            when (conn) {
                is PooledH1 -> {
                    // A new connection takes its first request before it runs; a pooled one must still be open
                    if (conn.reused && !conn.sender.isReady) {
                        // Closed while idle (the server ended a keep-alive connection): another one, nothing was sent
                        conn.close()
                        if (!config.retryCanceledRequests) {
                            throw HttpError(HttpError.Kind.Canceled, IllegalStateException("pooled connection was closed"))
                        }
                        continue
                    }
                    prepareHttp1(request, uri, authority)
                    pool.lend(key)
                    val response = try {
                        conn.sender.sendRequest(request)
                    } catch (e: Throwable) {
                        pool.giveBack(key)
                        throw e
                    }
                    // Back to the pool once the response has been read (hyper-util spawns `poll_ready`)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            conn.sender.ready()
                            pool.putIdle(key, conn)
                        } catch (e: Exception) {
                            conn.close()
                        } finally {
                            pool.giveBack(key)
                        }
                    }
                    return response
                }
                is PooledH2 -> {
                    if (conn.sender.isClosed) {
                        pool.removeShared(key, conn)
                        conn.close()
                        // A shared connection that has since closed: another one; a new one closed at once: an error
                        if (conn.reused) continue
                        throw HttpError(HttpError.Kind.ChannelClosed)
                    }
                    val handle = conn.sender.clone()
                    try {
                        conn.lastUse = TimeSource.Monotonic.markNow()
                        return handle.sendRequest(request)
                    } finally {
                        // The stream keeps the connection open until it is done
                        handle.close()
                    }
                }
            }
        }
    }

    /**
     * An idle connection for [key]. When there is none but some are out with responses, give them a few turns of the
     * reactor first: one whose response body was just read returns to the pool within a couple of dispatches (its
     * connection marks itself idle, then the watcher puts it back), and a request right after reading a body should
     * reuse it rather than open another (hyper-util races a new connect against the pool for the same reason).
     */
    private suspend fun checkout(key: String): Pooled? {
        pool.checkout(key)?.let { return it }
        var turns = 0
        while (pool.lent(key) > 0 && turns++ < RETURN_TURNS) {
            yield()
            pool.checkout(key)?.let { return it }
        }
        return null
    }

    /** `GET` [uri] (hyper-util `Client::get`). */
    suspend fun get(uri: String): Response<Incoming> = request(Request.get(uri).body(neton.http.EmptyBody))

    /** Idle connections in the pool, per `scheme://authority`. */
    fun idleConnections(): Map<String, Int> = pool.idleCounts()

    /** Closes every connection (idle or in use) and the pool; requests after this fail. Idempotent. */
    override fun close() {
        pool.clear()
        job.cancel()
    }

    private suspend fun connect(key: String, uri: Uri): Pooled {
        // HTTP/2 connections are shared: one connect per key at a time when HTTP/2 is certain (hyper-util's
        // `connecting` lock for `Ver::Http2`)
        if (config.http2Only) pool.awaitConnecting(key)?.let { return it }
        val connecting = if (config.http2Only) pool.beginConnecting(key) else null
        try {
            val connected = connector.connect(uri)
            connectionsOpened++
            val pooled = if (config.http2Only || connected.negotiatedH2) handshakeH2(key, connected.stream) else handshakeH1(connected.stream)
            connecting?.complete(pooled)
            return pooled
        } catch (e: Throwable) {
            connecting?.completeExceptionally(e)
            throw e
        } finally {
            if (connecting != null) pool.endConnecting(key)
        }
    }

    private fun handshakeH1(stream: IoStream): PooledH1 {
        val (sender, connection) = config.http1.handshake(stream)
        val run = scope.launch { try { connection.run() } catch (e: Exception) { } }
        return PooledH1(sender, stream, run)
    }

    private suspend fun handshakeH2(key: String, stream: IoStream): PooledH2 {
        val (sender, connection) = try {
            config.http2.handshake(stream)
        } catch (e: Throwable) {
            stream.close()
            throw e
        }
        val run = scope.launch { try { connection.run() } catch (e: Exception) { } }
        val pooled = PooledH2(sender, stream, run)
        pool.putShared(key, pooled)
        return pooled
    }

    /** hyper-util `send_request` for HTTP/1: the Host header and the request target's form. */
    private fun prepareHttp1(request: Request<out Body>, uri: Uri, authority: Authority) {
        if (config.setHost && request.headers.get(HOST) == null) {
            val host = uri.host ?: authority.toString()
            val port = uri.portU16
            val value = if (port != null && port != defaultPort(uri.schemeStr)) "$host:$port" else host
            request.headers.insert(HOST, HeaderValue.fromStr(value))
        }
        request.uri = if (request.method == Method.CONNECT) {
            Uri.from(authority)
        } else {
            val pq = uri.pathAndQuery
            Uri.from(if (pq == null || pq.toString().isEmpty()) PathAndQuery.fromStatic("/") else pq)
        }
    }

    companion object {
        /** A builder with hyper-util's defaults. */
        fun builder(): ClientBuilder = ClientBuilder()

        private val HOST = HeaderName.fromStr("host")
        private const val RETURN_TURNS = 4
    }
}

/** [Client] options (hyper-util `client::legacy::Builder`). */
class ClientBuilder internal constructor() {
    internal var poolIdleTimeout: Duration? = 90.seconds
    internal var poolMaxIdlePerHost: Int = Int.MAX_VALUE
    internal var retryCanceledRequests = true
    internal var setHost = true
    internal var http2Only = false
    internal var http1 = Http1ClientConfig()
    internal var http2 = Http2ClientConfig()

    /** How long an idle connection is kept (default 90 s); null keeps it until the server closes it. */
    fun poolIdleTimeout(timeout: Duration?) = apply {
        require(timeout == null || timeout.isPositive()) { "idle timeout must be positive" }
        poolIdleTimeout = timeout
    }

    /** The most idle connections kept per host (default unlimited); 0 keeps none. */
    fun poolMaxIdlePerHost(max: Int) = apply {
        require(max >= 0) { "max idle per host must not be negative" }
        poolMaxIdlePerHost = max
    }

    /** Retry a request whose pooled connection was found closed before anything was written (default on). */
    fun retryCanceledRequests(enabled: Boolean) = apply { retryCanceledRequests = enabled }

    /** Add a `Host` header to HTTP/1 requests that have none (default on). */
    fun setHost(enabled: Boolean) = apply { setHost = enabled }

    /** Speak HTTP/2 on every connection (prior knowledge, h2c), not only those whose connector negotiated it. */
    fun http2Only(enabled: Boolean) = apply { http2Only = enabled }

    /** The options of the HTTP/1 connections. */
    fun http1(config: Http1ClientConfig) = apply { http1 = config }

    /** The options of the HTTP/2 connections. */
    fun http2(config: Http2ClientConfig) = apply { http2 = config }

    /** The client; its connections run in [scope] (hyper-util takes an executor). */
    fun build(scope: CoroutineScope, connector: Connector = HttpConnector()): Client = Client(scope, connector, this)
}

/** A connection in the pool or in use. */
internal sealed class Pooled(val stream: IoStream, val run: Job) {
    var idleSince = TimeSource.Monotonic.markNow()

    /** Whether it carried a request before (hyper-util `is_reused`). */
    var reused = false

    open fun close() {
        run.cancel()
        try { stream.close() } catch (e: Exception) { }
    }
}

internal class PooledH1(val sender: H1SendRequest, stream: IoStream, run: Job) : Pooled(stream, run)

internal class PooledH2(val sender: H2SendRequest, stream: IoStream, run: Job) : Pooled(stream, run) {
    var lastUse = TimeSource.Monotonic.markNow()

    /** Releases the pool's handle: the connection closes once its streams are done (hyper drops the `SendRequest`). */
    override fun close() = sender.close()
}

/**
 * The connections per `scheme://authority` (hyper-util `pool::Pool`): idle HTTP/1 connections, most recent first out,
 * and one shared HTTP/2 connection. Idle ones expire after [idleTimeout], checked on use and by a reaper that runs
 * while anything is idle.
 */
internal class Pool(private val scope: CoroutineScope, private val idleTimeout: Duration?, private val maxIdlePerHost: Int) {
    private val idle = HashMap<String, ArrayDeque<PooledH1>>()
    private val shared = HashMap<String, PooledH2>()
    private val connecting = HashMap<String, CompletableDeferred<Pooled>>()
    private val lent = HashMap<String, Int>()
    private var reaper: Job? = null

    fun checkout(key: String): Pooled? {
        shared[key]?.let { h2 ->
            if (!h2.sender.isClosed && !expired(h2.lastUse)) return h2.also { it.reused = true }
            shared.remove(key)
            h2.close()
        }
        val q = idle[key] ?: return null
        while (q.isNotEmpty()) {
            val c = q.removeLast()
            if (expired(c.idleSince)) {
                c.close()
                continue
            }
            if (q.isEmpty()) idle.remove(key)
            c.reused = true
            return c
        }
        idle.remove(key)
        return null
    }

    fun putIdle(key: String, c: PooledH1) {
        if (!c.sender.isReady) {
            c.close()
            return
        }
        val q = idle.getOrPut(key) { ArrayDeque() }
        if (q.size >= maxIdlePerHost) {
            if (q.isEmpty()) idle.remove(key)
            c.close()
            return
        }
        c.idleSince = TimeSource.Monotonic.markNow()
        q.addLast(c)
        startReaper()
    }

    fun putShared(key: String, c: PooledH2) {
        val old = shared[key]
        if (old != null && !old.sender.isClosed) {
            // Two connects raced (ALPN): keep the first, the new one closes after its request
            return
        }
        old?.close()
        shared[key] = c
        startReaper()
    }

    fun removeShared(key: String, c: PooledH2) {
        if (shared[key] === c) shared.remove(key)
    }

    suspend fun awaitConnecting(key: String): Pooled? = connecting[key]?.let { runCatching { it.await() }.getOrNull() }

    fun beginConnecting(key: String): CompletableDeferred<Pooled> = CompletableDeferred<Pooled>().also { connecting[key] = it }

    fun endConnecting(key: String) {
        connecting.remove(key)
    }

    /** HTTP/1 connections out with a response not yet read, per key. */
    fun lent(key: String): Int = lent[key] ?: 0

    fun lend(key: String) {
        lent[key] = lent(key) + 1
    }

    fun giveBack(key: String) {
        val n = lent(key) - 1
        if (n <= 0) lent.remove(key) else lent[key] = n
    }

    fun idleCounts(): Map<String, Int> = idle.mapValues { it.value.size }.filterValues { it > 0 }

    fun clear() {
        reaper?.cancel()
        for (q in idle.values) q.forEach { it.close() }
        idle.clear()
        shared.values.forEach { it.close() }
        shared.clear()
    }

    private fun expired(since: TimeSource.Monotonic.ValueTimeMark): Boolean =
        idleTimeout != null && since.elapsedNow() >= idleTimeout

    /** hyper-util `IdleTask`: closes expired connections while the pool holds any. */
    private fun startReaper() {
        val timeout = idleTimeout ?: return
        if (reaper?.isActive == true) return
        reaper = scope.launch {
            while (idle.isNotEmpty() || shared.isNotEmpty()) {
                delay(maxOf(timeout / 4, MIN_REAP_INTERVAL))
                val it = idle.entries.iterator()
                while (it.hasNext()) {
                    val (_, q) = it.next()
                    q.removeAll { c -> expired(c.idleSince).also { if (it) c.close() } }
                    if (q.isEmpty()) it.remove()
                }
                val s = shared.entries.iterator()
                while (s.hasNext()) {
                    val (_, c) = s.next()
                    if (c.sender.isClosed || expired(c.lastUse)) {
                        c.close()
                        s.remove()
                    }
                }
            }
        }
    }

    private companion object {
        val MIN_REAP_INTERVAL = kotlin.time.Duration.parse("100ms")
    }
}
