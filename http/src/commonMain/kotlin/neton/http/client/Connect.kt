package neton.http.client

import kotlinx.coroutines.withTimeout
import neton.http.uri.Uri
import neton.io.core.IoStream
import neton.io.net.SocketOptions
import neton.io.net.connect as tcpConnect
import kotlin.time.Duration

/**
 * A connection a [Connector] made (hyper-util `client::legacy::connect::Connected`): the stream, and whether the
 * peer agreed to HTTP/2 (by TLS ALPN `h2`), which makes the client speak HTTP/2 on it.
 */
class Connected(val stream: IoStream, val negotiatedH2: Boolean = false)

/**
 * Opens connections for a [Client] (hyper-util's `Connect`, a `Service<Uri>`): given the request's URI (scheme and
 * authority), a connected stream. [HttpConnector] does plain TCP; HTTPS is a connector that adds TLS on top (this
 * library has no TLS) and reports the negotiated ALPN.
 */
fun interface Connector {
    suspend fun connect(uri: Uri): Connected
}

/**
 * Plain TCP to the URI's host and port (hyper-util `HttpConnector`): port 80 for `http`, 443 for `https`, unless the URI
 * names one. With [enforceHttp] (the default, as hyper-util) only `http` URIs are accepted: an `https` URI needs a
 * connector that does TLS.
 *
 * @param connectTimeout the longest a connection attempt may take (hyper-util `set_connect_timeout`); null: no limit.
 */
class HttpConnector(
    val enforceHttp: Boolean = true,
    val options: SocketOptions = SocketOptions.Default,
    val connectTimeout: Duration? = null,
) : Connector {
    override suspend fun connect(uri: Uri): Connected {
        val scheme = uri.schemeStr
        if (enforceHttp && scheme != "http") throw ConnectError("invalid URL, scheme is not http: $uri")
        val host = uri.host?.removeSurrounding("[", "]")?.ifEmpty { null } ?: throw ConnectError("invalid URL, missing host: $uri")
        val port = uri.portU16 ?: defaultPort(scheme) ?: throw ConnectError("invalid URL, unknown port: $uri")
        val stream = try {
            if (connectTimeout != null) withTimeout(connectTimeout) { tcpConnect(host, port, options) } else tcpConnect(host, port, options)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw ConnectError("connect to $host:$port timed out after $connectTimeout", e)
        }
        return Connected(stream)
    }
}

/** A connection could not be made (hyper-util `ConnectError`). */
class ConnectError(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun defaultPort(scheme: String?): Int? = when (scheme) {
    "http" -> 80
    "https" -> 443
    else -> null
}
