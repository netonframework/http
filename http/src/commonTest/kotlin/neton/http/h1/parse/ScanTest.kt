package neton.http.h1.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scanner tests from httparse 1.10.1 `src/simd/`.
 *
 * - `swar.rs` (3 tests): ported directly against the SWAR block matchers.
 * - `sse42.rs` (2), `avx2.rs` (2), `neon.rs` (3): the reference checks that each vector scanner stops exactly
 *   where the byte tables (`URI_MAP`, `HEADER_VALUE_MAP`, `TOKEN_MAP`) say, for one probe byte placed in a
 *   buffer of `_` at the position those scanners use (16 bytes with the probe at 10 for SSE4.2/NEON, 32 bytes
 *   with the probe at 26 for AVX2). This port has no vector scanners, so each of those tests becomes an
 *   equivalence test of the SWAR scanner against a plain scalar loop over the same buffers and all 256 probe
 *   bytes, keeping the reference's expected stop positions.
 * - An extra check compares the SWAR scanners with the scalar loop for the class-boundary bytes at every
 *   position of buffers of length 0..40 (covering the 8-byte blocks and the byte-wise tail).
 */
class ScanTest {
    private fun block(vararg bytes: Int): Long {
        require(bytes.size == BLOCK_SIZE)
        return loadWord(ByteArray(BLOCK_SIZE) { bytes[it].toByte() }, 0)
    }

    private fun uniform(b: Int): Long = block(*IntArray(BLOCK_SIZE) { b })
    private fun block(s: String): Long = loadWord(b(s), 0)

    // ---- swar.rs --------------------------------------------------------------------------------------------

    @Test fun test_is_header_value_block() {
        fun isHeaderValueBlock(x: Long) = matchHeaderValueChar8(x) == BLOCK_SIZE
        // 0..32 => false
        for (b in 0 until 32) assertFalse(isHeaderValueBlock(uniform(b)), "b=$b")
        // 32..=126 => true
        for (b in 32..126) assertTrue(isHeaderValueBlock(uniform(b)), "b=$b")
        // 127 => false
        assertFalse(isHeaderValueBlock(uniform(0x7F)), "b=127")
        // 128..=255 => true
        for (b in 128..255) assertTrue(isHeaderValueBlock(uniform(b)), "b=$b")
        // A few sanity checks on non-uniform bytes.
        assertFalse(isHeaderValueBlock(block("foo.com\n")))
        assertFalse(isHeaderValueBlock(block("o.com\r\nU")))
    }

    @Test fun test_is_uri_block() {
        fun isUriBlock(x: Long) = matchUriChar8(x) == BLOCK_SIZE
        // 0..33 => false
        for (b in 0 until 33) assertFalse(isUriBlock(uniform(b)), "b=$b")
        // 33..=126 => true
        for (b in 33..126) assertTrue(isUriBlock(uniform(b)), "b=$b")
        // 127 => false
        assertFalse(isUriBlock(uniform(0x7F)), "b=127")
        // 128..=255 => true
        for (b in 128..255) assertTrue(isUriBlock(uniform(b)), "b=$b")
    }

    @Test fun test_offsetnz() {
        for (i in 0 until BLOCK_SIZE) {
            val seq = IntArray(BLOCK_SIZE)
            seq[i] = 1
            assertEquals(i, offsetNonZero(block(*seq)))
        }
        assertEquals(BLOCK_SIZE, offsetNonZero(0L))
    }

    // ---- sse42.rs / avx2.rs / neon.rs, as SWAR-vs-scalar equivalence -----------------------------------------

    private fun scalarScan(buf: ByteArray, pos: Int, end: Int, allowed: (Int) -> Boolean): Int {
        var p = pos
        while (p < end && allowed(buf[p].toInt() and 0xFF)) p++
        return p
    }

    /** The reference `byte_is_allowed`: `size` bytes of `_` with [byte] at [at]; true if the scan runs to the end. */
    private fun byteIsAllowed(byte: Int, size: Int, at: Int, scan: (ByteArray, Int, Int) -> Int, allowed: (Int) -> Boolean): Boolean {
        val buf = ByteArray(size) { '_'.code.toByte() }
        buf[at] = byte.toByte()
        val pos = scan(buf, 0, size)
        // The scalar loop over the same buffer is the table the reference compares with.
        assertEquals(scalarScan(buf, 0, size, allowed), pos, "byte $byte")
        return when (pos) {
            size -> true
            at -> false
            else -> throw AssertionError("unexpected pos: $pos")
        }
    }

    private fun checkTable(size: Int, at: Int, scan: (ByteArray, Int, Int) -> Int, allowed: (Int) -> Boolean) {
        assertTrue(byteIsAllowed('_'.code, size, at, scan, allowed))
        for (b in 0 until 256) {
            assertEquals(allowed(b), byteIsAllowed(b, size, at, scan, allowed), "byte_is_allowed($b) should be ${allowed(b)}")
        }
    }

    private val uri: (ByteArray, Int, Int) -> Int = ::scanUri
    private val value: (ByteArray, Int, Int) -> Int = ::scanHeaderValue
    private val name: (ByteArray, Int, Int) -> Int = ::scanHeaderName

    /** Byte tables written out from the reference `byte_map!` patterns, independent of the parser's predicates. */
    private fun uriMap(b: Int) = b in 0x21..0x7E || b in 0x80..0xFF
    private fun headerValueMap(b: Int) = b == '\t'.code || b in 0x20..0x7E || b in 0x80..0xFF
    private fun tokenMap(b: Int) = b.toChar().let {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || (b < 128 && it in "!#$%&'*+-.^_`|~")
    }

    @Test fun sse_code_matches_uri_chars_table() = checkTable(16, 10, uri, ::uriMap)
    @Test fun sse_code_matches_header_value_chars_table() = checkTable(16, 10, value, ::headerValueMap)
    @Test fun avx2_code_matches_uri_chars_table() = checkTable(32, 26, uri, ::uriMap)
    @Test fun avx2_code_matches_header_value_chars_table() = checkTable(32, 26, value, ::headerValueMap)
    @Test fun neon_code_matches_uri_chars_table() = checkTable(16, 10, uri, ::uriMap)
    @Test fun neon_code_matches_header_value_chars_table() = checkTable(16, 10, value, ::headerValueMap)
    @Test fun neon_code_matches_header_name_chars_table() = checkTable(16, 10, name, ::tokenMap)

    @Test fun predicates_match_reference_byte_maps() {
        for (b in 0 until 256) {
            assertEquals(uriMap(b), isUriToken(b), "uri $b")
            assertEquals(headerValueMap(b), isHeaderValueToken(b), "value $b")
            assertEquals(tokenMap(b), isHeaderNameToken(b), "token $b")
            assertEquals(tokenMap(b), isMethodToken(b), "method $b")
        }
    }

    /** Bytes at the edges of the URI, header-value and tchar classes, plus a plain letter. */
    private val BOUNDARY_BYTES = intArrayOf(
        0x00, 0x01, 0x08, 0x09, 0x0A, 0x0D, 0x1F, 0x20, 0x21, 0x22, 0x28, 0x2F, 0x3A, 0x7B, 0x7E, 0x7F, 0x80, 0xC3, 0xFE, 0xFF, 'a'.code,
    )

    @Test fun swar_scanners_match_scalar_at_every_position() {
        for (size in 0..40) {
            for (at in 0 until maxOf(size, 1)) {
                for (probe in BOUNDARY_BYTES) {
                    val buf = ByteArray(size) { 'a'.code.toByte() }
                    if (size > 0) buf[at] = probe.toByte()
                    // Scan from each start offset, with the window ending before the array end sometimes.
                    for (start in 0..minOf(size, 1)) {
                        for (end in maxOf(start, size - 1)..size) {
                            assertEquals(scalarScan(buf, start, end, ::uriMap), scanUri(buf, start, end))
                            assertEquals(scalarScan(buf, start, end, ::headerValueMap), scanHeaderValue(buf, start, end))
                            assertEquals(scalarScan(buf, start, end, ::tokenMap), scanHeaderName(buf, start, end))
                        }
                    }
                }
            }
        }
    }
}
