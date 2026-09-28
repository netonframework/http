package neton.http.h1.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * httparse 1.10.1 `tests/uri.rs`: 263 `req!` cases (`urltest_001` .. `urltest_262` and `urltest_nvidia`), table-driven from
 * [URI_CASES]. Each case is parsed with `NUM_OF_HEADERS` slots and the default configuration (every input uses
 * `CR LF`), and checked against the reference expectations; failures are collected and reported together.
 */
class HttpParseUriTest {
    @Test fun all_263_cases_present() {
        assertEquals(263, URI_CASES.size)
        // The reference names the last case `urltest_nvidia`.
        val names = (1..262).map { "urltest_" + it.toString().padStart(3, '0') } + "urltest_nvidia"
        assertEquals(names, URI_CASES.map { it.name })
    }

    @Test fun uri_cases() {
        val failures = mutableListOf<String>()
        for (c in URI_CASES) {
            try {
                check(c)
            } catch (e: Throwable) {
                failures += "${c.name}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${URI_CASES.size} failed:\n" + failures.joinToString("\n"))
    }

    private fun check(c: UriCase) {
        val buf = b(c.input)
        val req = parseReq(buf)
        if (c.error != null) {
            assertStatus(err(c.error), req.status)
            return
        }
        assertStatus(complete(buf.size), req.status)
        assertEquals(c.method, req.method)
        assertEquals(c.path, req.path)
        assertEquals(c.version, req.version)
        assertEquals(c.headers.size, req.headerCount)
        for ((i, h) in c.headers.withIndex()) req.assertHeader(i, h.first, h.second)
    }
}
