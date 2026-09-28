package neton.http.h1

import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import neton.http.Body
import neton.http.Frame
import neton.http.FullBody
import neton.http.Incoming
import neton.http.Response
import neton.io.bytes.Buffer
import neton.io.core.IoStream
import neton.io.core.memoryStreamPair
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The end-to-end acceptance items of SPEC §6 that are not ports of a reference suite: arrival split at every byte
 * boundary, the security-baseline vectors of §3.9 driven through a served connection (each followed by a smuggled
 * request that must never be served), and the request-body limit on declared and chunked bodies. The 100 MB streamed
 * upload with its memory bound is in linuxTest (it reads the resident set from /proc).
 */
class AcceptanceTest {
    private fun config(lenientTeWithCl: Boolean = false, maxRequestBodySize: Long = 10L * 1024 * 1024) = Http1ServerConfig(
        headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0, autoDateHeader = false,
        lenientTeWithCl = lenientTeWithCl, maxRequestBodySize = maxRequestBodySize,
    )

    private val noTimeouts = config()

    private suspend fun IoStream.send(b: ByteArray) { if (b.isNotEmpty()) write(Buffer(b.size).also { it.writeBytes(b) }) }

    private suspend fun IoStream.readToEof(): String {
        val acc = Buffer()
        while (read(acc) >= 0) {}
        return acc.readAll().decodeToString()
    }

    private suspend fun Incoming.describe(): String {
        val sb = StringBuilder()
        while (true) {
            when (val f = nextFrame() ?: break) {
                is Frame.Data -> sb.append(f.bytes.toByteArray().decodeToString())
                is Frame.Trailers -> f.headers.forEach { n, v -> sb.append(" [").append(n).append('=').append(v.toStr()).append(']') }
            }
        }
        return sb.toString()
    }

    /** Echoes method, target and body (with trailers) of every request; records the targets served. */
    private fun echo(served: MutableList<String>, cfg: Http1ServerConfig = noTimeouts, server: IoStream) =
        cfg.serveConnection(server) { req ->
            served.add(req.uri.toString())
            val body = runCatching { (req.body as Incoming).describe() }.getOrElse { "<${it.message}>" }
            Response.builder().body(FullBody(bytesOf("${req.method} ${req.uri} $body")) as Body)
        }

    // A keep-alive sequence touching every framing path: bodiless GET, length-delimited POST, chunked POST with a
    // chunk extension and a trailer, then a GET that closes.
    private val script = (
        "GET /a HTTP/1.1\r\nHost: x\r\nX-Long: " + "v".repeat(40) + "\r\n\r\n" +
            "POST /b HTTP/1.1\r\nHost: x\r\nContent-Length: 11\r\n\r\nhello world" +
            "POST /c HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "5;ext=1\r\nabcde\r\n3\r\nfgh\r\n0\r\nx-t: 1\r\n\r\n" +
            "GET /d HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
        ).encodeToByteArray()

    /** Serves [script] delivered in the given pieces (a yield between pieces, so each arrives as its own read). */
    private fun serveInPieces(cuts: List<Int>): Pair<String, List<String>> {
        var out = ""
        val served = ArrayList<String>()
        runReactor {
            val (server, client) = memoryStreamPair()
            val done = async { echo(served, server = server).serve() }
            var from = 0
            for (to in cuts + script.size) {
                client.send(script.copyOfRange(from, to))
                from = to
                yield()
            }
            out = client.readToEof()
            withTimeout(5_000) { done.await() }
        }
        return out to served
    }

    @Test
    fun everySplitPointGivesTheSameExchange() {
        val (whole, served) = serveInPieces(emptyList())
        assertEquals(listOf("/a", "/b", "/c", "/d"), served)
        assertTrue(whole.contains("POST /b hello world") && whole.contains("POST /c abcdefgh [x-t=1]"), whole)
        for (i in 1 until script.size) {
            val (out, s) = serveInPieces(listOf(i))
            assertEquals(whole, out, "split at $i")
            assertEquals(served, s, "split at $i")
        }
    }

    @Test
    fun byteByByteArrivalGivesTheSameExchange() {
        val (whole, _) = serveInPieces(emptyList())
        assertEquals(whole, serveInPieces((1 until script.size).toList()).first)
    }

    /**
     * One §3.9 vector followed by a smuggled request on the same connection: returns what the client read (to EOF)
     * and the targets the service saw. The smuggled request must never reach the service or the wire.
     */
    private fun vector(head: String, cfg: Http1ServerConfig = noTimeouts): Pair<String, List<String>> {
        var out = ""
        val served = ArrayList<String>()
        runReactor {
            val (server, client) = memoryStreamPair()
            val done = async { runCatching { echo(served, cfg, server).serve() } }
            client.send((head + "GET /smuggled HTTP/1.1\r\nHost: x\r\n\r\n").encodeToByteArray())
            out = client.readToEof()
            withTimeout(5_000) { done.await() }
        }
        assertFalse(served.contains("/smuggled"), "smuggled request served: $out")
        assertFalse(out.contains("/smuggled"), "smuggled request answered: $out")
        return out to served
    }

    private fun assertRejected(status: String, head: String, cfg: Http1ServerConfig = noTimeouts) {
        val (out, served) = vector(head, cfg)
        assertTrue(out.startsWith("HTTP/1.1 $status"), "expected $status for ${head.take(60)}: ${out.take(80)}")
        assertEquals(emptyList(), served, "service called for a rejected head")
    }

    @Test
    fun teWithClIsRejectedAndNothingIsSmuggled() =
        assertRejected("400", "POST / HTTP/1.1\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")

    @Test
    fun teWithClLenientFollowsHyperWithoutKeepAlive() {
        // hyper: CL dropped, the chunked body read, keep-alive off; whatever follows is never read as a request.
        val (out, served) = vector(
            "POST /p HTTP/1.1\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n",
            config(lenientTeWithCl = true),
        )
        assertEquals(listOf("/p"), served)
        assertTrue(out.startsWith("HTTP/1.1 200 OK\r\n") && out.endsWith("POST /p abc"), out)
    }

    @Test
    fun conflictingContentLengthsAreRejected() =
        assertRejected("400", "POST / HTTP/1.1\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\nabcd")

    @Test
    fun contentLengthListIsRejected() = assertRejected("400", "POST / HTTP/1.1\r\nContent-Length: 3, 3\r\n\r\nabc")

    @Test
    fun equalContentLengthsAreMerged() {
        val (out, served) = vector("POST /m HTTP/1.1\r\nContent-Length: 3\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc")
        assertEquals(listOf("/m"), served)
        assertTrue(out.endsWith("POST /m abc"), out)
    }

    @Test
    fun contentLengthOverflowIsRejected() =
        assertRejected("400", "POST / HTTP/1.1\r\nContent-Length: 99999999999999999999\r\n\r\n")

    @Test
    fun bareLfIsRejected() = assertRejected("400", "GET / HTTP/1.1\nHost: x\n\n")

    @Test
    fun bareCrIsRejected() = assertRejected("400", "GET / HTTP/1.1\r\nHost: x\rX: y\r\n\r\n")

    @Test
    fun obsFoldInRequestIsRejected() = assertRejected("400", "GET / HTTP/1.1\r\nX: a\r\n b\r\n\r\n")

    @Test
    fun whitespaceBeforeColonIsRejected() = assertRejected("400", "GET / HTTP/1.1\r\nContent-Length : 5\r\n\r\nGET /")

    @Test
    fun overlongRequestLineIsRejected() = assertRejected("414", "GET /" + "a".repeat(9 * 1024) + " HTTP/1.1\r\n\r\n")

    @Test
    fun overlongHeaderSectionIsRejected() =
        assertRejected("431", "GET / HTTP/1.1\r\n" + (0 until 70).joinToString("") { "X-$it: " + "v".repeat(1000) + "\r\n" } + "\r\n")

    @Test
    fun tooManyHeadersAreRejected() =
        assertRejected("431", "GET / HTTP/1.1\r\n" + (0 until 101).joinToString("") { "X-$it: v\r\n" } + "\r\n")

    /** A chunked body that goes bad after the head was accepted: the service sees the error, the connection closes. */
    private fun assertBodyFails(chunks: String) {
        val (out, served) = vector("POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n$chunks")
        assertEquals(listOf("/x"), served)
        assertTrue(out.isEmpty() || out.startsWith("HTTP/1.1 "), out)
    }

    @Test
    fun chunkSizeOverSixteenDigitsFails() = assertBodyFails("0" + "1".repeat(16) + "\r\nabc\r\n0\r\n\r\n")

    @Test
    fun chunkSizeLineOverOneKibFails() = assertBodyFails("3;" + "e".repeat(1100) + "\r\nabc\r\n0\r\n\r\n")

    @Test
    fun bareLfInChunkedBodyFails() = assertBodyFails("3\nabc\r\n0\r\n\r\n")

    @Test
    fun trailersOverEightKibFail() = assertBodyFails("3\r\nabc\r\n0\r\nx-big: " + "t".repeat(9 * 1024) + "\r\n\r\n")

    @Test
    fun declaredBodyOverDefaultLimitIs413AndCloses() {
        val (out, served) = vector("POST / HTTP/1.1\r\nContent-Length: ${10L * 1024 * 1024 + 1}\r\n\r\n")
        assertTrue(out.startsWith("HTTP/1.1 413 Payload Too Large\r\n"), out)
        assertEquals(emptyList(), served)
    }

    @Test
    fun chunkedBodyOverTheLimitIs413WhenTheServicePropagates() = runReactor {
        val (server, client) = memoryStreamPair()
        val cfg = config(maxRequestBodySize = 1024)
        val done = async {
            runCatching {
                cfg.serveConnection(server) { req ->
                    while ((req.body as Incoming).nextFrame() != null) {}
                    Response.builder().body(FullBody(bytesOf("never")) as Body)
                }.serve()
            }
        }
        val chunk = "400\r\n" + "a".repeat(1024) + "\r\n"
        client.send(("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" + chunk + chunk + "0\r\n\r\n").encodeToByteArray())
        val out = client.readToEof()
        assertTrue(out.startsWith("HTTP/1.1 413 Payload Too Large\r\n"), out)
        assertFalse(out.contains("never"))
        withTimeout(5_000) { done.await() }
    }
}
