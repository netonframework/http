package neton.http.h1.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The table-driven cases of httparse 1.10.1 `src/lib.rs`: all 42 macro uses (29 `req!` + 13 `res!`; a plain
 * `grep 'req! {'` finds 44 lines because the two macro definitions also call themselves), one test each, with
 * the reference names, inputs and expectations. A case without an explicit expectation expects
 * `Complete(input.length)`.
 *
 * Cases whose input uses bare `LF` line endings are parsed with `allowBareLf = true` (reproducing httparse);
 * each of them also asserts the default (bare `LF` rejected) outcome.
 *
 * Reference `headers.len()` after `Partial`: httparse puts the caller's full slice back, so its length is
 * `NUM_OF_HEADERS`; here [HeaderSlots.count] keeps its value from before the call (0 for a fresh slot array)
 * while the slots hold what was parsed, as in the reference.
 */
class HttpParseTableTest {
    // ---- req! ----------------------------------------------------------------------------------------------

    @Test fun test_request_simple() {
        val buf = b("GET / HTTP/1.1\r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        assertEquals(0, req.headerCount)
    }

    @Test fun test_request_simple_with_query_params() {
        val buf = b("GET /thing?data=a HTTP/1.1\r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/thing?data=a", req.path)
        assertEquals(1, req.version)
        assertEquals(0, req.headerCount)
    }

    @Test fun test_request_simple_with_whatwg_query_params() {
        val buf = b("GET /thing?data=a^ HTTP/1.1\r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/thing?data=a^", req.path)
        assertEquals(1, req.version)
        assertEquals(0, req.headerCount)
    }

    @Test fun test_request_headers() {
        val buf = b("GET / HTTP/1.1\r\nHost: foo.com\r\nCookie: \r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        assertEquals(2, req.headerCount)
        req.assertHeader(0, "Host", "foo.com")
        req.assertHeader(1, "Cookie", "")
    }

    @Test fun test_request_headers_optional_whitespace() {
        val buf = b("GET / HTTP/1.1\r\nHost: \tfoo.com\t \r\nCookie: \t \r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        assertEquals(2, req.headerCount)
        req.assertHeader(0, "Host", "foo.com")
        req.assertHeader(1, "Cookie", "")
    }

    // Reference: "test the scalar parsing".
    @Test fun test_request_header_value_htab_short() {
        val buf = b("GET / HTTP/1.1\r\nUser-Agent: some\tagent\r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        assertEquals(1, req.headerCount)
        req.assertHeader(0, "User-Agent", "some\tagent")
    }

    // Reference: "test the sse42 parsing".
    @Test fun test_request_header_value_htab_med() {
        val buf = b("GET / HTTP/1.1\r\nUser-Agent: 1234567890some\tagent\r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        assertEquals(1, req.headerCount)
        req.assertHeader(0, "User-Agent", "1234567890some\tagent")
    }

    // Reference: "test the avx2 parsing".
    @Test fun test_request_header_value_htab_long() {
        val buf = b("GET / HTTP/1.1\r\nUser-Agent: 1234567890some\t1234567890agent1234567890\r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        assertEquals(1, req.headerCount)
        req.assertHeader(0, "User-Agent", "1234567890some\t1234567890agent1234567890")
    }

    // Reference: "test the avx2 parsing".
    @Test fun test_request_header_no_space_after_colon() {
        val buf = b("GET / HTTP/1.1\r\nUser-Agent:omg-no-space1234567890some1234567890agent1234567890\r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        assertEquals(1, req.headerCount)
        req.assertHeader(0, "User-Agent", "omg-no-space1234567890some1234567890agent1234567890")
    }

    @Test fun test_request_headers_max() {
        val buf = b("GET / HTTP/1.1\r\nA: A\r\nB: B\r\nC: C\r\nD: D\r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals(NUM_OF_HEADERS, req.headerCount)
    }

    @Test fun test_request_multibyte() {
        val buf = b("GET / HTTP/1.1\r\nHost: foo.com\r\nUser-Agent: \u00E3\u0081\u00B2\u00E3/1.0\r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        assertEquals(2, req.headerCount)
        req.assertHeader(0, "Host", "foo.com")
        req.assertHeader(1, "User-Agent", "\u00E3\u0081\u00B2\u00E3/1.0")
    }

    // A single byte which is part of a method is not invalid.
    @Test fun test_request_one_byte_method() = assertStatus(PARTIAL, parseReq(b("G")).status)

    // A subset of a method is a partial method, not invalid.
    @Test fun test_request_partial_method() = assertStatus(PARTIAL, parseReq(b("GE")).status)

    // A method, without the delimiting space, is a partial request.
    @Test fun test_request_method_no_delimiter() = assertStatus(PARTIAL, parseReq(b("GET")).status)

    // Regression test: a partial read with just the method and space results in a partial, rather than a token
    // error from uri parsing.
    @Test fun test_request_method_only() = assertStatus(PARTIAL, parseReq(b("GET ")).status)

    @Test fun test_request_partial() = assertStatus(PARTIAL, parseReq(b("GET / HTTP/1.1\r\n\r")).status)

    @Test fun test_request_partial_version() = assertStatus(PARTIAL, parseReq(b("GET / HTTP/1.")).status)

    @Test fun test_request_method_path_no_delimiter() = assertStatus(PARTIAL, parseReq(b("GET /")).status)

    @Test fun test_request_method_path_only() = assertStatus(PARTIAL, parseReq(b("GET / ")).status)

    @Test fun test_request_partial_parses_headers_as_much_as_it_can() {
        val req = parseReq(b("GET / HTTP/1.1\r\nHost: yolo\r\n"))
        assertStatus(PARTIAL, req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        // Reference: headers.len() == NUM_OF_HEADERS ("doesn't slice since not Complete"); see the class doc.
        assertEquals(0, req.headerCount)
        assertEquals(NUM_OF_HEADERS, req.r.headers.capacity)
        req.assertHeader(0, "Host", "yolo")
    }

    @Test fun test_request_newlines() {
        val buf = b("GET / HTTP/1.1\nHost: foo.bar\n\n")
        assertStatus(complete(buf.size), parseReq(buf, BARE_LF).status)
        assertStatus(err(HttpParseError.NewLine), parseReq(buf).status)
    }

    @Test fun test_request_empty_lines_prefix() {
        val buf = b("\r\n\r\nGET / HTTP/1.1\r\n\r\n")
        val req = parseReq(buf)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        assertEquals(0, req.headerCount)
    }

    @Test fun test_request_empty_lines_prefix_lf_only() {
        val buf = b("\n\nGET / HTTP/1.1\n\n")
        val req = parseReq(buf, BARE_LF)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/", req.path)
        assertEquals(1, req.version)
        assertEquals(0, req.headerCount)
        assertStatus(err(HttpParseError.NewLine), parseReq(buf).status)
    }

    @Test fun test_request_path_backslash() {
        val buf = b("\n\nGET /\\?wayne\\=5 HTTP/1.1\n\n")
        val req = parseReq(buf, BARE_LF)
        assertStatus(complete(buf.size), req.status)
        assertEquals("GET", req.method)
        assertEquals("/\\?wayne\\=5", req.path)
        assertEquals(1, req.version)
        assertEquals(0, req.headerCount)
        assertStatus(err(HttpParseError.NewLine), parseReq(buf).status)
    }

    @Test fun test_request_with_invalid_token_delimiter() {
        val buf = b("GET\n/ HTTP/1.1\r\nHost: foo.bar\r\n\r\n")
        assertStatus(err(HttpParseError.Token), parseReq(buf).status)
        assertStatus(err(HttpParseError.Token), parseReq(buf, BARE_LF).status)
    }

    @Test fun test_request_with_invalid_but_short_version() =
        assertStatus(err(HttpParseError.Version), parseReq(b("GET / HTTP/1!")).status)

    @Test fun test_request_with_empty_method() =
        assertStatus(err(HttpParseError.Token), parseReq(b(" / HTTP/1.1\r\n\r\n")).status)

    @Test fun test_request_with_empty_path() =
        assertStatus(err(HttpParseError.Token), parseReq(b("GET  HTTP/1.1\r\n\r\n")).status)

    @Test fun test_request_with_empty_method_and_path() =
        assertStatus(err(HttpParseError.Token), parseReq(b("  HTTP/1.1\r\n\r\n")).status)

    // ---- res! ----------------------------------------------------------------------------------------------

    @Test fun test_response_simple() {
        val buf = b("HTTP/1.1 200 OK\r\n\r\n")
        val res = parseRes(buf)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
    }

    @Test fun test_response_newlines() {
        val buf = b("HTTP/1.0 403 Forbidden\nServer: foo.bar\n\n")
        assertStatus(complete(buf.size), parseRes(buf, BARE_LF).status)
        assertStatus(err(HttpParseError.Status), parseRes(buf).status)
    }

    @Test fun test_response_reason_missing() {
        val buf = b("HTTP/1.1 200 \r\n\r\n")
        val res = parseRes(buf)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("", res.reason)
    }

    @Test fun test_response_reason_missing_no_space() {
        val buf = b("HTTP/1.1 200\r\n\r\n")
        val res = parseRes(buf)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("", res.reason)
    }

    @Test fun test_response_reason_missing_no_space_with_headers() {
        val buf = b("HTTP/1.1 200\r\nFoo: bar\r\n\r\n")
        val res = parseRes(buf)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Foo", "bar")
    }

    @Test fun test_response_reason_with_space_and_tab() {
        val buf = b("HTTP/1.1 101 Switching Protocols\t\r\n\r\n")
        val res = parseRes(buf)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(101, res.code)
        assertEquals("Switching Protocols\t", res.reason)
    }

    @Test fun test_response_reason_with_obsolete_text_byte() {
        val buf = b("HTTP/1.1 200 X\u00FFZ\r\n\r\n") // RESPONSE_REASON_WITH_OBS_TEXT_BYTE
        val res = parseRes(buf)
        assertStatus(complete(buf.size), res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        // Empty string fallback in case of obs-text.
        assertEquals("", res.reason)
    }

    @Test fun test_response_reason_with_nul_byte() =
        assertStatus(err(HttpParseError.Status), parseRes(b("HTTP/1.1 200 \u0000\r\n\r\n")).status)

    @Test fun test_response_version_missing_space() = assertStatus(PARTIAL, parseRes(b("HTTP/1.1")).status)

    @Test fun test_response_code_missing_space() = assertStatus(PARTIAL, parseRes(b("HTTP/1.1 200")).status)

    @Test fun test_response_partial_parses_headers_as_much_as_it_can() {
        val res = parseRes(b("HTTP/1.1 200 OK\r\nServer: yolo\r\n"))
        assertStatus(PARTIAL, res.status)
        assertEquals(1, res.version)
        assertEquals(200, res.code)
        assertEquals("OK", res.reason)
        // Reference: headers.len() == NUM_OF_HEADERS ("doesn't slice since not Complete"); see the class doc.
        assertEquals(0, res.headerCount)
        assertEquals(NUM_OF_HEADERS, res.r.headers.capacity)
        res.assertHeader(0, "Server", "yolo")
    }

    @Test fun test_response_empty_lines_prefix_lf_only() {
        val buf = b("\n\nHTTP/1.1 200 OK\n\n")
        assertStatus(complete(buf.size), parseRes(buf, BARE_LF).status)
        assertStatus(err(HttpParseError.NewLine), parseRes(buf).status)
    }

    @Test fun test_response_no_cr() {
        val buf = b("HTTP/1.0 200\nContent-type: text/html\n\n")
        val res = parseRes(buf, BARE_LF)
        assertStatus(complete(buf.size), res.status)
        assertEquals(0, res.version)
        assertEquals(200, res.code)
        assertEquals("", res.reason)
        assertEquals(1, res.headerCount)
        res.assertHeader(0, "Content-type", "text/html")
        val strict = parseRes(buf)
        assertStatus(err(HttpParseError.Status), strict.status)
        assertNull(strict.reason)
    }
}
