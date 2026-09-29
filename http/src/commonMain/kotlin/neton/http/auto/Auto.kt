package neton.http.auto

import neton.http.HttpError
import neton.http.h1.Http1Connection
import neton.http.h1.Http1ServerConfig
import neton.http.h1.HttpService
import neton.http.h2.Http2Connection
import neton.http.h2.Http2ServerConfig
import neton.http.h2.proto.PREFACE
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoException
import neton.io.core.IoStream
import neton.io.core.StreamCapability
import neton.io.core.TimeoutException
import neton.io.core.monotonicNanos

// hyper-util 0.1.20 `server::conn::auto` (SPEC §7, §11): one server connection that is HTTP/1 or HTTP/2, told apart by
// the HTTP/2 connection preface (prior-knowledge h2c) or, ⚖️ over TLS, by the ALPN result.

/**
 * HTTP/1 or HTTP/2 server connection options (hyper-util `auto::Builder`): the [http1] and [http2] options of the two
 * protocols (hyper-util's `http1()` / `http2()` sub-builders), and whether only one of them is accepted.
 *
 * ```
 * val auto = AutoServerConfig().http1 { copy(keepAlive = true) }.http2 { keepAliveInterval(null) }
 * auto.serveConnection(stream, service).serve()
 * ```
 *
 * ⛔ hyper-util's executor and timers: each connection runs on its reactor (SPEC §1).
 */
class AutoServerConfig(
    http1: Http1ServerConfig = Http1ServerConfig(),
    /** The HTTP/2 options (hyper-util `http2()`), changed in place like [Http2ServerConfig] itself. */
    val http2: Http2ServerConfig = Http2ServerConfig(),
) {
    /**
     * The HTTP/1 options (hyper-util `http1()`). [Http1ServerConfig] is immutable: replace it, or change it with
     * [http1]`{ copy(...) }`. Its [Http1ServerConfig.upgrades] is not used: [serveConnection] serves without upgrades
     * and [serveConnectionWithUpgrades] with them (hyper-util `with_upgrades`).
     */
    var http1: Http1ServerConfig = http1

    private var only = DETECT

    /** Changes the HTTP/1 options (hyper-util `http1()`), e.g. `http1 { copy(halfClose = true) }`. */
    fun http1(configure: Http1ServerConfig.() -> Http1ServerConfig) = apply { http1 = http1.configure() }

    /** Changes the HTTP/2 options (hyper-util `http2()`), e.g. `http2 { maxHeaderListSize(4096) }`. */
    fun http2(configure: Http2ServerConfig.() -> Unit) = apply { http2.configure() }

    /**
     * Only accepts HTTP/2 (hyper-util `http2_only`): no detection. Does not do anything if used with
     * [serveConnectionWithUpgrades] (as hyper-util). At most one of [http1Only] / [http2Only].
     */
    fun http2Only() = apply {
        check(only == DETECT)
        only = H2
    }

    /**
     * Only accepts HTTP/1 (hyper-util `http1_only`): no detection. Does not do anything if used with
     * [serveConnectionWithUpgrades] (as hyper-util). At most one of [http1Only] / [http2Only].
     */
    fun http1Only() = apply {
        check(only == DETECT)
        only = H1
    }

    /** Whether this configuration can serve an HTTP/1.1-based connection (hyper-util `is_http1_available`). */
    fun isHttp1Available(): Boolean = only != H2

    /** Whether this configuration can serve an HTTP/2-based connection (hyper-util `is_http2_available`). */
    fun isHttp2Available(): Boolean = only != H1

    /** Title-case HTTP/1 header names on the wire (hyper-util `title_case_headers`); HTTP/2 is not affected. */
    fun titleCaseHeaders(enabled: Boolean) = apply { http1 = http1.copy(titleCaseHeaders = enabled) }

    /** Preserve the case of HTTP/1 header names (hyper-util `preserve_header_case`); HTTP/2 is not affected. */
    fun preserveHeaderCase(enabled: Boolean) = apply { http1 = http1.copy(preserveHeaderCase = enabled) }

    /**
     * Binds a connection to [service] (hyper-util `serve_connection`), without HTTP/1 upgrades: HTTP/1 or HTTP/2 as
     * [http1Only] / [http2Only] say; otherwise as [alpnProtocol] says; otherwise detected from the connection
     * preface ([AutoConnection]).
     *
     * ⚖️ [alpnProtocol]: the protocol a TLS layer negotiated (the `tls` library's `TlsStream.alpn`). `"h2"` serves
     * HTTP/2 and `"http/1.1"` (or `"http/1.0"`) HTTP/1 without reading anything first; null or another value falls back
     * to detection. hyper-util has no such input (it always detects, which works for TLS too since an h2 client still
     * sends the preface); with the ALPN result known the protocol is already decided, and a peer that picked
     * `http/1.1` gets HTTP/1 even if it then sends the preface (hyper's HTTP/1 answers that with its `VersionH2` error).
     */
    fun serveConnection(stream: IoStream, alpnProtocol: String?, service: HttpService): AutoConnection {
        val version = if (only != DETECT) only else alpnVersion(alpnProtocol)
        return AutoConnection(stream, service, http1, http2, version, upgrades = false)
    }

    /** [serveConnection] without an ALPN result: the protocol is detected unless [http1Only] / [http2Only]. */
    fun serveConnection(stream: IoStream, service: HttpService): AutoConnection = serveConnection(stream, null, service)

    /**
     * Binds a connection to [service] with HTTP/1 upgrades (hyper-util `serve_connection_with_upgrades`): as
     * [serveConnection], but [http1Only] / [http2Only] are not used (as hyper-util). An HTTP/1 connection hands over
     * after a `101` / CONNECT `2xx` response; its [neton.http.Upgraded.downcast] returns the original stream as usual
     * (hyper-util needs `auto::upgrade::downcast` to see through its `Rewind`; there is no wrapper here, see
     * [AutoConnection]).
     */
    fun serveConnectionWithUpgrades(stream: IoStream, alpnProtocol: String?, service: HttpService): AutoConnection =
        AutoConnection(stream, service, http1, http2, alpnVersion(alpnProtocol), upgrades = true)

    /** [serveConnectionWithUpgrades] without an ALPN result: the protocol is detected. */
    fun serveConnectionWithUpgrades(stream: IoStream, service: HttpService): AutoConnection =
        serveConnectionWithUpgrades(stream, null, service)

    private fun alpnVersion(alpn: String?): Int = when (alpn) {
        "h2" -> H2
        "http/1.1", "http/1.0" -> H1
        else -> DETECT
    }

    internal companion object {
        const val DETECT = 0
        const val H1 = 1
        const val H2 = 2
    }
}

/**
 * One HTTP/1 or HTTP/2 server connection (hyper-util `auto::Connection` / `UpgradeableConnection`). When the
 * protocol is not decided up front, [serve] first reads up to the 24 bytes of the HTTP/2 connection preface
 * (`PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`): all of them → HTTP/2; the first byte that differs, or an EOF, → HTTP/1. This
 * happens once per connection.
 *
 * ⚖️ What was read is not replayed through a wrapper stream (hyper-util `Rewind`): it is placed in the chosen
 * protocol's read buffer, as if that connection had read it. Same bytes, same order; but a wrapper stays on every
 * read, write, flush and timeout call of the connection's life — measured on the HTTP/1 hello benchmark at about
 * 150 instructions per request (SPEC §11). hyper-util cannot do this (hyper takes only an I/O object); here both
 * protocols are in the same library.
 *
 * ⚖️ hyper-util puts no time limit on the detection; here it is bounded by [Http1ServerConfig.headerReadTimeoutMillis]
 * (the HTTP/1 limit for the first request, SPEC §3.9) from the start of [serve] — [HttpError.Kind.HeaderTimeout] —
 * and then HTTP/1 applies its own timeouts as usual. A non-zero value needs a stream with
 * [StreamCapability.ReadTimeout], as for [Http1Connection].
 */
class AutoConnection internal constructor(
    private val stream: IoStream,
    private val service: HttpService,
    private val http1: Http1ServerConfig,
    private val http2: Http2ServerConfig,
    version: Int,
    private val upgrades: Boolean,
) {
    // hyper-util `ConnState`: detecting (both null), or the protocol's connection. A decided protocol is bound at once,
    // as hyper-util does, so a graceful shutdown before [serve] goes to it.
    private var h1: Http1Connection? =
        if (version == AutoServerConfig.H1) Http1Connection(stream, service, http1, upgrades) else null
    private var h2: Http2Connection? =
        if (version == AutoServerConfig.H2) http2.serveConnection(stream, service) else null
    private var cancelled = false
    private var detecting = false

    /**
     * Serves the connection until it ends (hyper-util `Connection` as a future): as [Http1Connection.serve] or
     * [Http2Connection.serve] once the protocol is known. Throws [HttpError]: that of the protocol, or while
     * detecting [HttpError.Kind.Io] for an I/O error or a graceful shutdown (hyper-util's `Interrupted` "Cancelled"
     * I/O error: the cause is an [IoException] with errno EINTR) and [HttpError.Kind.HeaderTimeout]. The stream is
     * closed on return unless upgraded.
     */
    suspend fun serve() {
        h1?.let { it.serve(); return }
        h2?.let { it.serve(); return }
        val (version, replay) = readVersion()
        if (version == AutoServerConfig.H1) {
            val c = try {
                Http1Connection(stream, service, http1, upgrades, replay)
            } catch (e: IllegalArgumentException) {
                stream.close()                              // options that do not fit the stream
                throw e
            }
            h1 = c
            c.serve()
        } else {
            val c = http2.serveConnection(stream, service, replay)
            h2 = c
            c.serve()
        }
    }

    /**
     * Starts a graceful shutdown (hyper-util `graceful_shutdown`): that of the protocol's connection
     * ([Http1Connection.gracefulShutdown], [Http2Connection.gracefulShutdown]); while the protocol is still being
     * detected, the detection is cancelled and [serve] ends with the `Cancelled` error. Call it on the connection's
     * reactor thread.
     */
    fun gracefulShutdown() {
        h1?.let { it.gracefulShutdown(); return }
        h2?.let { it.gracefulShutdown(); return }
        cancelled = true
        if (detecting) stream.close()                       // wakes the parked read
    }

    /** hyper-util `ReadVersion`: the protocol, and what was read. */
    private suspend fun readVersion(): Pair<Int, Bytes> {
        if (cancelled) throw cancelledError()
        val timeout = http1.headerReadTimeoutMillis
        if (timeout > 0) {
            require(StreamCapability.ReadTimeout in stream.capabilities) {
                "the header read timeout needs a stream with ReadTimeout; set it to 0 for this stream"
            }
        }
        val deadline = if (timeout > 0) nowMillis() + timeout else 0L
        val buf = Buffer(PREFACE.size)
        // We start as H2 and switch to H1 as soon as we don't have the preface.
        var version = AutoServerConfig.H2
        detecting = true
        try {
            while (buf.readableBytes < PREFACE.size) {
                val len = buf.readableBytes
                if (deadline > 0) stream.setReadTimeout((deadline - nowMillis()).coerceAtLeast(1))
                if (stream.read(buf) < 0 || !prefaceContinues(buf, len)) {
                    version = AutoServerConfig.H1
                    break
                }
            }
            if (deadline > 0) stream.setReadTimeout(0)
        } catch (e: TimeoutException) {
            stream.close()
            throw HttpError(HttpError.Kind.HeaderTimeout)
        } catch (e: IoException) {
            if (cancelled) throw cancelledError()
            stream.close()
            throw HttpError(HttpError.Kind.Io, e)
        } catch (e: Throwable) {
            stream.close()
            throw e
        } finally {
            detecting = false
        }
        if (cancelled) throw cancelledError()
        return version to buf.readSlice(buf.readableBytes)
    }

    /** Whether the bytes read after the first [from] still match the preface (only its 24 bytes are compared). */
    private fun prefaceContinues(buf: Buffer, from: Int): Boolean {
        val end = minOf(buf.readableBytes, PREFACE.size)
        for (i in from until end) if (buf.getByte(i) != PREFACE[i]) return false
        return true
    }

    private fun cancelledError(): HttpError {
        stream.close()
        return HttpError(HttpError.Kind.Io, IoException("Cancelled", EINTR))
    }

    private fun nowMillis() = monotonicNanos() / 1_000_000

    private companion object {
        /** errno EINTR (Rust `ErrorKind::Interrupted`); 4 on every target. */
        const val EINTR = 4
    }
}
