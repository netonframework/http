@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http.h3.interop

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.http.Method
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.h3.Code
import neton.http.h3.ConnectionError
import neton.http.h3.LocalError
import neton.http.h3.StreamError
import neton.http.h3.quic.ALPN_H3
import neton.http.h3.quic.ConnectionErrorIncoming
import neton.http.h3.quic.asH3
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes
import neton.io.net.SocketAddress
import neton.io.net.bindUdp
import neton.io.net.runReactor
import neton.quic.Endpoint
import neton.quic.Incoming
import neton.quic.proto.Certificates
import neton.quic.proto.ClientConfig
import neton.quic.proto.EndpointConfig
import neton.quic.proto.PrivateKey
import neton.quic.proto.ServerConfig
import neton.quic.proto.TlsClientConfig
import neton.quic.proto.TlsHandshakeData
import neton.quic.proto.TlsServerConfig
import neton.quic.proto.VarInt
import neton.quic.proto.default
import neton.quic.proto.withCrypto
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.stdout
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import neton.http.h3.client.Connection as ClientConnection
import neton.http.h3.client.SendRequest
import neton.http.h3.server.Connection as ServerConnection
import neton.http.h3.server.RequestResolver

// HTTP/3 interop peer (SPEC §11, phase D): neton.http.h3 on neton.quic with real TLS 1.3, run against external
// HTTP/3 implementations. Not part of the http3 artifact.
//
//   h3interop server <ip:port> <cert.pem|der> <key.pem|der>
//     Serves until killed. Routes (the same in the Rust h3 peer, http3-interop/h3-peer):
//       GET /           200 "hello from <implementation>\n"
//       GET /size/<n>   200 with n bytes of the pattern byte(i) = (i * 31 + 7) % 251
//       POST /echo      200, the request body echoed as it arrives; if the request had trailers, the response has
//                       trailers x-echo-<name> for each of them and x-body-length
//       GET /goaway     sends GOAWAY (shutdown(0): requests already received complete, later ones are refused),
//                       then 200 "goaway"; once the last request is done the connection is closed with H3_NO_ERROR
//       otherwise       404
//   h3interop client <ip:port> <server name> <ca.pem|der> <peer|basic|example> [large bytes]
//     peer:    the scenarios below against a server with the routes above, on one connection, then a second
//              connection for the client's GOAWAY; exit status 0 only if every scenario passed.
//     basic:   the same without trailers and GOAWAY (servers that cannot express them, e.g. aioquic's ASGI server),
//              ending with the client's close.
//     example: against h3's examples/server.rs serving a directory with small.txt and large.bin (the pattern).
//   NETON_H3_GREASE=0 disables GREASE (stream, setting and the frame sent before the first finish).

/**
 * `NETON_H3_GREASE=0` turns GREASE off (`sendGrease(false)`, h3's `send_grease(false)`): no GREASE stream, setting or
 * frame. On by default, as in h3.
 */
private val grease: Boolean = platform.posix.getenv("NETON_H3_GREASE")?.toKString() != "0"

private fun log(msg: String) {
    println("[h3-interop] $msg")
    fflush(stdout)
}

private fun readFile(path: String): ByteArray {
    val f = fopen(path, "rb") ?: error("cannot open $path")
    try {
        val out = ArrayList<ByteArray>()
        memScoped {
            val buf = allocArray<ByteVar>(65536)
            while (true) {
                val n = fread(buf, 1u, 65536u, f).toInt()
                if (n <= 0) break
                out += buf.readBytes(n)
            }
        }
        val all = ByteArray(out.sumOf { it.size })
        var o = 0
        for (b in out) { b.copyInto(all, o); o += b.size }
        return all
    } finally {
        fclose(f)
    }
}

private fun isPem(b: ByteArray) = b.size > 10 && b.decodeToString(0, 10).startsWith("-----")

private fun certificates(path: String): Certificates = readFile(path).let { if (isPem(it)) Certificates.pem(it) else Certificates.der(it) }

private fun privateKey(path: String): PrivateKey = readFile(path).let { if (isPem(it)) PrivateKey.pem(it) else PrivateKey.der(it) }

private fun address(s: String): SocketAddress {
    val host = s.substringBeforeLast(':')
    val port = s.substringAfterLast(':').toInt()
    val parts = host.split('.').map { it.toInt() }
    require(parts.size == 4) { "IPv4 address expected: $s" }
    return SocketAddress.ipv4(parts[0], parts[1], parts[2], parts[3], port)
}

private fun patternByte(offset: Long): Byte = ((offset * 31 + 7) % 251).toByte()

private fun pattern(offset: Long, size: Int): Bytes = Bytes.wrap(ByteArray(size) { patternByte(offset + it) })

private fun checkPattern(chunk: Bytes, offset: Long): Long {
    for (i in 0 until chunk.size) {
        check(chunk[i] == patternByte(offset + i)) { "body byte ${offset + i} differs" }
    }
    return offset + chunk.size
}

private fun text(s: String) = Bytes.wrap(s.encodeToByteArray())

private fun describe(e: Throwable?): String = when (e) {
    null -> "none"
    is ConnectionError.Local -> "Local(${(e.error as? LocalError.Application)?.let { "${it.code} ${it.reason}" } ?: e.error})"
    is ConnectionError.Remote -> "Remote(${e.error.let { if (it is ConnectionErrorIncoming.ApplicationClose) Code(it.errorCode).toString() else it.toString() }})"
    else -> e.toString()
}

fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "server" -> server(args[1], args[2], args[3])
        "client" -> exitProcess(client(args[1], args[2], args[3], args[4], args.getOrNull(5)?.toLong() ?: (16L shl 20)))
        else -> {
            println("usage: h3interop server <ip:port> <cert> <key> | client <ip:port> <server name> <ca> <peer|basic|example> [large bytes]")
            exitProcess(2)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Server

private fun server(addr: String, cert: String, key: String) = runReactor {
    val tls = TlsServerConfig(certificates(cert), privateKey(key), alpnProtocols = listOf(ALPN_H3))
    val endpoint = Endpoint.create(EndpointConfig.default(), ServerConfig.withCrypto(tls), bindUdp(address(addr)))
    log("server listening on ${endpoint.localAddr()}")
    var n = 0
    while (true) {
        val incoming = endpoint.accept() ?: break
        val id = n++
        launch { serveConnection(id, incoming) }
    }
}

private suspend fun CoroutineScope.serveConnection(id: Int, incoming: Incoming) {
    val quic = try {
        incoming.await()
    } catch (e: Exception) {
        log("conn $id: handshake failed: $e")
        return
    }
    val hd = quic.handshakeData() as TlsHandshakeData
    log("conn $id: from ${quic.remoteAddress()}, ALPN ${hd.protocol?.decodeToString()}, SNI ${hd.serverName}")
    val conn = try {
        neton.http.h3.server.builder().sendGrease(grease).build(quic.asH3())
    } catch (e: ConnectionError) {
        log("conn $id: HTTP/3 setup failed: ${describe(e)}")
        return
    }
    val drive = async { conn.run() }
    var requests = 0
    while (true) {
        val resolver = try {
            conn.accept()
        } catch (e: ConnectionError) {
            log("conn $id: accept ended: ${describe(e)}")
            null
        } ?: break
        requests++
        launch { handle(id, conn, resolver) }
    }
    // accept() reported the end (GOAWAY either way and nothing left in progress): close with H3_NO_ERROR.
    conn.close()
    log("conn $id: done after $requests requests; driver: ${describe(drive.await())}; QUIC: ${quic.closeReason()}")
}

private suspend fun handle(connId: Int, conn: ServerConnection, resolver: RequestResolver) {
    val (req, stream) = try {
        resolver.resolveRequest()
    } catch (e: StreamError) {
        log("conn $connId: request failed before its head: $e")
        return
    }
    val path = req.uri.path
    // close() is the reference's Drop: it releases the QUIC stream (and its stream credit) whether or not the
    // request body was read to its end. Without it, a stream whose FIN was never read stays open.
    try {
        stream.use { handleResolved(connId, conn, req, it) }
    } catch (e: StreamError) {
        log("conn $connId: ${req.method} $path -> $e")
    }
}

private suspend fun handleResolved(connId: Int, conn: ServerConnection, req: Request<Unit>, stream: neton.http.h3.server.RequestStream) {
    val path = req.uri.path
    run {
        when {
            req.method == Method.GET && path == "/" -> {
                stream.sendResponse(Response.builder().status(200).header("content-type", "text/plain").body(Unit))
                stream.sendData(text("hello from neton h3\n"))
            }
            req.method == Method.GET && path.startsWith("/size/") -> {
                val size = path.removePrefix("/size/").toLong()
                stream.sendResponse(Response.builder().status(200).header("content-length", size.toString()).body(Unit))
                var sent = 0L
                while (sent < size) {
                    val n = minOf(32L * 1024, size - sent).toInt()
                    stream.sendData(pattern(sent, n))
                    sent += n
                }
            }
            req.method == Method.POST && path == "/echo" -> {
                stream.sendResponse(Response.builder().status(200).body(Unit))
                var length = 0L
                while (true) {
                    val chunk = stream.recvData() ?: break
                    length += chunk.size
                    stream.sendData(chunk)
                }
                val trailers = stream.recvTrailers()
                if (trailers != null) {
                    val echo = HeaderMap.new()
                    trailers.forEach { name, value -> echo.append(HeaderName.fromStr("x-echo-${name.asStr()}"), value) }
                    echo.append(HeaderName.fromStr("x-body-length"), HeaderValue.fromStr(length.toString()))
                    stream.sendTrailers(echo)
                }
            }
            req.method == Method.GET && path == "/goaway" -> {
                conn.shutdown(0)
                stream.sendResponse(Response.builder().status(200).body(Unit))
                stream.sendData(text("goaway"))
            }
            else -> stream.sendResponse(Response.builder().status(404).body(Unit))
        }
        stream.finish()
        log("conn $connId: ${req.method} $path -> done")
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Client

private class Session(val endpoint: Endpoint, val conn: ClientConnection, val send: SendRequest, val drive: kotlinx.coroutines.Deferred<ConnectionError>, val quic: neton.quic.Connection)

private suspend fun CoroutineScope.connect(endpoint: Endpoint, config: ClientConfig, addr: SocketAddress, name: String): Session {
    val quic = endpoint.connectWith(config, addr, name).await()
    val hd = quic.handshakeData() as TlsHandshakeData
    log("connected to $addr ($name): ALPN ${hd.protocol?.decodeToString()}")
    val (conn, send) = neton.http.h3.client.builder().sendGrease(grease).build(quic.asH3())
    val drive = async { conn.run() }
    return Session(endpoint, conn, send, drive, quic)
}

private class Results {
    var failed = 0
    var passed = 0

    suspend fun run(name: String, block: suspend (CoroutineScope) -> String) {
        val start = TimeSource.Monotonic.markNow()
        try {
            val detail = withTimeout(120.seconds) { coroutineScope { block(this) } }
            passed++
            log("PASS $name: $detail (${start.elapsedNow()})")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            failed++
            log("FAIL $name: $e")
        }
    }
}

private suspend fun SendRequest.get(authority: String, path: String): Triple<StatusCode, ByteArray, HeaderMap<HeaderValue>?> {
    val s = sendRequest(Request.get("https://$authority$path").body(Unit))
    s.finish()
    val resp = s.recvResponse()
    val body = ArrayList<ByteArray>()
    while (true) body += (s.recvData() ?: break).toByteArray()
    val all = ByteArray(body.sumOf { it.size })
    var o = 0
    for (b in body) { b.copyInto(all, o); o += b.size }
    return Triple(resp.status, all, s.recvTrailers())
}

private suspend fun SendRequest.getPattern(authority: String, path: String, size: Long): String {
    val s = sendRequest(Request.get("https://$authority$path").body(Unit))
    s.finish()
    val resp = s.recvResponse()
    check(resp.status == StatusCode.OK) { "status ${resp.status}" }
    var received = 0L
    while (true) received = checkPattern(s.recvData() ?: break, received)
    check(received == size) { "received $received of $size bytes" }
    return "$size bytes, pattern verified"
}

private fun client(addr: String, name: String, ca: String, mode: String, large: Long): Int {
    var status = 1
    runReactor { status = runClient(addr, name, ca, mode, large) }
    return status
}

private suspend fun CoroutineScope.runClient(addr: String, name: String, ca: String, mode: String, large: Long): Int {
    val tls = TlsClientConfig(trustAnchors = certificates(ca), alpnProtocols = listOf(ALPN_H3))
    val config = ClientConfig(tls)
    val server = address(addr)
    val authority = "$name:${server.port}"
    val endpoint = Endpoint.create(EndpointConfig.default(), null, bindUdp(SocketAddress.ipv4(0, 0, 0, 0, 0)))
    val r = Results()
    when (mode) {
        "peer" -> peerScenarios(r, endpoint, config, server, name, authority, large, full = true)
        "basic" -> peerScenarios(r, endpoint, config, server, name, authority, large, full = false)
        "example" -> exampleScenarios(r, endpoint, config, server, name, authority, large)
        else -> error("unknown mode $mode")
    }
    endpoint.waitIdle()
    endpoint.close()
    log("client done: ${r.passed} passed, ${r.failed} failed")
    return if (r.failed == 0) 0 else 1
}

private suspend fun CoroutineScope.peerScenarios(
    r: Results, endpoint: Endpoint, config: ClientConfig, server: SocketAddress, name: String, authority: String, large: Long,
    full: Boolean,
) {
    val s = connect(endpoint, config, server, name)
    r.run("get") {
        val (status, body) = s.send.get(authority, "/")
        check(status == StatusCode.OK) { "status $status" }
        check(body.isNotEmpty())
        "200 \"${body.decodeToString().trim()}\""
    }
    r.run("not-found") {
        val (status) = s.send.get(authority, "/missing")
        check(status == StatusCode.NOT_FOUND) { "status $status" }
        "404"
    }
    r.run("post") {
        val body = "hello over HTTP/3 ".repeat(64)
        val st = s.send.sendRequest(Request.post("https://$authority/echo").header("content-type", "text/plain").body(Unit))
        st.sendData(text(body))
        st.finish()
        check(st.recvResponse().status == StatusCode.OK)
        val got = StringBuilder()
        while (true) got.append((st.recvData() ?: break).decodeToString())
        check(got.toString() == body) { "echo differs" }
        "${body.length} bytes echoed"
    }
    r.run("large-upload-echo") {
        val st = s.send.sendRequest(Request.post("https://$authority/echo").body(Unit))
        val (sendHalf, recvHalf) = st.split()
        val writer = it.async {
            var sent = 0L
            while (sent < large) {
                val n = minOf(64L * 1024, large - sent).toInt()
                sendHalf.sendData(pattern(sent, n))
                sent += n
            }
            sendHalf.finish()
        }
        check(recvHalf.recvResponse().status == StatusCode.OK)
        var received = 0L
        while (true) received = checkPattern(recvHalf.recvData() ?: break, received)
        writer.await()
        check(received == large) { "echoed $received of $large" }
        "$large bytes up and echoed back, pattern verified"
    }
    r.run("large-download") { s.send.getPattern(authority, "/size/$large", large) }
    r.run("many-requests-one-connection") {
        val concurrent = (0 until 100).map { i -> it.async { s.send.getPattern(authority, "/size/${1000 + i}", 1000L + i) } }.awaitAll()
        repeat(50) { i -> s.send.getPattern(authority, "/size/$i", i.toLong()) }
        "${concurrent.size} concurrent + 50 sequential requests on one connection"
    }
    if (!full) {
        r.run("close") {
            s.send.close()
            val end = s.drive.await()
            check(end.code == Code.H3_NO_ERROR) { "connection ended with ${describe(end)}" }
            "connection ended ${describe(end)}; QUIC ${s.quic.closeReason()}"
        }
        return
    }
    r.run("trailers") {
        val st = s.send.sendRequest(Request.post("https://$authority/echo").body(Unit))
        st.sendData(text("body with trailers"))
        st.sendTrailers(HeaderMap.new().also { it.append(HeaderName.fromStr("x-checksum"), HeaderValue.fromStr("abc123")) })
        st.finish()
        check(st.recvResponse().status == StatusCode.OK)
        val got = StringBuilder()
        while (true) got.append((st.recvData() ?: break).decodeToString())
        check(got.toString() == "body with trailers")
        val trailers = checkNotNull(st.recvTrailers()) { "no response trailers" }
        check(trailers["x-echo-x-checksum"]?.toStr() == "abc123") { "trailers $trailers" }
        check(trailers["x-body-length"]?.toStr() == "18") { "trailers $trailers" }
        "request trailer echoed in response trailers (x-echo-x-checksum, x-body-length 18)"
    }
    r.run("server-goaway") {
        // A request in progress when the server sends GOAWAY completes; a new one afterwards is refused; the server
        // then closes the connection with H3_NO_ERROR.
        val open = s.send.sendRequest(Request.post("https://$authority/echo").body(Unit))
        open.sendData(text("part1 "))
        check(open.recvResponse().status == StatusCode.OK)
        val (status, body) = s.send.get(authority, "/goaway")
        check(status == StatusCode.OK && body.decodeToString() == "goaway") { "goaway: $status" }
        val deadline = TimeSource.Monotonic.markNow()
        while (!s.conn.isClosing) {
            check(deadline.elapsedNow() < 10.seconds) { "the GOAWAY never arrived" }
            delay(5)
        }
        val refused = try {
            s.send.sendRequest(Request.get("https://$authority/").body(Unit))
            "accepted"
        } catch (e: StreamError.RemoteClosing) {
            "RemoteClosing"
        }
        check(refused == "RemoteClosing") { "a request after GOAWAY was $refused" }
        open.sendData(text("part2"))
        open.finish()
        val got = StringBuilder()
        while (true) got.append((open.recvData() ?: break).decodeToString())
        check(got.toString() == "part1 part2") { "in-flight echo: $got" }
        // Nothing left in progress. RFC 9114 §5.2 lets the server close now or let the client do it: this library's
        // server closes with H3_NO_ERROR; h3 0.0.8's accept() only ends after the client's GOAWAY, so the client
        // closes (H3_NO_ERROR) if the server has not within 3 s.
        val serverClosed = kotlinx.coroutines.withTimeoutOrNull(3.seconds) { s.drive.await() }
        s.send.close()
        val end = serverClosed ?: s.drive.await()
        check(end.code == Code.H3_NO_ERROR) { "connection ended with ${describe(end)}" }
        val by = if (serverClosed != null) "the server closed the connection" else "the server left it open; the client closed it"
        "in-flight request completed, new request refused (RemoteClosing), $by: ${describe(end)}"
    }
    // A second connection: the client sends GOAWAY, finishes a request, then closes with H3_NO_ERROR.
    val s2 = connect(endpoint, config, server, name)
    r.run("client-goaway-and-close") {
        val (status) = s2.send.get(authority, "/")
        check(status == StatusCode.OK)
        s2.conn.shutdown()
        s2.send.close()
        val end = s2.drive.await()
        check(end.code == Code.H3_NO_ERROR) { "connection ended with ${describe(end)}" }
        "client GOAWAY sent; connection ended ${describe(end)}; QUIC ${s2.quic.closeReason()}"
    }
}

private suspend fun CoroutineScope.exampleScenarios(
    r: Results, endpoint: Endpoint, config: ClientConfig, server: SocketAddress, name: String, authority: String, large: Long,
) {
    val s = connect(endpoint, config, server, name)
    r.run("get") {
        val (status, body) = s.send.get(authority, "/small.txt")
        check(status == StatusCode.OK) { "status $status" }
        "200 \"${body.decodeToString().trim()}\""
    }
    r.run("not-found") {
        val (status) = s.send.get(authority, "/missing")
        check(status == StatusCode.NOT_FOUND) { "status $status" }
        "404"
    }
    r.run("large-download") { s.send.getPattern(authority, "/large.bin", large) }
    r.run("many-requests-one-connection") {
        val results = (0 until 100).map { _ -> it.async { s.send.get(authority, "/small.txt") } }.awaitAll()
        check(results.all { it.first == StatusCode.OK && it.second.isNotEmpty() })
        repeat(50) { check(s.send.get(authority, "/small.txt").first == StatusCode.OK) }
        "100 concurrent + 50 sequential requests on one connection"
    }
    r.run("post-body-ignored-by-example") {
        // examples/server.rs answers without reading the request body; the request must still complete.
        val st = s.send.sendRequest(Request.post("https://$authority/small.txt").body(Unit))
        st.sendData(text("ignored body"))
        st.finish()
        val status = st.recvResponse().status
        while (true) st.recvData() ?: break
        "status $status"
    }
    r.run("close") {
        s.send.close()
        val end = s.drive.await()
        check(end.code == Code.H3_NO_ERROR) { "connection ended with ${describe(end)}" }
        "connection ended ${describe(end)}; QUIC ${s.quic.closeReason()}"
    }
}
