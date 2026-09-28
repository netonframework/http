package neton.http.h1

import neton.http.Method
import neton.http.RequestParts
import neton.http.ResponseParts
import neton.http.StatusCode
import neton.http.Version
import neton.http.h1.parse.ParseStatus
import neton.http.h1.parse.ParserConfig
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** hyper 1.11.1 `proto/h1/role.rs` tests, test for test (names in camelCase), plus the SPEC §3.9 deviations. */
class RoleTest {

    private class ServerParsed(val p: ServerHeadParser, val consumed: Int) {
        val parts: RequestParts get() = p.parts!!
        val decode: Long get() = p.bodyLength
        val keepAlive: Boolean get() = p.keepAlive
    }

    private class ClientParsed(val p: ClientHeadParser, val consumed: Int) {
        val parts: ResponseParts get() = p.parts!!
        val decode: Long get() = p.bodyLength
        val keepAlive: Boolean get() = p.keepAlive
        val wantsUpgrade: Boolean get() = p.wantsUpgrade
    }

    private fun serverParse(s: String, config: H1Config = H1Config()): ServerParsed {
        val p = ServerHeadParser(config)
        val b = ascii(s)
        val n = p.parse(b, 0, b.size)
        if (n < 0) fail("parse of ${s.take(40)}: status $n (${p.error})")
        return ServerParsed(p, n)
    }

    private fun serverParseErr(s: String, comment: String = "", config: H1Config = H1Config()): H1ParseError {
        val p = ServerHeadParser(config)
        val b = ascii(s)
        val n = p.parse(b, 0, b.size)
        assertTrue(n < ParseStatus.PARTIAL, "expected an error: $comment (got $n)")
        return p.error!!
    }

    private fun clientParse(s: String, method: Method? = Method.GET, config: H1Config = H1Config(), h09: Boolean = false): ClientParsed {
        val p = ClientHeadParser(config)
        val b = ascii(s)
        val n = p.parse(b, 0, b.size, method, h09)
        if (n < 0) fail("parse of ${s.take(40)}: status $n (${p.error})")
        return ClientParsed(p, n)
    }

    private fun clientParseErr(s: String, method: Method? = Method.GET, config: H1Config = H1Config(), h09: Boolean = false): H1ParseError {
        val p = ClientHeadParser(config)
        val b = ascii(s)
        val n = p.parse(b, 0, b.size, method, h09)
        assertTrue(n < ParseStatus.PARTIAL, "expected an error (got $n)")
        return p.error!!
    }

    private fun parserConfig(block: ParserConfig.() -> ParserConfig) = ParserConfig.DEFAULT.block()

    @Test
    fun testParseRequest() {
        val raw = "GET /echo HTTP/1.1\r\nHost: hyper.rs\r\n\r\n"
        val msg = serverParse(raw)
        assertEquals(raw.length, msg.consumed)
        assertEquals(Method.GET, msg.parts.method)
        assertEquals("/echo", msg.parts.uri.toString())
        assertEquals(Version.HTTP_11, msg.parts.version)
        assertEquals(1, msg.parts.headers.len())
        assertEquals("hyper.rs", msg.parts.headers["Host"]!!.toStr())
    }

    @Test
    fun testParseResponse() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
        val msg = clientParse(raw)
        assertEquals(raw.length, msg.consumed)
        assertEquals(StatusCode.OK, msg.parts.status)
        assertEquals(Version.HTTP_11, msg.parts.version)
        assertEquals(1, msg.parts.headers.len())
        assertEquals("0", msg.parts.headers["Content-Length"]!!.toStr())
    }

    @Test
    fun testParseRequestErrors() {
        serverParseErr("GET htt:p// HTTP/1.1\r\nHost: hyper.rs\r\n\r\n")
    }

    private val h09Response = "Baguettes are super delicious, don't you agree?"

    @Test
    fun testParseResponseH09Allowed() {
        val msg = clientParse(h09Response, h09 = true)
        assertEquals(0, msg.consumed)                       // the bytes stay: they are the body
        assertEquals(StatusCode.OK, msg.parts.status)
        assertEquals(Version.HTTP_09, msg.parts.version)
        assertEquals(0, msg.parts.headers.len())
    }

    @Test
    fun testParseResponseH09Rejected() {
        clientParseErr(h09Response)
    }

    private val responseWithWhitespaceBetweenHeaderNameAndColon =
        "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Credentials : true\r\n\r\n"

    @Test
    fun testParseAllowResponseWithSpacesBeforeColons() {
        val config = H1Config(parser = parserConfig { copy(allowSpacesAfterHeaderNameInResponses = true) })
        val msg = clientParse(responseWithWhitespaceBetweenHeaderNameAndColon, config = config)
        assertEquals(responseWithWhitespaceBetweenHeaderNameAndColon.length, msg.consumed)
        assertEquals(StatusCode.OK, msg.parts.status)
        assertEquals(Version.HTTP_11, msg.parts.version)
        assertEquals(1, msg.parts.headers.len())
        assertEquals("true", msg.parts.headers["Access-Control-Allow-Credentials"]!!.toStr())
    }

    @Test
    fun testParseRejectResponseWithSpacesBeforeColons() {
        clientParseErr(responseWithWhitespaceBetweenHeaderNameAndColon)
    }

    private val requestWithMultipleSpacesInRequestLine = "GET  /echo  HTTP/1.1\r\nHost: hyper.rs\r\n\r\n"

    @Test
    fun testParseAllowRequestWithMultipleSpacesInRequestLine() {
        val config = H1Config(parser = parserConfig { copy(allowMultipleSpacesInRequestLineDelimiters = true) })
        val msg = serverParse(requestWithMultipleSpacesInRequestLine, config)
        assertEquals(requestWithMultipleSpacesInRequestLine.length, msg.consumed)
        assertEquals(Method.GET, msg.parts.method)
        assertEquals("/echo", msg.parts.uri.toString())
        assertEquals(Version.HTTP_11, msg.parts.version)
        assertEquals(1, msg.parts.headers.len())
        assertEquals("hyper.rs", msg.parts.headers["Host"]!!.toStr())
    }

    @Test
    fun testParseRejectRequestWithMultipleSpacesInRequestLine() {
        serverParseErr(requestWithMultipleSpacesInRequestLine)
    }

    @Test
    fun testParsePreserveHeaderCaseInRequest() {
        val msg = serverParse("GET / HTTP/1.1\r\nHost: hyper.rs\r\nX-BREAD: baguette\r\n\r\n", H1Config(preserveHeaderCase = true))
        val orig = assertNotNull(msg.parts.extensions.get<HeaderCaseMap>())
        assertEquals(listOf("Host"), orig.getAll(HeaderName.fromStatic("host")).map { it.decodeToString() })
        assertEquals(listOf("X-BREAD"), orig.getAll(HeaderName.fromStatic("x-bread")).map { it.decodeToString() })
    }

    /**
     * hyper `test_decoder_request`. The Transfer-Encoding + Content-Length cases are hyper's behaviour, which this
     * library keeps behind `lenientTeWithCl`; the default rejects them (⚖️ SPEC §3.9, see [teWithClRequestIsRejectedByDefault]).
     */
    @Test
    fun testDecoderRequest() {
        val lenient = H1Config(lenientTeWithCl = true)
        // no length or transfer-encoding means 0-length body
        assertEquals(0L, serverParse("GET / HTTP/1.1\r\n\r\n").decode)
        assertEquals(0L, serverParse("POST / HTTP/1.1\r\n\r\n").decode)
        // transfer-encoding: chunked
        assertEquals(BodyLength.CHUNKED, serverParse("POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n").decode)
        assertEquals(BodyLength.CHUNKED, serverParse("POST / HTTP/1.1\r\ntransfer-encoding: gzip, chunked\r\n\r\n").decode)
        assertEquals(BodyLength.CHUNKED, serverParse("POST / HTTP/1.1\r\ntransfer-encoding: gzip\r\ntransfer-encoding: chunked\r\n\r\n").decode)
        // content-length
        assertEquals(10L, serverParse("POST / HTTP/1.1\r\ncontent-length: 10\r\n\r\n").decode)
        // transfer-encoding and content-length = chunked
        for (raw in listOf(
            "POST / HTTP/1.1\r\ncontent-length: 10\r\ntransfer-encoding: chunked\r\n\r\n",
            "POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\ncontent-length: 10\r\n\r\n",
            "POST / HTTP/1.1\r\ntransfer-encoding: gzip\r\ncontent-length: 10\r\ntransfer-encoding: chunked\r\n\r\n",
            "POST / HTTP/1.1\r\nconnection: keep-alive\r\ncontent-length: 10\r\ntransfer-encoding: chunked\r\n\r\n",
        )) {
            val msg = serverParse(raw, lenient)
            assertEquals(BodyLength.CHUNKED, msg.decode)
            assertFalse(msg.parts.headers.containsKey(HeaderName.CONTENT_LENGTH))
            assertFalse(msg.keepAlive)
        }
        // multiple content-lengths of same value are fine
        assertEquals(10L, serverParse("POST / HTTP/1.1\r\ncontent-length: 10\r\ncontent-length: 10\r\n\r\n").decode)
        // multiple content-lengths with different values is an error
        serverParseErr("POST / HTTP/1.1\r\ncontent-length: 10\r\ncontent-length: 11\r\n\r\n", "multiple content-lengths")
        // content-length with prefix is not allowed
        serverParseErr("POST / HTTP/1.1\r\ncontent-length: +10\r\n\r\n", "prefixed content-length")
        // transfer-encoding that isn't chunked is an error
        serverParseErr("POST / HTTP/1.1\r\ntransfer-encoding: gzip\r\n\r\n", "transfer-encoding but not chunked")
        serverParseErr("POST / HTTP/1.1\r\ntransfer-encoding: chunked, gzip\r\n\r\n", "transfer-encoding doesn't end in chunked")
        serverParseErr("POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\ntransfer-encoding: afterlol\r\n\r\n",
            "transfer-encoding multiple lines doesn't end in chunked")
        // http/1.0
        assertEquals(10L, serverParse("POST / HTTP/1.0\r\ncontent-length: 10\r\n\r\n").decode)
        // 1.0 doesn't understand chunked, so its an error
        serverParseErr("POST / HTTP/1.0\r\ntransfer-encoding: chunked\r\n\r\n", "1.0 chunked")
    }

    /** hyper `test_decoder_response`; the TE + CL case is rejected by default (⚖️ SPEC §3.9), hyper's answer is CHUNKED. */
    @Test
    fun testDecoderResponse() {
        fun parseIgnores(s: String) {
            val p = ClientHeadParser()
            val b = ascii(s)
            assertEquals(ParseStatus.PARTIAL, p.parse(b, 0, b.size, Method.GET))
            assertEquals(1, p.informational.size)
        }
        // no content-length or transfer-encoding means close-delimited
        assertEquals(BodyLength.CLOSE_DELIMITED, clientParse("HTTP/1.1 200 OK\r\n\r\n").decode)
        // 204 and 304 never have a body
        assertEquals(0L, clientParse("HTTP/1.1 204 No Content\r\n\r\n").decode)
        assertEquals(0L, clientParse("HTTP/1.1 304 Not Modified\r\n\r\n").decode)
        // content-length
        assertEquals(8L, clientParse("HTTP/1.1 200 OK\r\ncontent-length: 8\r\n\r\n").decode)
        assertEquals(8L, clientParse("HTTP/1.1 200 OK\r\ncontent-length: 8\r\ncontent-length: 8\r\n\r\n").decode)
        clientParseErr("HTTP/1.1 200 OK\r\ncontent-length: 8\r\ncontent-length: 9\r\n\r\n")
        clientParseErr("HTTP/1.1 200 OK\r\ncontent-length: +8\r\n\r\n")
        // transfer-encoding: chunked
        assertEquals(BodyLength.CHUNKED, clientParse("HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n").decode)
        // transfer-encoding not-chunked is close-delimited
        assertEquals(BodyLength.CLOSE_DELIMITED, clientParse("HTTP/1.1 200 OK\r\ntransfer-encoding: yolo\r\n\r\n").decode)
        // transfer-encoding and content-length: hyper = chunked; ⚖️ here an error
        assertEquals(H1ParseError.TransferEncodingWithContentLength,
            clientParseErr("HTTP/1.1 200 OK\r\ncontent-length: 10\r\ntransfer-encoding: chunked\r\n\r\n"))
        // HEAD can have content-length, but not body
        assertEquals(0L, clientParse("HTTP/1.1 200 OK\r\ncontent-length: 8\r\n\r\n", Method.HEAD).decode)
        // CONNECT with 200 never has body
        clientParse("HTTP/1.1 200 OK\r\n\r\n", Method.CONNECT).let { msg ->
            assertEquals(0L, msg.decode)
            assertFalse(msg.keepAlive, "should be upgrade")
            assertTrue(msg.wantsUpgrade, "should be upgrade")
        }
        // CONNECT receiving non 200 can have a body
        assertEquals(BodyLength.CLOSE_DELIMITED, clientParse("HTTP/1.1 400 Bad Request\r\n\r\n", Method.CONNECT).decode)
        // 1xx status codes
        parseIgnores("HTTP/1.1 100 Continue\r\n\r\n")
        parseIgnores("HTTP/1.1 103 Early Hints\r\n\r\n")
        // 101 upgrade not supported yet
        clientParse("HTTP/1.1 101 Switching Protocols\r\n\r\n").let { msg ->
            assertEquals(0L, msg.decode)
            assertFalse(msg.keepAlive, "should be last")
            assertTrue(msg.wantsUpgrade, "should be upgrade")
        }
        // http/1.0
        assertEquals(BodyLength.CLOSE_DELIMITED, clientParse("HTTP/1.0 200 OK\r\n\r\n").decode)
        // 1.0 doesn't understand chunked
        clientParseErr("HTTP/1.0 200 OK\r\ntransfer-encoding: chunked\r\n\r\n")
        // keep-alive
        assertTrue(clientParse("HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n").keepAlive, "HTTP/1.1 keep-alive is default")
        assertFalse(clientParse("HTTP/1.1 200 OK\r\ncontent-length: 0\r\nconnection: foo, close, bar\r\n\r\n").keepAlive,
            "connection close is always close")
        assertFalse(clientParse("HTTP/1.0 200 OK\r\ncontent-length: 0\r\n\r\n").keepAlive, "HTTP/1.0 close is default")
        assertTrue(clientParse("HTTP/1.0 200 OK\r\ncontent-length: 0\r\nconnection: foo, keep-alive, bar\r\n\r\n").keepAlive,
            "connection keep-alive is always keep-alive")
    }

    @Test
    fun testClientObsFoldLine() {
        fun unfold(src: String): String {
            // Through the parser: a response whose one header value is [src].
            val config = H1Config(parser = parserConfig { copy(allowObsoleteMultilineHeadersInResponses = true) })
            return clientParse("HTTP/1.1 200 OK\r\nx: $src\r\n\r\n", config = config).parts.headers["x"]!!.toStr()
        }
        assertEquals("a normal line", unfold("a normal line"))
        assertEquals("obs fold line", unfold("obs\r\n fold\r\n\t line"))
    }

    private fun requestHead(vararg headers: Pair<String, String>): RequestParts = RequestParts().also { p ->
        for ((n, v) in headers) p.headers.insert(n, HeaderValue.fromStatic(v))
    }

    private fun responseHead(vararg headers: Pair<String, String>): ResponseParts = ResponseParts().also { p ->
        for ((n, v) in headers) p.headers.insert(n, HeaderValue.fromStatic(v))
    }

    @Test
    fun testClientRequestEncodeTitleCase() {
        val head = requestHead("content-length" to "10", "content-type" to "application/json", "*-*" to "o_o")
        val dst = Buffer()
        ClientHeadEncoder.encode(head, 10, H1Config(titleCaseHeaders = true), dst)
        assertEquals("GET / HTTP/1.1\r\nContent-Length: 10\r\nContent-Type: application/json\r\n*-*: o_o\r\n\r\n", dst.text())
    }

    private fun origCase(): HeaderCaseMap = HeaderCaseMap().also { it.insert(HeaderName.CONTENT_LENGTH, ascii("CONTENT-LENGTH")) }

    @Test
    fun testClientRequestEncodeOrigCase() {
        val head = requestHead("content-length" to "10", "content-type" to "application/json")
        head.extensions.insert(origCase())
        val dst = Buffer()
        ClientHeadEncoder.encode(head, 10, H1Config(), dst)
        assertEquals("GET / HTTP/1.1\r\nCONTENT-LENGTH: 10\r\ncontent-type: application/json\r\n\r\n", dst.text())
    }

    @Test
    fun testClientRequestEncodeOrigAndTitleCase() {
        val head = requestHead("content-length" to "10", "content-type" to "application/json")
        head.extensions.insert(origCase())
        val dst = Buffer()
        ClientHeadEncoder.encode(head, 10, H1Config(titleCaseHeaders = true), dst)
        assertEquals("GET / HTTP/1.1\r\nCONTENT-LENGTH: 10\r\nContent-Type: application/json\r\n\r\n", dst.text())
    }

    @Test
    fun testServerEncodeConnectMethod() {
        val plan = ServerHeadEncoder.encode(ResponseParts(), null, Method.CONNECT, true, H1Config(), true, Buffer())
        assertNull(plan.error)
        assertTrue(plan.isLast)
    }

    @Test
    fun testServerResponseEncodeTitleCase() {
        val head = responseHead("content-length" to "10", "content-type" to "application/json", "weird--header" to "")
        val dst = Buffer()
        assertNull(ServerHeadEncoder.encode(head, 10, null, true, H1Config(titleCaseHeaders = true), true, dst).error)
        val expected = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\nContent-Type: application/json\r\nWeird--Header: \r\n"
        assertEquals(expected, dst.text().take(expected.length))
    }

    @Test
    fun testServerResponseEncodeOrigCase() {
        val head = responseHead("content-length" to "10", "content-type" to "application/json")
        head.extensions.insert(origCase())
        val dst = Buffer()
        ServerHeadEncoder.encode(head, 10, null, true, H1Config(), true, dst)
        val expected = "HTTP/1.1 200 OK\r\nCONTENT-LENGTH: 10\r\ncontent-type: application/json\r\ndate: "
        assertEquals(expected, dst.text().take(expected.length))
    }

    @Test
    fun testServerResponseEncodeOrigAndTitleCase() {
        val head = responseHead("content-length" to "10", "content-type" to "application/json")
        head.extensions.insert(origCase())
        val dst = Buffer()
        ServerHeadEncoder.encode(head, 10, null, true, H1Config(titleCaseHeaders = true), true, dst)
        // this will also test that the date does exist
        val expected = "HTTP/1.1 200 OK\r\nCONTENT-LENGTH: 10\r\nContent-Type: application/json\r\nDate: "
        assertEquals(expected, dst.text().take(expected.length))
    }

    @Test
    fun testDisabledDateHeader() {
        val head = responseHead("content-length" to "10", "content-type" to "application/json")
        head.extensions.insert(origCase())
        val dst = Buffer()
        ServerHeadEncoder.encode(head, 10, null, true, H1Config(titleCaseHeaders = true), false, dst)
        assertEquals("HTTP/1.1 200 OK\r\nCONTENT-LENGTH: 10\r\nContent-Type: application/json\r\n\r\n", dst.text())
    }

    @Test
    fun parseHeaderHtabs() {
        val parsed = clientParse("HTTP/1.1 200 OK\r\nserver: hello\tworld\r\n\r\n")
        assertEquals("hello\tworld", parsed.parts.headers["server"]!!.toStr())
    }

    @Test
    fun parseTooLargeHeaders() {
        fun genReqWithHeaders(num: Int) = buildString {
            append("GET / HTTP/1.1\r\n"); for (i in 0 until num) append("key$i: val$i\r\n"); append("\r\n")
        }
        fun genRespWithHeaders(num: Int) = buildString {
            append("HTTP/1.1 200 OK\r\n"); for (i in 0 until num) append("key$i: val$i\r\n"); append("\r\n")
        }
        fun parse(maxHeaders: Int?, genSize: Int, shouldSuccess: Boolean) {
            val config = if (maxHeaders == null) H1Config() else H1Config(maxHeaders = maxHeaders)
            // server side
            if (shouldSuccess) serverParse(genReqWithHeaders(genSize), config) else serverParseErr(genReqWithHeaders(genSize), config = config)
            // client side
            if (shouldSuccess) clientParse(genRespWithHeaders(genSize), null, config)
            else clientParseErr(genRespWithHeaders(genSize), null, config)
        }
        // check generator
        assertEquals("GET / HTTP/1.1\r\n\r\n", genReqWithHeaders(0))
        assertEquals("GET / HTTP/1.1\r\nkey0: val0\r\n\r\n", genReqWithHeaders(1))
        assertEquals("GET / HTTP/1.1\r\nkey0: val0\r\nkey1: val1\r\n\r\n", genReqWithHeaders(2))
        assertEquals("GET / HTTP/1.1\r\nkey0: val0\r\nkey1: val1\r\nkey2: val2\r\n\r\n", genReqWithHeaders(3))
        // default max_headers is 100: ≤ 100 accepted, more rejected
        for (n in listOf(0, 1, 50, 99, 100)) parse(null, n, true)
        for (n in listOf(101, 102, 200)) parse(null, n, false)
        // max_headers is 0: without header accepted, with header(s) rejected
        parse(0, 0, true)
        parse(0, 1, false); parse(0, 100, false)
        // max_headers is 200
        for (n in listOf(0, 1, 100, 200)) parse(200, n, true)
        for (n in listOf(201, 210)) parse(200, n, false)
    }

    @Test
    fun testIsCompleteFast() {
        fun complete(s: String, n: Int) = ascii(s).let { isCompleteFast(it, 0, it.size, n) }
        for (s in listOf("GET / HTTP/1.1\r\na: b\r\n\r\n", "GET / HTTP/1.1\na: b\n\n", "GET / HTTP/1.1\r\na: b\n\r\n")) {
            for (n in s.indices) assertTrue(complete(s, n), "$s; $n")
        }
        // Not
        for (s in listOf("GET / HTTP/1.1\r\na: b\r\n\r", "GET / HTTP/1.1\na: b\n", "GET / HTTP/1.1\r\na: b\n\r")) {
            for (n in s.indices) assertFalse(complete(s, n), "$s; $n")
        }
    }

    /**
     * hyper `test_parse_accepts_lf_crlf_terminator`: httparse accepts a bare-LF line ending. The safety baseline
     * rejects bare LF by default (⚖️ SPEC §3.9); with `allowBareLf` it is hyper's behaviour.
     */
    @Test
    fun testParseAcceptsLfCrlfTerminator() {
        val raw = "GET / HTTP/1.1\r\na: b\n\r\n"
        serverParse(raw, H1Config(parser = parserConfig { copy(allowBareLf = true) }))
        serverParseErr(raw)
    }

    @Test
    fun testWriteHeadersOrigCaseEmptyValue() {
        val headers = RequestParts().headers
        val name = HeaderName.fromStatic("x-empty")
        headers.insert(name, HeaderValue.fromStr(""))
        val cases = HeaderCaseMap().also { it.insert(name, ascii("X-EmptY")) }
        val dst = Buffer()
        writeHeadersOriginalCase(headers, cases, dst, false)
        assertEquals("X-EmptY:\r\n", dst.text(), "there should be no space between the colon and CRLF")
    }

    @Test
    fun testWriteHeadersOrigCaseMultipleEntries() {
        val headers = RequestParts().headers
        val name = HeaderName.fromStatic("x-empty")
        headers.insert(name, HeaderValue.fromStr("a"))
        headers.append(name, HeaderValue.fromStr("b"))
        val cases = HeaderCaseMap().also { it.insert(name, ascii("X-Empty")); it.append(name, ascii("X-EMPTY")) }
        val dst = Buffer()
        writeHeadersOriginalCase(headers, cases, dst, false)
        assertEquals("X-Empty: a\r\nX-EMPTY: b\r\n", dst.text())
    }

    // ---- SPEC §3.9 deviations and the encoder matrix -------------------------------------------------------

    @Test
    fun teWithClRequestIsRejectedByDefault() {
        for (raw in listOf(
            "POST / HTTP/1.1\r\ncontent-length: 10\r\ntransfer-encoding: chunked\r\n\r\n",
            "POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\ncontent-length: 10\r\n\r\n",
        )) {
            val e = serverParseErr(raw)
            assertEquals(H1ParseError.TransferEncodingWithContentLength, e)
            assertEquals(400, e.autoStatus)
        }
    }

    @Test
    fun requestLineAndHeadLimits() {
        // ⚖️ request line > 8 KiB → 414, complete or not.
        val longPath = "/" + "a".repeat(9000)
        assertEquals(H1ParseError.UriTooLong, serverParseErr("GET $longPath HTTP/1.1\r\n\r\n"))
        val p = ServerHeadParser()
        val partial = ascii("GET $longPath")
        assertTrue(p.parse(partial, 0, partial.size) < ParseStatus.PARTIAL)
        assertEquals(414, p.error!!.autoStatus)
        // A line just under the limit is fine; raising the limit admits the long one.
        serverParse("GET /" + "a".repeat(8000) + " HTTP/1.1\r\n\r\n")
        serverParse("GET $longPath HTTP/1.1\r\n\r\n", H1Config(maxRequestLineSize = 16 * 1024))
        // hyper's URI limit (65534) holds with any line limit.
        assertEquals(H1ParseError.UriTooLong,
            serverParseErr("GET /" + "a".repeat(65534) + " HTTP/1.1\r\n\r\n", config = H1Config(maxRequestLineSize = 1 shl 20, maxHeaderSectionSize = 1 shl 20)))
        // ⚖️ head section > 64 KiB → 431, complete or not.
        val big = "GET / HTTP/1.1\r\n" + (0 until 70).joinToString("") { "x$it: ${"v".repeat(1000)}\r\n" }
        assertEquals(H1ParseError.TooLarge, serverParseErr(big + "\r\n"))
        val q = ServerHeadParser()
        val bigBytes = ascii(big)
        assertTrue(q.parse(bigBytes, 0, bigBytes.size) < ParseStatus.PARTIAL)
        assertEquals(431, q.error!!.autoStatus)
        // Partial and within the limits: more bytes needed.
        val r = ServerHeadParser()
        val small = ascii("GET / HTTP/1.1\r\nHost: a\r\n")
        assertEquals(ParseStatus.PARTIAL, r.parse(small, 0, small.size))
    }

    @Test
    fun contentLengthRange() {
        assertEquals(Long.MAX_VALUE, serverParse("POST / HTTP/1.1\r\ncontent-length: ${Long.MAX_VALUE}\r\n\r\n").decode)
        // Past a Long but within u64 → 431 (hyper answers 431 at the top of the u64 range), past u64 → 400.
        assertEquals(H1ParseError.TooLarge, serverParseErr("POST / HTTP/1.1\r\ncontent-length: 18446744073709551615\r\n\r\n"))
        assertEquals(H1ParseError.ContentLengthInvalid, serverParseErr("POST / HTTP/1.1\r\ncontent-length: 18446744073709551616\r\n\r\n"))
        assertEquals(H1ParseError.TooLarge, clientParseErr("HTTP/1.1 200 OK\r\ncontent-length: 9223372036854775808\r\n\r\n"))
        // Request: a comma list is rejected (hyper); response: an equal list is accepted.
        serverParseErr("POST / HTTP/1.1\r\ncontent-length: 5, 5\r\n\r\n")
        assertEquals(5L, clientParse("HTTP/1.1 200 OK\r\ncontent-length: 5, 5\r\n\r\n").decode)
    }

    @Test
    fun serverRequestFlags() {
        serverParse("POST / HTTP/1.1\r\nexpect: 100-Continue\r\ncontent-length: 1\r\n\r\n").p.let { assertTrue(it.expectContinue) }
        serverParse("GET / HTTP/1.1\r\nupgrade: websocket\r\n\r\n").p.let { assertTrue(it.wantsUpgrade) }
        serverParse("GET / HTTP/1.0\r\nupgrade: websocket\r\n\r\n").p.let { assertFalse(it.wantsUpgrade) }
        serverParse("CONNECT a.com:443 HTTP/1.1\r\n\r\n").p.let { assertTrue(it.wantsUpgrade) }
        assertTrue(serverParse("GET / HTTP/1.0\r\nconnection: keep-alive\r\n\r\n").keepAlive)
        assertFalse(serverParse("GET / HTTP/1.1\r\nconnection: close\r\n\r\n").keepAlive)
        // An invalid method token / URI token map to Method / Uri.
        assertEquals(H1ParseError.Method, serverParseErr("G(T / HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun clientSkipsInformationalAndKeepsReason() {
        val raw = "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 103 Early Hints\r\nlink: </a>\r\n\r\nHTTP/1.1 200 Fine\r\ncontent-length: 0\r\n\r\n"
        val msg = clientParse(raw)
        assertEquals(raw.length, msg.consumed)
        assertEquals(2, msg.p.informational.size)
        assertEquals(StatusCode.OK, msg.parts.status)
        assertEquals("Fine", msg.parts.extensions.get<ReasonPhrase>().toString())
        assertNull(clientParse("HTTP/1.1 200 OK\r\n\r\n").parts.extensions.get<ReasonPhrase>())
    }

    private fun serverEncode(
        parts: ResponseParts, body: Long?, method: Method? = Method.GET, keepAlive: Boolean = true, date: Boolean = false,
    ): Pair<EncodePlan, String> {
        val dst = Buffer()
        val plan = ServerHeadEncoder.encode(parts, body, method, keepAlive, H1Config(), date, dst)
        return plan to dst.text()
    }

    @Test
    fun serverEncodeLengthMatrix() {
        // Known length → content-length; unknown → chunked (1.1) / close-delimited (1.0); none → content-length: 0.
        serverEncode(responseHead(), 5).let { (p, s) ->
            assertEquals("HTTP/1.1 200 OK\r\ncontent-length: 5\r\n\r\n", s); assertEquals(EncodePlan.LENGTH, p.kind); assertEquals(5L, p.length)
        }
        serverEncode(responseHead(), OutgoingBody.UNKNOWN).let { (p, s) ->
            assertEquals("HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n", s); assertEquals(EncodePlan.CHUNKED, p.kind)
        }
        serverEncode(responseHead().also { it.version = Version.HTTP_10 }, OutgoingBody.UNKNOWN).let { (p, s) ->
            assertEquals("HTTP/1.0 200 OK\r\n\r\n", s); assertEquals(EncodePlan.CLOSE_DELIMITED, p.kind)
        }
        serverEncode(responseHead(), null).let { (_, s) -> assertEquals("HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n", s) }
        // HEAD: no implicit zero, body forced to 0; a user content-length is kept.
        serverEncode(responseHead(), null, Method.HEAD).let { (_, s) -> assertEquals("HTTP/1.1 200 OK\r\n\r\n", s) }
        serverEncode(responseHead("content-length" to "7"), null, Method.HEAD).let { (p, s) ->
            assertEquals("HTTP/1.1 200 OK\r\ncontent-length: 7\r\n\r\n", s); assertEquals(0L, p.length)
        }
        serverEncode(responseHead(), 5, Method.HEAD).let { (p, _) -> assertEquals(0L, p.length) }
        // 204 / 304: no content-length, no body.
        serverEncode(responseHead().also { it.status = StatusCode.NO_CONTENT }, 5).let { (p, s) ->
            assertEquals("HTTP/1.1 204 No Content\r\n\r\n", s); assertEquals(0L, p.length)
        }
        // Unknown body with a user content-length → length; differing duplicates → error, nothing written.
        serverEncode(responseHead("content-length" to "3"), OutgoingBody.UNKNOWN).let { (p, _) -> assertEquals(3L, p.length) }
        val dup = responseHead("content-length" to "3").also { it.headers.append("content-length", HeaderValue.fromStatic("4")) }
        serverEncode(dup, OutgoingBody.UNKNOWN).let { (p, s) -> assertEquals(H1EncodeError.UnexpectedHeader, p.error); assertEquals("", s) }
        // A user transfer-encoding not ending in chunked gets `, chunked`.
        serverEncode(responseHead("transfer-encoding" to "gzip"), OutgoingBody.UNKNOWN).let { (p, s) ->
            assertEquals("HTTP/1.1 200 OK\r\ntransfer-encoding: gzip, chunked\r\n\r\n", s); assertEquals(EncodePlan.CHUNKED, p.kind)
        }
        // Trailer names are collected for a chunked body.
        serverEncode(responseHead("trailer" to "grpc-status, X-Sum"), OutgoingBody.UNKNOWN).let { (p, _) ->
            assertEquals(listOf("grpc-status", "x-sum"), p.allowedTrailers!!.map { it.asStr() })
        }
    }

    @Test
    fun serverEncodeStatusLineAndConnection() {
        serverEncode(responseHead().also { it.status = StatusCode.NOT_FOUND }, 0).let { (_, s) -> assertTrue(s.startsWith("HTTP/1.1 404 Not Found\r\n")) }
        serverEncode(responseHead().also { it.extensions.insert(ReasonPhrase(ascii("Fine"))) }, 0).let { (_, s) ->
            assertTrue(s.startsWith("HTTP/1.1 200 Fine\r\n"))
        }
        serverEncode(responseHead().also { it.version = Version.HTTP_2 }, 0).let { (_, s) -> assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n")) }
        serverEncode(responseHead("connection" to "close"), 0).let { (p, _) -> assertTrue(p.isLast) }
        serverEncode(responseHead(), 0, keepAlive = false).let { (p, _) -> assertTrue(p.isLast) }
        serverEncode(responseHead(), 0).let { (p, _) -> assertFalse(p.isLast) }
        serverEncode(responseHead().also { it.status = StatusCode.SWITCHING_PROTOCOLS }, null).let { (p, _) -> assertTrue(p.isLast) }
        // A 1xx from the service: a 500 goes out and the connection closes.
        serverEncode(responseHead("x" to "y").also { it.status = StatusCode.CONTINUE }, 3).let { (p, s) ->
            assertEquals(H1EncodeError.UnsupportedStatusCode, p.error); assertTrue(p.isLast)
            assertEquals("HTTP/1.1 500 Internal Server Error\r\ncontent-length: 0\r\n\r\n", s)
        }
        // The date header: 29 bytes, IMF-fixdate.
        serverEncode(responseHead(), 0, date = true).let { (_, s) ->
            val m = Regex("date: ([A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT)\r\n\r\n$").find(s)
            assertNotNull(m, s); assertEquals(29, m.groupValues[1].length)
        }
    }

    @Test
    fun httpDateFormat() {
        assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", HttpDate.format(784111777))
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", HttpDate.format(0))
        assertEquals("Tue, 29 Feb 2000 23:59:59 GMT", HttpDate.format(951868799))
    }

    @Test
    fun clientSetLength() {
        fun enc(parts: RequestParts, body: Long?): Pair<EncodePlan, String> {
            val dst = Buffer(); val p = ClientHeadEncoder.encode(parts, body, H1Config(), dst); return p to dst.text()
        }
        enc(requestHead(), null).let { (p, s) -> assertEquals("GET / HTTP/1.1\r\n\r\n", s); assertEquals(0L, p.length) }
        enc(requestHead(), 4).let { (p, s) -> assertEquals("GET / HTTP/1.1\r\ncontent-length: 4\r\n\r\n", s); assertEquals(4L, p.length) }
        // Unknown length: GET / HEAD / CONNECT send no body; others go chunked.
        enc(requestHead(), OutgoingBody.UNKNOWN).let { (p, s) -> assertEquals("GET / HTTP/1.1\r\n\r\n", s); assertEquals(EncodePlan.LENGTH, p.kind) }
        enc(requestHead().also { it.method = Method.POST }, OutgoingBody.UNKNOWN).let { (p, s) ->
            assertEquals("POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n", s); assertEquals(EncodePlan.CHUNKED, p.kind)
        }
        // A user transfer-encoding wins and drops content-length; `chunked` is added when missing.
        enc(requestHead("transfer-encoding" to "gzip", "content-length" to "4").also { it.method = Method.POST }, 4).let { (p, s) ->
            assertEquals("POST / HTTP/1.1\r\ntransfer-encoding: gzip, chunked\r\n\r\n", s); assertEquals(EncodePlan.CHUNKED, p.kind)
        }
        // HTTP/1.0: no chunked; unknown length without content-length → no body.
        enc(requestHead("transfer-encoding" to "chunked").also { it.method = Method.POST; it.version = Version.HTTP_10 }, OutgoingBody.UNKNOWN)
            .let { (p, s) -> assertEquals("POST / HTTP/1.0\r\n\r\n", s); assertEquals(0L, p.length) }
        // No body removes a transfer-encoding.
        enc(requestHead("transfer-encoding" to "chunked"), null).let { (_, s) -> assertEquals("GET / HTTP/1.1\r\n\r\n", s) }
    }
}
