package neton.http.h1.parse

import neton.http.HttpException
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.uri.Uri
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

// Ports of the fuzz targets of httparse 1.10.1 (`fuzz/fuzz_targets`: parse_request, parse_response,
// parse_request_multspaces, parse_response_multspaces, parse_headers, parse_chunk_size) and http 1.5.0
// (`fuzz/src/fuzz_http.rs`). The references run libFuzzer and only require that nothing panics; here inputs come from
// fixed seeds (reproducible), half random over an HTTP-heavy alphabet and half mutations of valid heads, and each
// result is also checked against properties the parser promises:
// - the status is a byte count within the input, PARTIAL, or a known error;
// - a complete parse only records offsets inside the bytes it consumed;
// - the same input at an offset inside a larger array gives the same result, shifted;
// - every proper prefix of a complete head is PARTIAL (parsing is incremental: more bytes cannot turn an error into a
//   head, so a prefix that is an error would make the complete head unreachable for a streaming reader).

private const val CASES = 20_000

private val TOKENS = listOf(
    "GET", "POST", "HTTP/1.1", "HTTP/1.0", "HTTP/1.", "HTTP/2", " ", "  ", "\t", "\r\n", "\r", "\n", ":", ": ", "/",
    "/a?b=c#d", "*", "Host", "Content-Length", "Transfer-Encoding", "chunked", "200", "404 Not Found", "0", "1f",
    ";ext=1", "\u0000", "\u007f", "é", "x", "abc", "999", "\r\n\r\n",
).map { it.encodeToByteArray() }

private fun seeds(vararg s: String) = s.map { it.encodeToByteArray() }

private val REQUESTS = seeds(
    "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
    "POST /path?q=1 HTTP/1.0\r\nContent-Length: 5\r\nX-A: b c\r\n\r\nhello",
    "GET /x HTTP/1.1\r\nA: 1\r\nB:\r\nC: \t v \r\n\r\n",
    "OPTIONS * HTTP/1.1\r\n\r\n",
)
private val RESPONSES = seeds(
    "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n",
    "HTTP/1.0 404 Not Found\r\nServer: s\r\nX: y\r\n\r\nbody",
    "HTTP/1.1 101 \r\nUpgrade: websocket\r\n\r\n",
    "HTTP/1.1 204\r\n\r\n",
)
private val URIS = seeds("/", "/a/b?c=d", "http://example.com:8080/p?q#f", "*", "example.com:443", "https://u@h/")
private val NAMES = seeds("content-type", "X-Custom-Header", "text/html; charset=utf-8", "a", "")
private val STATUSES = seeds("200", "404", "999", "100", "1000", "0")
private val HEADERS = seeds("Host: a\r\nAccept: */*\r\n\r\n", "X:\r\n\r\n", "\r\n", "A: b\r\nC: d e f\r\n\r\nrest")
private val CHUNKS = seeds("1f;ext=v\r\n", "0\r\n", "FFFFFFFFFFFFFFFF\r\n", "a\r\ndata", "3;a=b;c\r\n")

private fun Random.input(seeds: List<ByteArray>): ByteArray {
    if (nextBoolean()) {
        val out = ArrayList<Byte>()
        repeat(nextInt(0, 40)) {
            if (nextInt(4) == 0) out.add(nextInt(256).toByte()) else TOKENS[nextInt(TOKENS.size)].forEach { out.add(it) }
        }
        return out.toByteArray()
    }
    val b = seeds[nextInt(seeds.size)].toMutableList()
    repeat(nextInt(1, 4)) {
        if (b.isEmpty()) return@repeat
        val i = nextInt(b.size)
        when (nextInt(6)) {
            0 -> b[i] = (b[i].toInt() xor (1 shl nextInt(8))).toByte()
            1 -> b.add(i, nextInt(256).toByte())
            2 -> b.removeAt(i)
            3 -> TOKENS[nextInt(TOKENS.size)].reversed().forEach { b.add(i, it) }
            4 -> { val j = nextInt(i, b.size); b.addAll(j, b.subList(i, j).toList()) }
            else -> repeat(b.size - i) { b.removeAt(b.size - 1) }
        }
    }
    return b.toByteArray()
}

/** [input] placed at an offset inside a larger array of noise. */
private fun Random.embedded(input: ByteArray, pad: Int): ByteArray =
    ByteArray(pad + input.size + pad) { nextInt(256).toByte() }.also { input.copyInto(it, pad) }

private fun checkStatus(status: Int, length: Int, what: String) {
    when {
        status >= 0 -> assertTrue(status <= length, "$what: consumed $status of $length")
        status == ParseStatus.PARTIAL -> {}
        // httparse's `InvalidChunkSize` is a type of its own, not one of the head errors.
        status == ParseStatus.INVALID_CHUNK_SIZE -> {}
        else -> assertNotNull(ParseStatus.error(status), "$what: unknown status $status")
    }
}

private fun checkHeaders(h: HeaderSlots, from: Int, to: Int, what: String) {
    assertTrue(h.count in 0..h.capacity, "$what: count ${h.count}")
    for (i in 0 until h.count) {
        assertTrue(h.nameStart[i] in from..h.nameEnd[i] && h.nameEnd[i] <= to, "$what: header $i name")
        assertTrue(h.valueStart[i] in from..h.valueEnd[i] && h.valueEnd[i] <= to, "$what: header $i value")
    }
}

private fun show(b: ByteArray) = b.joinToString("") { val c = it.toInt() and 0xff; if (c in 32..126) c.toChar().toString() else "\\x" + c.toString(16) }

/**
 * Runs [parse] (input array, offset, length → status plus a snapshot of what it recorded, offsets relative to the
 * given offset) on every case and checks the properties above.
 */
private fun fuzz(seed: Long, seeds: List<ByteArray>, parse: (ByteArray, Int, Int) -> Pair<Int, List<Int>>) {
    val rng = Random(seed)
    var complete = 0
    repeat(CASES) {
        val input = rng.input(seeds)
        val what = "case $it `${show(input)}`"
        val (status, snapshot) = try { parse(input, 0, input.size) } catch (e: Throwable) { fail("$what threw $e") }
        checkStatus(status, input.size, what)
        val pad = rng.nextInt(1, 9)
        assertEquals(status to snapshot, parse(rng.embedded(input, pad), pad, input.size), "$what at offset $pad")
        if (status >= 0) complete++
        if (status > 0) {
            for (n in 0 until status) {
                assertEquals(ParseStatus.PARTIAL, parse(input, 0, n).first, "$what: prefix of $n bytes")
            }
        }
    }
    // Keep the generator honest: the prefix property needs complete heads to check.
    assertTrue(complete >= CASES / 20, "only $complete of $CASES cases parsed completely")
}

private fun HeaderSlots.snapshot(base: Int): List<Int> =
    (0 until count).flatMap { listOf(nameStart[it] - base, nameEnd[it] - base, valueStart[it] - base, valueEnd[it] - base) }

class ParseFuzzTest {
    private fun request(config: ParserConfig) = { b: ByteArray, off: Int, len: Int ->
        val req = ParsedRequest(HeaderSlots(16))
        val status = req.parse(b, off, len, config)
        if (status >= 0) {
            val what = "request `${show(b.copyOfRange(off, off + len))}`"
            assertTrue(req.methodStart in off..req.methodEnd && req.pathStart in req.methodEnd..req.pathEnd, what)
            assertTrue(req.pathEnd <= off + status && req.version in 0..1, what)
            checkHeaders(req.headers, off, off + status, what)
        }
        status to (if (status >= 0) listOf(req.methodStart - off, req.methodEnd - off, req.pathStart - off, req.pathEnd - off, req.version) +
            req.headers.snapshot(off) else emptyList())
    }

    private fun response(config: ParserConfig) = { b: ByteArray, off: Int, len: Int ->
        val res = ParsedResponse(HeaderSlots(16))
        val status = res.parse(b, off, len, config)
        if (status >= 0) {
            val what = "response `${show(b.copyOfRange(off, off + len))}`"
            assertTrue(res.code in 0..999 && res.version in 0..1, what)
            assertTrue(res.reasonStart in off..res.reasonEnd && res.reasonEnd <= off + status, what)
            checkHeaders(res.headers, off, off + status, what)
        }
        status to (if (status >= 0) listOf(res.code, res.version, res.reasonStart - off, res.reasonEnd - off) + res.headers.snapshot(off)
        else emptyList())
    }

    @Test fun parse_request() = fuzz(1, REQUESTS, request(ParserConfig.DEFAULT))

    @Test fun parse_request_multspaces() = fuzz(2, REQUESTS, request(ParserConfig(allowMultipleSpacesInRequestLineDelimiters = true)))

    @Test fun parse_response() = fuzz(3, RESPONSES, response(ParserConfig.DEFAULT))

    @Test fun parse_response_multspaces() = fuzz(4, RESPONSES, response(ParserConfig(allowMultipleSpacesInResponseStatusDelimiters = true)))

    @Test fun parse_headers() = fuzz(5, HEADERS) { b, off, len ->
        val h = HeaderSlots(16)
        val status = parseHeaders(b, off, len, h)
        if (status >= 0) checkHeaders(h, off, off + status, "headers")
        status to (if (status >= 0) h.snapshot(off) else emptyList())
    }

    @Test fun parse_chunk_size() = fuzz(6, CHUNKS) { b, off, len ->
        val out = ChunkSize()
        val status = parseChunkSize(b, off, len, out)
        status to (if (status >= 0) listOf(out.size.toLong().toInt(), (out.size shr 32).toInt()) else emptyList())
    }

    /** http 1.5.0 `fuzz_http`: builders and status parsing on arbitrary bytes fail only with the crate's own errors. */
    @Test
    fun fuzz_http() {
        val rng = Random(7)
        fun <T> attempt(what: String, f: () -> T): T? = try { f() } catch (e: HttpException) { null } catch (e: Throwable) {
            fail("$what threw $e")
        }
        repeat(CASES) {
            val uri = rng.input(URIS); val name = rng.input(NAMES); val value = rng.input(NAMES); val status = rng.input(STATUSES)
            val what = "case $it uri `${show(uri)}` name `${show(name)}` value `${show(value)}` status `${show(status)}`"
            val u = attempt(what) { Uri.fromBytes(uri) }
            val n = attempt(what) { HeaderName.fromBytes(name) }
            val v = attempt(what) { HeaderValue.fromBytes(value) }
            attempt(what) {
                Request.builder().also { b -> if (u != null) b.uri(u) }.also { b -> if (n != null && v != null) b.header(n, v) }.body(Unit)
            }
            attempt(what) { Response.builder().also { b -> if (n != null && v != null) b.header(n, v) }.body(Unit) }
            attempt(what) { StatusCode.fromBytes(status) }
        }
    }
}
