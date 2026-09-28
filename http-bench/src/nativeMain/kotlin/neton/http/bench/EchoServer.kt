@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http.bench

import neton.http.Body
import neton.http.Frame
import neton.http.FullBody
import neton.http.Incoming
import neton.http.Response
import neton.http.h1.Http1ServerConfig
import neton.http.h1.HttpService
import neton.http.h2.Http2ServerConfig
import neton.io.bytes.Bytes
import neton.io.net.serveTcp
import kotlinx.cinterop.toKString

/**
 * The server of the `curl` interop check (SPEC §6): every request gets a one-line description of what arrived —
 * method, target, version, body length and FNV-1a checksum, trailer names — so a script can compare it with what curl
 * sent. `/chunked` answers with a body of unknown length (chunked on the wire), `/big` with 1 MiB of known length.
 *
 * Arguments: host port. Environment NETON_IO_DRIVER picks the driver; NETON_HTTP_H2=1 serves HTTP/2 over cleartext
 * with prior knowledge (h2c, for h2spec and `curl --http2-prior-knowledge`) instead of HTTP/1.
 */
fun echoMain(args: Array<String>) {
    val host = args.getOrElse(0) { "127.0.0.1" }
    val port = args.getOrElse(1) { "3001" }.toInt()
    val config = Http1ServerConfig(maxRequestBodySize = 256L * 1024 * 1024)
    val h2 = platform.posix.getenv("NETON_HTTP_H2")?.toKString() == "1"
    val big = Bytes.copyOf(ByteArray(1 shl 20) { ('a' + it % 26).code.toByte() })
    println("echoServer on $host:$port ${if (h2) "h2c" else "http/1"}")
    val service = HttpService { req ->
        val body = req.body as Incoming
        var n = 0L
        var h = 0xcbf29ce484222325uL.toLong()
        val trailers = ArrayList<String>()
        while (true) {
            when (val f = body.nextFrame() ?: break) {
                is Frame.Data -> {
                    val b = f.bytes
                    for (i in 0 until b.size) h = (h xor (b[i].toLong() and 0xff)) * 0x100000001b3L
                    n += b.size
                }
                is Frame.Trailers -> f.headers.forEach { name, _ -> trailers.add(name.toString()) }
            }
        }
        val line = "${req.method} ${req.uri} ${req.version} len=$n fnv=${h.toULong().toString(16)} trailers=${trailers.joinToString(",")}\n"
        when (req.uri.path) {
            "/big" -> Response<Body>(FullBody(big))
            "/chunked" -> Response<Body>(Pieces(listOf(line, "second piece\n", "third piece\n")))
            else -> Response<Body>(FullBody(Bytes.copyOf(line.encodeToByteArray())))
        }
    }
    serveTcp(host, port, shutdownOnSignals = true) { stream ->
        runCatching {
            if (h2) Http2ServerConfig().serveConnection(stream, service).serve()
            else config.serveConnection(stream, service).serve()
        }
    }
}

/** A body of unknown length, one frame per piece. */
private class Pieces(private val pieces: List<String>) : Body {
    private var i = 0
    override suspend fun nextFrame(): Frame? =
        if (i < pieces.size) Frame.Data(Bytes.copyOf(pieces[i++].encodeToByteArray())) else null
}
