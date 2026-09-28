package neton.http.h1.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The standalone `#[test]` functions of httparse 1.10.1 `src/lib.rs` (51; the file's other two `#[test]` lines
 * sit inside the `req!` / `res!` macro definitions, whose 42 uses are in [HttpParseTableTest]), plus the
 * `parse_headers`, `parse_chunk_size` and `ParserConfig` doc examples.
 *
 * Reference names are kept. Where the reference reuses one `Response` / `Request` for several parses (possible
 * because a failed parse restores it), a fresh parse is used each time, which is equivalent.
 */
class HttpParseTest {
    private val default = ParserConfig.DEFAULT

    /** Check all subset permutations of a partial request line with no headers. */
    @Test fun partial_permutations() {
        val reqStr = "GET / HTTP/1.1\r\n\r\n"
        val r = ParsedRequest(HeaderSlots(NUM_OF_HEADERS))
        for (i in 0 until reqStr.length) {
            val status = r.parse(b(reqStr.substring(0, i)))
            assertStatus(PARTIAL, status, "partial request line should return partial. Portion which failed: '${reqStr.substring(0, i)}' (below $i)")
        }
    }

    private val responseWithWhitespaceBetweenHeaderNameAndColon =
        b("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Credentials : true\r\nBread: baguette\r\n\r\n")

    @Test fun test_forbid_response_with_whitespace_between_header_name_and_colon() {
        val res = parseRes(responseWithWhitespaceBetweenHeaderNameAndColon, capacity = 2)
        assertStatus(err(HttpParseError.HeaderName), res.status)
    }

    @Test fun test_allow_response_with_whitespace_between_header_name_and_colon() {
        val res = parseRes(
            responseWithWhitespaceBetweenHeaderNameAndColon,
            ParserConfig(allowSpacesAfterHeaderNameInResponses = true), capacity = 2,
        )
        assertStatus(complete(77), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(2, res.headerCount)
        res.assertHeader(0, "Access-Control-Allow-Credentials", "true")
        res.assertHeader(1, "Bread", "baguette")
    }

    @Test fun test_ignore_header_line_with_whitespaces_after_header_name_in_response() {
        val res = parseRes(
            responseWithWhitespaceBetweenHeaderNameAndColon,
            ParserConfig(ignoreInvalidHeadersInResponses = true), capacity = 2,
        )
        assertStatus(complete(77), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Bread", "baguette")
    }

    private val requestWithWhitespaceBetweenHeaderNameAndColon = b("GET / HTTP/1.1\r\nHost : localhost\r\n\r\n")

    @Test fun test_forbid_request_with_whitespace_between_header_name_and_colon() {
        val req = parseReq(requestWithWhitespaceBetweenHeaderNameAndColon, capacity = 1)
        assertStatus(err(HttpParseError.HeaderName), req.status)
    }

    @Test fun test_ignore_header_line_with_whitespaces_after_header_name_in_request() {
        val req = parseReq(
            requestWithWhitespaceBetweenHeaderNameAndColon,
            ParserConfig(ignoreInvalidHeadersInRequests = true), capacity = 2,
        )
        assertStatus(complete(36), req.status)
    }

    private val responseWithObsoleteLineFoldingAtStart =
        b("HTTP/1.1 200 OK\r\nLine-Folded-Header: \r\n   \r\n hello there\r\n\r\n")

    @Test fun test_forbid_response_with_obsolete_line_folding_at_start() {
        val res = parseRes(responseWithObsoleteLineFoldingAtStart, capacity = 1)
        assertStatus(err(HttpParseError.HeaderName), res.status)
    }

    @Test fun test_allow_response_with_obsolete_line_folding_at_start() {
        val buf = responseWithObsoleteLineFoldingAtStart
        val res = parseRes(buf, ParserConfig(allowObsoleteMultilineHeadersInResponses = true), capacity = 1)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Line-Folded-Header", "hello there")
    }

    private val responseWithObsoleteLineFoldingAtEnd =
        b("HTTP/1.1 200 OK\r\nLine-Folded-Header: hello there\r\n   \r\n \r\n\r\n")

    @Test fun test_forbid_response_with_obsolete_line_folding_at_end() {
        val res = parseRes(responseWithObsoleteLineFoldingAtEnd, capacity = 1)
        assertStatus(err(HttpParseError.HeaderName), res.status)
    }

    @Test fun test_allow_response_with_obsolete_line_folding_at_end() {
        val buf = responseWithObsoleteLineFoldingAtEnd
        val res = parseRes(buf, ParserConfig(allowObsoleteMultilineHeadersInResponses = true), capacity = 1)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Line-Folded-Header", "hello there")
    }

    private val responseWithObsoleteLineFoldingInMiddle =
        b("HTTP/1.1 200 OK\r\nLine-Folded-Header: hello  \r\n \r\n there\r\n\r\n")

    @Test fun test_forbid_response_with_obsolete_line_folding_in_middle() {
        val res = parseRes(responseWithObsoleteLineFoldingInMiddle, capacity = 1)
        assertStatus(err(HttpParseError.HeaderName), res.status)
    }

    @Test fun test_allow_response_with_obsolete_line_folding_in_middle() {
        val buf = responseWithObsoleteLineFoldingInMiddle
        val res = parseRes(buf, ParserConfig(allowObsoleteMultilineHeadersInResponses = true), capacity = 1)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Line-Folded-Header", "hello  \r\n \r\n there")
    }

    private val responseWithObsoleteLineFoldingInEmptyHeader =
        b("HTTP/1.1 200 OK\r\nLine-Folded-Header:   \r\n \r\n \r\n\r\n")

    @Test fun test_forbid_response_with_obsolete_line_folding_in_empty_header() {
        val res = parseRes(responseWithObsoleteLineFoldingInEmptyHeader, capacity = 1)
        assertStatus(err(HttpParseError.HeaderName), res.status)
    }

    @Test fun test_allow_response_with_obsolete_line_folding_in_empty_header() {
        val buf = responseWithObsoleteLineFoldingInEmptyHeader
        val res = parseRes(buf, ParserConfig(allowObsoleteMultilineHeadersInResponses = true), capacity = 1)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Line-Folded-Header", "")
    }

    private fun chunk(s: String): Pair<String, ULong?> {
        val out = ChunkSize()
        val st = parseChunkSize(b(s), out = out)
        return ParseStatus.describe(st) to (if (st >= 0) out.size else null)
    }

    private fun chunkOk(n: Int, size: ULong): Pair<String, ULong?> = "Complete($n)" to size
    private val chunkPartial: Pair<String, ULong?> = "Partial" to null
    private val chunkInvalid: Pair<String, ULong?> = "Err(InvalidChunkSize)" to null

    @Test fun test_chunk_size() {
        assertEquals(chunkOk(3, 0u), chunk("0\r\n"))
        assertEquals(chunkOk(4, 18u), chunk("12\r\nchunk"))
        assertEquals(chunkOk(7, 198765u), chunk("3086d\r\n"))
        assertEquals(chunkOk(18, 57891505u), chunk("3735AB1;foo bar*\r\n"))
        assertEquals(chunkOk(16, 57891505u), chunk("3735ab1 ; baz \r\n"))
        assertEquals(chunkPartial, chunk("77a65\r"))
        assertEquals(chunkPartial, chunk("ab"))
        assertEquals(chunkInvalid, chunk("567f8a\rfoo"))
        assertEquals(chunkInvalid, chunk("567f8a\rfoo"))
        assertEquals(chunkInvalid, chunk("567xf8a\r\n"))
        assertEquals(chunkOk(18, ULong.MAX_VALUE), chunk("ffffffffffffffff\r\n"))
        assertEquals(chunkInvalid, chunk("1ffffffffffffffff\r\n"))
        assertEquals(chunkInvalid, chunk("Affffffffffffffff\r\n"))
        assertEquals(chunkInvalid, chunk("fffffffffffffffff\r\n"))
    }

    private val responseWithMultipleSpaceDelimiters = b("HTTP/1.1   200  OK\r\n\r\n")

    @Test fun test_forbid_response_with_multiple_space_delimiters() {
        assertStatus(err(HttpParseError.Status), parseRes(responseWithMultipleSpaceDelimiters).status)
    }

    @Test fun test_allow_response_with_multiple_space_delimiters() {
        val buf = responseWithMultipleSpaceDelimiters
        val res = parseRes(buf, ParserConfig(allowMultipleSpacesInResponseStatusDelimiters = true))
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(0, res.headerCount)
    }

    /** Technically allowed by the spec, but only multiple spaces are supported as an option, not stray `\r`s. */
    private val responseWithWeirdWhitespaceDelimiters = b("HTTP/1.1 200\rOK\r\n\r\n")

    @Test fun test_forbid_response_with_weird_whitespace_delimiters() {
        assertStatus(err(HttpParseError.Status), parseRes(responseWithWeirdWhitespaceDelimiters).status)
    }

    @Test fun test_still_forbid_response_with_weird_whitespace_delimiters() {
        val res = parseRes(responseWithWeirdWhitespaceDelimiters, ParserConfig(allowMultipleSpacesInResponseStatusDelimiters = true))
        assertStatus(err(HttpParseError.Status), res.status)
    }

    private val requestWithMultipleSpaceDelimiters = b("GET  /    HTTP/1.1\r\n\r\n")

    @Test fun test_forbid_request_with_multiple_space_delimiters() {
        assertStatus(err(HttpParseError.Token), parseReq(requestWithMultipleSpaceDelimiters).status)
    }

    @Test fun test_allow_request_with_multiple_space_delimiters() {
        val buf = requestWithMultipleSpaceDelimiters
        val req = parseReq(buf, ParserConfig(allowMultipleSpacesInRequestLineDelimiters = true))
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        assertEquals(0, req.headerCount)
    }

    /** Technically allowed by the spec, but only multiple spaces are supported as an option, not stray `\r`s. */
    private val requestWithWeirdWhitespaceDelimiters = b("GET\r/\rHTTP/1.1\r\n\r\n")

    @Test fun test_forbid_request_with_weird_whitespace_delimiters() {
        assertStatus(err(HttpParseError.Token), parseReq(requestWithWeirdWhitespaceDelimiters).status)
    }

    @Test fun test_still_forbid_request_with_weird_whitespace_delimiters() {
        val req = parseReq(requestWithWeirdWhitespaceDelimiters, ParserConfig(allowMultipleSpacesInRequestLineDelimiters = true))
        assertStatus(err(HttpParseError.Token), req.status)
    }

    @Test fun test_request_with_multiple_spaces_and_bad_path() {
        val req = parseReq(b("GET   /foo ohno HTTP/1.1\r\n\r\n"), ParserConfig(allowMultipleSpacesInRequestLineDelimiters = true))
        assertStatus(err(HttpParseError.Version), req.status)
    }

    // DEL is not allowed in the path (every byte from 0x21 except DEL is).
    @Test fun test_request_with_del_in_path() {
        val req = parseReq(b("GET   /foo\u007Fohno HTTP/1.1\r\n\r\n"), ParserConfig(allowMultipleSpacesInRequestLineDelimiters = true))
        assertStatus(err(HttpParseError.Token), req.status)
    }

    /**
     * Every 2-byte, 3-byte (lead >= 0xE0) and 4-byte (lead >= 0xF0) sequence of bytes 0x80-0xFF as the path
     * after `/`: `Complete` exactly when the sequence is valid UTF-8, `Token` otherwise. The reference decides
     * validity with `core::str::from_utf8`; here [referenceUtf8] is an independent code-point based check.
     * About 34 million parses (the reference skips this test under Miri only).
     */
    @Test fun test_all_utf8_char_in_paths() {
        val config = ParserConfig(allowMultipleSpacesInRequestLineDelimiters = true)
        val r = ParsedRequest(HeaderSlots(NUM_OF_HEADERS))
        val tail = b(" HTTP/1.1\r\n\r\n")
        fun line(n: Int): ByteArray = b("GET /") + ByteArray(n) + tail
        val l2 = line(2)
        val l3 = line(3)
        val l4 = line(4)
        val p = 5
        for (i in 128 until 256) {
            for (j in 128 until 256) {
                l2[p] = i.toByte(); l2[p + 1] = j.toByte()
                val want2 = if (referenceUtf8(l2, p, 2)) complete(20) else err(HttpParseError.Token)
                val got2 = r.parse(l2, 0, l2.size, config)
                if (got2 != want2) assertStatus(want2, got2, "failed for utf8 char i: $i, j: $j")

                // Three code points starting from 0xe0.
                if (i < 0xe0) continue
                for (k in 128 until 256) {
                    l3[p] = i.toByte(); l3[p + 1] = j.toByte(); l3[p + 2] = k.toByte()
                    val want3 = if (referenceUtf8(l3, p, 3)) complete(21) else err(HttpParseError.Token)
                    val got3 = r.parse(l3, 0, l3.size, config)
                    if (got3 != want3) assertStatus(want3, got3, "failed for utf8 char i: $i, j: $j, k: $k")

                    // Four code points starting from 0xf0.
                    if (i < 0xf0) continue
                    l4[p] = i.toByte(); l4[p + 1] = j.toByte(); l4[p + 2] = k.toByte()
                    for (l in 128 until 256) {
                        l4[p + 3] = l.toByte()
                        val want4 = if (referenceUtf8(l4, p, 4)) complete(22) else err(HttpParseError.Token)
                        val got4 = r.parse(l4, 0, l4.size, config)
                        if (got4 != want4) assertStatus(want4, got4, "failed for utf8 char i: $i, j: $j, k: $k, l: $l")
                    }
                }
            }
        }
    }

    @Test fun test_response_with_spaces_in_code() {
        val res = parseRes(b("HTTP/1.1 99 200 OK\r\n\r\n"), ParserConfig(allowMultipleSpacesInResponseStatusDelimiters = true))
        assertStatus(err(HttpParseError.Status), res.status)
    }

    @Test fun test_response_with_empty_header_name() {
        val buf = b("HTTP/1.1 200 OK\r\n: hello\r\nBread: baguette\r\n\r\n")

        val first = parseRes(buf, ParserConfig(allowSpacesAfterHeaderNameInResponses = true), capacity = 2)
        assertStatus(err(HttpParseError.HeaderName), first.status)

        val res = parseRes(buf, ParserConfig(ignoreInvalidHeadersInResponses = true), capacity = 2)
        assertStatus(complete(45), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Bread", "baguette")
    }

    @Test fun test_request_with_empty_header_name() {
        val buf = b("GET / HTTP/1.1\r\n: hello\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderName), parseReq(buf, default, capacity = 2).status)
        assertStatus(complete(44), parseReq(buf, ParserConfig(ignoreInvalidHeadersInRequests = true), capacity = 2).status)
    }

    @Test fun test_request_with_whitespace_between_header_name_and_colon() {
        val buf = b("GET / HTTP/1.1\r\nAccess-Control-Allow-Credentials  : true\r\nBread: baguette\r\n\r\n")
        // The response-only switches do not apply to requests.
        assertStatus(
            err(HttpParseError.HeaderName),
            parseReq(buf, ParserConfig(allowSpacesAfterHeaderNameInResponses = true), capacity = 2).status,
        )
        assertStatus(
            err(HttpParseError.HeaderName),
            parseReq(buf, ParserConfig(ignoreInvalidHeadersInResponses = true), capacity = 2).status,
        )
    }

    @Test fun test_response_with_invalid_char_between_header_name_and_colon() {
        val buf = b("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Credentials\u00FF  : true\r\nBread: baguette\r\n\r\n")
        assertStatus(
            err(HttpParseError.HeaderName),
            parseRes(buf, ParserConfig(allowSpacesAfterHeaderNameInResponses = true), capacity = 2).status,
        )

        val res = parseRes(buf, ParserConfig(ignoreInvalidHeadersInResponses = true), capacity = 2)
        assertStatus(complete(79), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Bread", "baguette")
    }

    @Test fun test_request_with_invalid_char_between_header_name_and_colon() {
        val buf = b("GET / HTTP/1.1\r\nAccess-Control-Allow-Credentials\u00FF  : true\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderName), parseReq(buf, default, capacity = 2).status)
        assertStatus(complete(78), parseReq(buf, ParserConfig(ignoreInvalidHeadersInRequests = true), capacity = 2).status)
    }

    @Test fun test_ignore_header_line_with_missing_colon_in_response() {
        val buf = b("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Credentials\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderName), parseRes(buf, default, capacity = 2).status)

        val res = parseRes(buf, ParserConfig(ignoreInvalidHeadersInResponses = true), capacity = 2)
        assertStatus(complete(70), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Bread", "baguette")
    }

    @Test fun test_ignore_header_line_with_missing_colon_in_request() {
        val buf = b("GET / HTTP/1.1\r\nAccess-Control-Allow-Credentials\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderName), parseReq(buf, default, capacity = 2).status)
        assertStatus(complete(69), parseReq(buf, ParserConfig(ignoreInvalidHeadersInRequests = true), capacity = 2).status)
    }

    @Test fun test_response_header_with_missing_colon_with_folding() {
        val buf = b("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Credentials   \r\n hello\r\nBread: baguette\r\n\r\n")
        assertStatus(
            err(HttpParseError.HeaderName),
            parseRes(
                buf,
                ParserConfig(allowObsoleteMultilineHeadersInResponses = true, allowSpacesAfterHeaderNameInResponses = true),
                capacity = 2,
            ).status,
        )

        val res = parseRes(buf, ParserConfig(ignoreInvalidHeadersInResponses = true), capacity = 2)
        assertStatus(complete(81), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Bread", "baguette")
    }

    @Test fun test_request_header_with_missing_colon_with_folding() {
        val buf = b("GET / HTTP/1.1\r\nAccess-Control-Allow-Credentials   \r\n hello\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderName), parseReq(buf, default, capacity = 2).status)
        assertStatus(complete(80), parseReq(buf, ParserConfig(ignoreInvalidHeadersInRequests = true), capacity = 2).status)
    }

    @Test fun test_response_header_with_nul_in_header_name() {
        val buf = b("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Cred\u0000entials: hello\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderName), parseRes(buf, default, capacity = 2).status)
        assertStatus(
            err(HttpParseError.HeaderName),
            parseRes(buf, ParserConfig(ignoreInvalidHeadersInResponses = true), capacity = 2).status,
        )
    }

    @Test fun test_request_header_with_nul_in_header_name() {
        val buf = b("GET / HTTP/1.1\r\nAccess-Control-Allow-Cred\u0000entials: hello\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderName), parseReq(buf, default, capacity = 2).status)
        assertStatus(
            err(HttpParseError.HeaderName),
            parseReq(buf, ParserConfig(ignoreInvalidHeadersInRequests = true), capacity = 2).status,
        )
    }

    @Test fun test_header_with_cr_in_header_name() {
        val response = b("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Cred\rentials: hello\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderName), parseRes(response, default, capacity = 2).status)
        assertStatus(
            err(HttpParseError.HeaderName),
            parseRes(response, ParserConfig(ignoreInvalidHeadersInResponses = true), capacity = 2).status,
        )

        val request = b("GET / HTTP/1.1\r\nAccess-Control-Allow-Cred\rentials: hello\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderName), parseReq(request, default, capacity = 2).status)
        assertStatus(
            err(HttpParseError.HeaderName),
            parseReq(request, ParserConfig(ignoreInvalidHeadersInRequests = true), capacity = 2).status,
        )
    }

    @Test fun test_header_with_nul_in_whitespace_before_colon() {
        val response = b("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Credentials   \u0000: hello\r\nBread: baguette\r\n\r\n")
        assertStatus(
            err(HttpParseError.HeaderName),
            parseRes(response, ParserConfig(allowSpacesAfterHeaderNameInResponses = true), capacity = 2).status,
        )
        assertStatus(
            err(HttpParseError.HeaderName),
            parseRes(
                response,
                ParserConfig(allowSpacesAfterHeaderNameInResponses = true, ignoreInvalidHeadersInResponses = true),
                capacity = 2,
            ).status,
        )

        val request = b("GET / HTTP/1.1\r\nAccess-Control-Allow-Credentials   \u0000: hello\r\nBread: baguette\r\n\r\n")
        assertStatus(
            err(HttpParseError.HeaderName),
            parseReq(request, ParserConfig(ignoreInvalidHeadersInRequests = true), capacity = 2).status,
        )
    }

    @Test fun test_header_with_nul_in_value() {
        val response = b("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Credentials: hell\u0000o\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderValue), parseRes(response, default, capacity = 2).status)
        assertStatus(
            err(HttpParseError.HeaderValue),
            parseRes(response, ParserConfig(ignoreInvalidHeadersInResponses = true), capacity = 2).status,
        )

        val request = b("GET / HTTP/1.1\r\nAccess-Control-Allow-Credentials: hell\u0000o\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderValue), parseReq(request, default, capacity = 2).status)
        assertStatus(
            err(HttpParseError.HeaderValue),
            parseReq(request, ParserConfig(ignoreInvalidHeadersInRequests = true), capacity = 2).status,
        )
    }

    @Test fun test_header_with_invalid_char_in_value() {
        val response = b("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Credentials: hell\u0001o\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderValue), parseRes(response, default, capacity = 2).status)

        val res = parseRes(response, ParserConfig(ignoreInvalidHeadersInResponses = true), capacity = 2)
        assertStatus(complete(78), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Bread", "baguette")

        val request = b("GET / HTTP/1.1\r\nAccess-Control-Allow-Credentials: hell\u0001o\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderValue), parseReq(request, default, capacity = 2).status)

        val req = parseReq(request, ParserConfig(ignoreInvalidHeadersInRequests = true), capacity = 2)
        assertStatus(complete(77), req.status)
        assertEquals(1, req.version)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.headerCount)
        req.assertHeader(0, "Bread", "baguette")
    }

    /**
     * The reference input contains a bare `LF` (`hell\x01o  \n world!`), so the reference expectations are
     * checked with `allowBareLf = true`; by default the bare `LF` met while skipping the invalid line is itself
     * a `HeaderValue` error.
     */
    @Test fun test_header_with_invalid_char_in_value_with_folding() {
        val response = b("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Credentials: hell\u0001o  \n world!\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderValue), parseRes(response, BARE_LF, capacity = 2).status)

        val res = parseRes(response, ParserConfig(ignoreInvalidHeadersInResponses = true, allowBareLf = true), capacity = 2)
        assertStatus(complete(88), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Bread", "baguette")
        assertStatus(
            err(HttpParseError.HeaderValue),
            parseRes(response, ParserConfig(ignoreInvalidHeadersInResponses = true), capacity = 2).status,
        )

        val request = b("GET / HTTP/1.1\r\nAccess-Control-Allow-Credentials: hell\u0001o  \n world!\r\nBread: baguette\r\n\r\n")
        assertStatus(err(HttpParseError.HeaderValue), parseReq(request, BARE_LF, capacity = 2).status)

        val req = parseReq(request, ParserConfig(ignoreInvalidHeadersInRequests = true, allowBareLf = true), capacity = 2)
        assertStatus(complete(87), req.status)
        assertEquals(1, req.version)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.headerCount)
        req.assertHeader(0, "Bread", "baguette")
        assertStatus(
            err(HttpParseError.HeaderValue),
            parseReq(request, ParserConfig(ignoreInvalidHeadersInRequests = true), capacity = 2).status,
        )
    }

    @Test fun test_method_within_buffer() {
        val request = b("GET / HTTP/1.1\r\n\r\n")
        val r = ParsedRequest(HeaderSlots(0))
        ParseStatus.completeOrThrow(default.parseRequest(r, request))
        // The method lies within the buffer.
        assertAllInBuffer(intArrayOf(r.methodStart, r.methodEnd), request.size)
        assertTrue(r.methodStart <= r.methodEnd)
    }

    private val responseWithSpaceBeforeFirstHeader = b("HTTP/1.1 200 OK\r\n Space-Before-Header: hello there\r\n\r\n")

    @Test fun test_forbid_response_with_space_before_first_header() {
        assertStatus(err(HttpParseError.HeaderName), parseRes(responseWithSpaceBeforeFirstHeader, capacity = 1).status)
    }

    @Test fun test_allow_response_response_with_space_before_first_header() {
        val buf = responseWithSpaceBeforeFirstHeader
        val res = parseRes(buf, ParserConfig(allowSpaceBeforeFirstHeaderName = true), capacity = 1)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Space-Before-Header", "hello there")
    }

    @Test fun test_no_space_after_colon() {
        val res = parseRes(b("HTTP/1.1 200 OK\r\nfoo:bar\r\n\r\n"), default, capacity = 1)
        assertStatus(complete(28), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "foo", "bar")
    }

    @Test fun test_request_with_leading_space() {
        assertStatus(err(HttpParseError.Token), parseReq(b(" GET / HTTP/1.1\r\nfoo:bar\r\n\r\n"), default, capacity = 1).status)
    }

    @Test fun test_request_with_invalid_method() {
        assertStatus(err(HttpParseError.Token), parseReq(b("P()ST / HTTP/1.1\r\nfoo:bar\r\n\r\n"), default, capacity = 1).status)
    }

    @Test fun test_utf8_in_path_ok() {
        val req = parseReq(
            b("GET /test?post=I\u00E2\u0080\u0099msorryIforkedyou HTTP/1.1\r\nHost: example.org\r\n\r\n"), default, capacity = 1,
        )
        assertStatus(complete(67), req.status)
        assertEquals(1, req.version)
        assertEquals("GET", req.method)
        assertEquals("/test?post=I\u2019msorryIforkedyou", req.path)
        assertEquals(1, req.headerCount)
        req.assertHeader(0, "Host", "example.org")
    }

    @Test fun test_bad_utf8_in_path() {
        val req = parseReq(b("GET /test?post=I\u00E2msorryIforkedyou HTTP/1.1\r\nHost: example.org\r\n\r\n"), default, capacity = 1)
        assertStatus(err(HttpParseError.Token), req.status)
    }

    // ---- doc examples ----------------------------------------------------------------------------------------

    /** `parse_headers` doc example (bare `LF` input, so `allowBareLf = true`). */
    @Test fun doc_parse_headers() {
        val buf = b("Host: foo.bar\nAccept: */*\n\nblah blah")
        val slots = HeaderSlots(4)
        assertStatus(complete(27), parseHeaders(buf, headers = slots, allowBareLf = true))
        assertEquals(2, slots.count)
        assertHeader("Host", "foo.bar", slots.name(0, buf), slots.value(0, buf))
        assertHeader("Accept", "*/*", slots.name(1, buf), slots.value(1, buf))
        // Default: the bare LF is rejected.
        assertStatus(err(HttpParseError.HeaderValue), parseHeaders(buf, headers = HeaderSlots(4)))
        // The same with CRLF.
        val crlf = b("Host: foo.bar\r\nAccept: */*\r\n\r\nblah blah")
        assertStatus(complete(30), parseHeaders(crlf, headers = slots))
        assertEquals(2, slots.count)
    }

    /** `parse_chunk_size` doc example. */
    @Test fun doc_parse_chunk_size() {
        assertEquals(chunkOk(3, 4u), chunk("4\r\nRust\r\n0\r\n\r\n"))
    }

    /** `ParserConfig::allow_obsolete_multiline_headers_in_responses` doc example. */
    @Test fun doc_allow_obsolete_multiline_headers_in_responses() {
        val buf = b("HTTP/1.1 200 OK\r\nFolded-Header: hello\r\n there \r\n\r\n")
        val res = parseRes(buf, ParserConfig(allowObsoleteMultilineHeadersInResponses = true), capacity = 16)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Folded-Header", "hello\r\n there")
    }

    /** `ParserConfig::allow_space_before_first_header_name` doc example. */
    @Test fun doc_allow_space_before_first_header_name() {
        val buf = b("HTTP/1.1 200 OK\r\n Space-Before-Header: hello there\r\n\r\n")
        val res = parseRes(buf, ParserConfig(allowSpaceBeforeFirstHeaderName = true), capacity = 1)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Space-Before-Header", "hello there")
    }

    /** `Request` doc example: inspect the path of a partial parse. */
    @Test fun doc_request_partial_path() {
        val req = parseReq(b("GET /404 HTTP/1.1\r\nHost:"), capacity = 16)
        assertStatus(PARTIAL, req.status)
        assertEquals("/404", req.path)
    }
}

/**
 * Independent UTF-8 validity check for `test_all_utf8_char_in_paths`: decodes the code point from the lead
 * byte's length class and rejects overlong forms, surrogates and values above U+10FFFF by value.
 */
internal fun referenceUtf8(buf: ByteArray, start: Int, len: Int): Boolean {
    var i = start
    val end = start + len
    while (i < end) {
        val lead = buf[i].toInt() and 0xFF
        val n: Int
        var cp: Int
        when {
            lead < 0x80 -> { i++; continue }
            lead and 0xE0 == 0xC0 -> { n = 2; cp = lead and 0x1F }
            lead and 0xF0 == 0xE0 -> { n = 3; cp = lead and 0x0F }
            lead and 0xF8 == 0xF0 -> { n = 4; cp = lead and 0x07 }
            else -> return false
        }
        if (end - i < n) return false
        for (k in 1 until n) {
            val c = buf[i + k].toInt() and 0xFF
            if (c and 0xC0 != 0x80) return false
            cp = (cp shl 6) or (c and 0x3F)
        }
        val min = when (n) { 2 -> 0x80; 3 -> 0x800; else -> 0x10000 }
        if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return false
        i += n
    }
    return true
}
