package neton.http.h1.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The documented deviation from httparse (SPEC §3.1, §3.9): a bare `LF` line ending is rejected by default at
 * every line-ending position; `allowBareLf = true` accepts exactly what httparse accepts. A lone `CR` is an
 * error either way.
 */
class BareLfTest {
    private class Case(
        val input: String,
        val response: Boolean,
        /** Status with the default configuration. */
        val strict: Int,
        /** Status with `allowBareLf = true` (the httparse result). */
        val lenient: Int,
        val config: ParserConfig = ParserConfig.DEFAULT,
    )

    private fun parse(c: Case, cfg: ParserConfig): Int =
        if (c.response) parseRes(b(c.input), cfg).status else parseReq(b(c.input), cfg).status

    private fun run(cases: List<Case>) {
        for (c in cases) {
            val shown = c.input.replace("\r", "\\r").replace("\n", "\\n")
            assertStatus(c.strict, parse(c, c.config), "default: $shown")
            assertStatus(c.lenient, parse(c, c.config.copy(allowBareLf = true)), "allowBareLf: $shown")
        }
    }

    private fun full(s: String) = complete(s.length)

    @Test fun empty_lines_before_the_message() = run(
        listOf(
            "\nGET / HTTP/1.1\r\n\r\n".let { Case(it, false, err(HttpParseError.NewLine), full(it)) },
            "\r\n\nGET / HTTP/1.1\r\n\r\n".let { Case(it, false, err(HttpParseError.NewLine), full(it)) },
            "\nHTTP/1.1 200 OK\r\n\r\n".let { Case(it, true, err(HttpParseError.NewLine), full(it)) },
            "\r\n\nHTTP/1.1 200 OK\r\n\r\n".let { Case(it, true, err(HttpParseError.NewLine), full(it)) },
        ),
    )

    @Test fun request_line() = run(
        listOf(
            "GET / HTTP/1.1\n\r\n".let { Case(it, false, err(HttpParseError.NewLine), full(it)) },
            "GET / HTTP/1.1\nHost: a\r\n\r\n".let { Case(it, false, err(HttpParseError.NewLine), full(it)) },
            // Partial input stops before the line end: the same either way.
            Case("GET / HTTP/1.1", false, PARTIAL, PARTIAL),
        ),
    )

    @Test fun status_line() = run(
        listOf(
            "HTTP/1.1 200 OK\n\r\n".let { Case(it, true, err(HttpParseError.Status), full(it)) },
            "HTTP/1.1 200 \n\r\n".let { Case(it, true, err(HttpParseError.Status), full(it)) },
            "HTTP/1.1 200\n\r\n".let { Case(it, true, err(HttpParseError.Status), full(it)) },
        ),
    )

    @Test fun header_lines() = run(
        listOf(
            "GET / HTTP/1.1\r\nHost: a\n\r\n".let { Case(it, false, err(HttpParseError.HeaderValue), full(it)) },
            "GET / HTTP/1.1\r\nHost:\n\r\n".let { Case(it, false, err(HttpParseError.HeaderValue), full(it)) },
            "GET / HTTP/1.1\r\nHost: \t\n\r\n".let { Case(it, false, err(HttpParseError.HeaderValue), full(it)) },
            "GET / HTTP/1.1\r\nA: 1\r\nB: 2\n\r\n".let { Case(it, false, err(HttpParseError.HeaderValue), full(it)) },
            "HTTP/1.1 200 OK\r\nA: 1\n\r\n".let { Case(it, true, err(HttpParseError.HeaderValue), full(it)) },
            "HTTP/1.1 200 OK\r\nA: 1\r\nB:\n\r\n".let { Case(it, true, err(HttpParseError.HeaderValue), full(it)) },
            // A header name ended by a bare LF is a HeaderName error either way (no colon).
            Case("GET / HTTP/1.1\r\nHost\n\r\n", false, err(HttpParseError.HeaderName), err(HttpParseError.HeaderName)),
        ),
    )

    @Test fun final_empty_line() = run(
        listOf(
            "GET / HTTP/1.1\r\n\n".let { Case(it, false, err(HttpParseError.NewLine), full(it)) },
            "GET / HTTP/1.1\r\nHost: a\r\n\n".let { Case(it, false, err(HttpParseError.NewLine), full(it)) },
            "HTTP/1.1 200 OK\r\n\n".let { Case(it, true, err(HttpParseError.NewLine), full(it)) },
            "HTTP/1.1 200 OK\r\nA: 1\r\n\n".let { Case(it, true, err(HttpParseError.NewLine), full(it)) },
        ),
    )

    @Test fun obsolete_line_folding_and_ignored_lines() {
        val fold = ParserConfig(allowObsoleteMultilineHeadersInResponses = true)
        val ignoreRes = ParserConfig(ignoreInvalidHeadersInResponses = true)
        val ignoreReq = ParserConfig(ignoreInvalidHeadersInRequests = true)
        val spaceFirst = ParserConfig(allowSpaceBeforeFirstHeaderName = true)
        run(
            listOf(
                "HTTP/1.1 200 OK\r\nA: x\n y\r\n\r\n".let { Case(it, true, err(HttpParseError.HeaderValue), full(it), fold) },
                "HTTP/1.1 200 OK\r\nA:\n y\r\n\r\n".let { Case(it, true, err(HttpParseError.HeaderValue), full(it), fold) },
                // An invalid line skipped up to a bare LF.
                "HTTP/1.1 200 OK\r\nBad Name: x\nA: 1\r\n\r\n".let { Case(it, true, err(HttpParseError.HeaderName), full(it), ignoreRes) },
                "GET / HTTP/1.1\r\nA: \u0001x\nB: 1\r\n\r\n".let { Case(it, false, err(HttpParseError.HeaderValue), full(it), ignoreReq) },
                "HTTP/1.1 200 OK\r\n A: 1\n\r\n".let { Case(it, true, err(HttpParseError.HeaderValue), full(it), spaceFirst) },
            ),
        )
        val res = parseRes(b("HTTP/1.1 200 OK\r\nA: x\n y\r\n\r\n"), fold.copy(allowBareLf = true))
        res.assertHeader(0, "A", "x\n y")
    }

    @Test fun parse_headers_trailers() {
        val lf = b("A: 1\nB: 2\n\n")
        assertStatus(err(HttpParseError.HeaderValue), parseHeaders(lf, headers = HeaderSlots(4)))
        val slots = HeaderSlots(4)
        assertStatus(complete(lf.size), parseHeaders(lf, headers = slots, allowBareLf = true))
        assertEquals(2, slots.count)
        assertStatus(err(HttpParseError.NewLine), parseHeaders(b("\n"), headers = HeaderSlots(4)))
        assertStatus(complete(1), parseHeaders(b("\n"), headers = HeaderSlots(4), allowBareLf = true))
        assertStatus(complete(2), parseHeaders(b("\r\n"), headers = HeaderSlots(4)))
    }

    @Test fun lone_cr_is_always_an_error() = run(
        listOf(
            Case("\rGET / HTTP/1.1\r\n\r\n", false, err(HttpParseError.NewLine), err(HttpParseError.NewLine)),
            Case("GET / HTTP/1.1\rX\r\n\r\n", false, err(HttpParseError.NewLine), err(HttpParseError.NewLine)),
            Case("GET / HTTP/1.1\r\nHost: a\rb\r\n\r\n", false, err(HttpParseError.HeaderValue), err(HttpParseError.HeaderValue)),
            Case("GET / HTTP/1.1\r\nHost:\rb\r\n\r\n", false, err(HttpParseError.HeaderValue), err(HttpParseError.HeaderValue)),
            Case("GET / HTTP/1.1\r\n\rX", false, err(HttpParseError.NewLine), err(HttpParseError.NewLine)),
            Case("HTTP/1.1 200 OK\rX\r\n\r\n", true, err(HttpParseError.Status), err(HttpParseError.Status)),
            Case("HTTP/1.1 200\rX\r\n\r\n", true, err(HttpParseError.Status), err(HttpParseError.Status)),
            Case("HTTP/1.1 200 OK\r\n\rX", true, err(HttpParseError.NewLine), err(HttpParseError.NewLine)),
            // A lone CR while skipping an invalid line.
            Case(
                "HTTP/1.1 200 OK\r\nBad Name\rX\r\n\r\n", true, err(HttpParseError.HeaderName), err(HttpParseError.HeaderName),
                ParserConfig(ignoreInvalidHeadersInResponses = true),
            ),
        ),
    )

    /**
     * Every `tests/uri.rs` case with each `CR LF` turned into a bare `LF`: `allowBareLf = true` parses it with the
     * same results as the `CR LF` original (httparse behaviour); the default configuration rejects it.
     */
    @Test fun uri_cases_with_bare_lf() {
        for (c in URI_CASES) {
            if (c.error != null) continue
            val input = b(c.input.replace("\r\n", "\n"))
            val lenient = parseReq(input, BARE_LF)
            assertStatus(complete(input.size), lenient.status, c.name)
            assertEquals(c.path, lenient.path, c.name)
            assertEquals(c.headers.size, lenient.headerCount, c.name)
            for ((i, h) in c.headers.withIndex()) lenient.assertHeader(i, h.first, h.second)
            val strict = parseReq(input).status
            assertTrue(ParseStatus.isError(strict), "${c.name}: ${ParseStatus.describe(strict)}")
        }
    }
}
