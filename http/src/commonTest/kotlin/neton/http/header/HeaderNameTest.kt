package neton.http.header

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Ports of the tests in http 1.5.0 `src/header/name.rs`, plus its asserting doc examples. */
class HeaderNameTest {
    private val standard: Array<HeaderName> get() = HeaderName.standardAll

    private fun mixedCase(s: String): String =
        s.mapIndexed { i, c -> if (i % 2 == 0) c.uppercaseChar() else c }.joinToString("")

    // ===== macro-generated tests (standard_headers!) =====

    @Test
    fun testParseStandardHeaders() {
        for (std in standard) {
            val name = std.asStr()
            // Lower case
            assertEquals(std, HeaderName.fromBytes(name.encodeToByteArray()))
            // Upper case
            assertEquals(std, HeaderName.fromBytes(name.uppercase().encodeToByteArray()))
        }
    }

    @Test
    fun testStandardHeadersIntoBytes() {
        for (std in standard) {
            val name = std.asStr()
            val lower = HeaderName.fromBytes(name.encodeToByteArray())
            assertTrue(lower.toByteArray().contentEquals(name.encodeToByteArray()))
            assertEquals(std, lower)

            val upper = HeaderName.fromBytes(name.uppercase().encodeToByteArray())
            assertTrue(upper.toByteArray().contentEquals(name.encodeToByteArray()))
            assertEquals(std, upper)
        }
    }

    // ===== mod tests =====

    @Test
    fun testBounds() {
        // Rust checks `HeaderName: Send + Sync`. Kotlin/Native shares objects freely; the equivalent guarantee is
        // immutability: every constructor returns a value whose observable state never changes.
        val a = HeaderName.fromStr("X-Custom")
        val before = a.asStr()
        a.toByteArray()[0] = 'z'.code.toByte()
        assertEquals(before, a.asStr())
    }

    @Test
    fun testParseInvalidHeaders() {
        for (i in 0 until 128) {
            val hdr = ByteArray(i) { 1 }
            assertNull(HeaderName.tryFromBytes(hdr), "$i invalid header chars did not fail")
            assertFailsWith<InvalidHeaderName> { HeaderName.fromBytes(hdr) }
        }
    }

    @Test
    fun testInvalidNameLengths() {
        assertNull(HeaderName.tryFromBytes(ByteArray(0)), "zero-length header name is an error")

        val oneTooLong = ByteArray(MAX_HEADER_NAME_LEN + 1) { 'a'.code.toByte() }
        val longStr = "a".repeat(MAX_HEADER_NAME_LEN)
        assertTrue(HeaderName.fromStatic(longStr).equalsIgnoreCase(longStr)) // must not throw

        assertEquals(longStr, HeaderName.fromBytes(oneTooLong, 0, MAX_HEADER_NAME_LEN).asStr(), "max length is ok")
        assertNull(HeaderName.tryFromBytes(oneTooLong), "longer than max header name length is an error")
    }

    @Test
    fun testStaticInvalidNameLengths() {
        assertFailsWith<InvalidHeaderName> { HeaderName.fromStatic("a".repeat(MAX_HEADER_NAME_LEN + 1)) }
    }

    @Test
    fun testFromHdrName() {
        // The reference converts its borrowed `HdrName` into a `HeaderName`; here the same three inputs go through
        // the parsing constructors: a standard name, a lowercase custom name, and a mixed-case custom name.
        assertSame(HeaderName.VARY, HeaderName.fromBytes("vary".encodeToByteArray()))

        val lower = HeaderName.fromLowercase("hello-world".encodeToByteArray())
        assertEquals("hello-world", lower.asStr())
        assertEquals(-1, lower.standardIndex)

        val mixed = HeaderName.fromBytes("Hello-World".encodeToByteArray())
        assertEquals("hello-world", mixed.asStr())
        assertEquals(lower, mixed)
    }

    @Test
    fun testEqHdrName() {
        // The reference compares a `HeaderName` with a borrowed `HdrName`; here the borrowed side is raw bytes,
        // matched without allocating through `hashOf` + `equalsIgnoreCase` (the hash map lookup path).
        fun matches(a: HeaderName, raw: String): Boolean {
            val b = raw.encodeToByteArray()
            return HeaderName.hashOf(b) == a.hash && a.equalsIgnoreCase(b)
        }

        val a = HeaderName.VARY
        assertTrue(matches(a, "vary"))

        val custom = HeaderName.fromStatic("vaary")
        assertNotEquals(custom, a)
        assertFalse(matches(custom, "vary"))

        assertTrue(matches(custom, "vaary")) // lower: true
        assertTrue(matches(custom, "VAARY")) // lower: false
        assertFalse(matches(a, "VAARY"))
    }

    @Test
    fun testFromStaticStd() {
        val a = HeaderName.VARY
        assertEquals(a, HeaderName.fromStatic("vary"))
        assertSame(a, HeaderName.fromStatic("vary"))
        assertNotEquals(a, HeaderName.fromStatic("vaary"))
    }

    @Test
    fun testFromStaticStdUppercase() {
        assertFailsWith<InvalidHeaderName> { HeaderName.fromStatic("Vary") }
    }

    @Test
    fun testFromStaticStdSymbol() {
        assertFailsWith<InvalidHeaderName> { HeaderName.fromStatic("vary{}") }
    }

    @Test
    fun testFromStaticCustomShort() {
        val b = HeaderName.fromStatic("customheader")
        assertEquals("customheader", b.asStr())
        assertEquals(HeaderName.fromBytes("customheader".encodeToByteArray()), b)
    }

    @Test
    fun testFromStaticCustomShortUppercase() {
        assertFailsWith<InvalidHeaderName> { HeaderName.fromStatic("custom header") }
    }

    @Test
    fun testFromStaticCustomShortSymbol() {
        assertFailsWith<InvalidHeaderName> { HeaderName.fromStatic("CustomHeader") }
    }

    @Test
    fun testFromStaticCustomLong() {
        val s = "longer-than-63--thisheaderislongerthansixtythreecharactersandthushandleddifferent"
        val b = HeaderName.fromStatic(s)
        assertEquals(s, b.asStr())
        assertEquals(HeaderName.fromBytes(s.encodeToByteArray()), b)
    }

    @Test
    fun testFromStaticCustomLongUppercase() {
        assertFailsWith<InvalidHeaderName> {
            HeaderName.fromStatic("Longer-Than-63--ThisHeaderIsLongerThanSixtyThreeCharactersAndThusHandledDifferent")
        }
    }

    @Test
    fun testFromStaticCustomLongSymbol() {
        assertFailsWith<InvalidHeaderName> {
            HeaderName.fromStatic(
                "longer-than-63--thisheader{}{}{}{}islongerthansixtythreecharactersandthushandleddifferent",
            )
        }
    }

    @Test
    fun testFromStaticCustomSingleChar() {
        val b = HeaderName.fromStatic("a")
        assertEquals("a", b.asStr())
        assertEquals(HeaderName.fromBytes(byteArrayOf('a'.code.toByte())), b)
    }

    @Test
    fun testFromStaticEmpty() {
        assertFailsWith<InvalidHeaderName> { HeaderName.fromStatic("") }
    }

    @Test
    fun testAllTokens() {
        HeaderName.fromStatic("!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyz")
    }

    @Test
    fun testFromLowercase() {
        for (n in intArrayOf(10, 100)) {
            for (b in intArrayOf(0, 'A'.code, 0x1, 0xFF)) {
                if (n == 100 && b == 0) continue // commented out in the reference
                assertFailsWith<InvalidHeaderName>("byte $b x $n") {
                    HeaderName.fromLowercase(ByteArray(n) { b.toByte() })
                }
            }
        }
    }

    // ===== doc examples =====

    @Test
    fun docFromLowercase() {
        assertEquals(HeaderName.CONTENT_LENGTH, HeaderName.fromLowercase("content-length".encodeToByteArray()))
        assertNull(HeaderName.tryFromLowercase("Content-Length".encodeToByteArray()))
    }

    @Test
    fun docFromStatic() {
        assertEquals(HeaderName.CONTENT_LENGTH, HeaderName.fromStatic("content-length"))
        assertEquals(HeaderName.fromLowercase("custom-header".encodeToByteArray()), HeaderName.fromStatic("custom-header"))
        assertFailsWith<InvalidHeaderName> { HeaderName.fromStatic("content{}{}length") }
        HeaderName.fromStatic("foobar")
        assertFailsWith<InvalidHeaderName> { HeaderName.fromStatic("FOOBAR") }
    }

    @Test
    fun docPartialEqStr() {
        assertTrue(HeaderName.CONTENT_LENGTH.equalsIgnoreCase("content-length"))
        assertTrue(HeaderName.CONTENT_LENGTH.equalsIgnoreCase("Content-Length"))
        assertFalse(HeaderName.CONTENT_LENGTH.equalsIgnoreCase("content length"))
    }

    // ===== additional coverage of the SPEC rules =====

    @Test
    fun everyStandardNameRoundTripsToTheSameInstance() {
        assertEquals(81, standard.size)
        assertEquals(81, standard.map { it.asStr() }.toSet().size)
        for ((i, std) in standard.withIndex()) {
            val name = std.asStr()
            assertEquals(i, std.standardIndex)
            for (form in listOf(name, name.uppercase(), mixedCase(name))) {
                assertSame(std, HeaderName.fromBytes(form.encodeToByteArray()), form)
                assertSame(std, HeaderName.fromStr(form), form)
                // Inside a larger buffer, as a parser would pass it.
                val buf = "xx$form: v".encodeToByteArray()
                assertSame(std, HeaderName.fromBytes(buf, 2, form.length), form)
                assertEquals(std.hash, HeaderName.hashOf(form), form)
                assertTrue(std.equalsIgnoreCase(form), form)
            }
            assertSame(std, HeaderName.fromLowercase(name.encodeToByteArray()))
            assertSame(std, HeaderName.fromStatic(name))
            assertNull(HeaderName.tryFromLowercase(name.uppercase().encodeToByteArray()))
        }
    }

    @Test
    fun standardConstantsHaveTheReferenceNames() {
        assertEquals("accept", HeaderName.ACCEPT.asStr())
        assertEquals("content-type", HeaderName.CONTENT_TYPE.asStr())
        assertEquals("content-security-policy-report-only", HeaderName.CONTENT_SECURITY_POLICY_REPORT_ONLY.asStr())
        assertEquals("www-authenticate", HeaderName.WWW_AUTHENTICATE.asStr())
        assertEquals("x-xss-protection", HeaderName.X_XSS_PROTECTION.asStr())
        assertEquals("content-type", HeaderName.CONTENT_TYPE.toString())
    }

    @Test
    fun customNamesFoldCaseAndHashConsistently() {
        val a = HeaderName.fromStr("X-Request-Id")
        val b = HeaderName.fromBytes("x-REQUEST-id".encodeToByteArray())
        assertEquals("x-request-id", a.asStr())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a.hash, HeaderName.hashOf("X-REQUEST-ID"))
        assertEquals(a.hash, HeaderName.hashOf("x-request-id".encodeToByteArray()))
        assertNotEquals(a, HeaderName.fromStr("x-request-ie"))
    }

    @Test
    fun onlyTcharIsAccepted() {
        for (c in 0 until 256) {
            val isTchar = c.toChar() in "!#$%&'*+-.^_`|~" || c.toChar() in '0'..'9' ||
                c.toChar() in 'a'..'z' || c.toChar() in 'A'..'Z'
            val parsed = HeaderName.tryFromBytes(byteArrayOf('x'.code.toByte(), c.toByte()))
            assertEquals(isTchar, parsed != null, "byte $c")
        }
        assertNull(HeaderName.tryFromStr("x-é"))
        assertNull(HeaderName.tryFromStr("x y"))
        assertFailsWith<InvalidHeaderName> { HeaderName.fromStr("") }
    }

    @Test
    fun equalsIgnoreCaseRejectsInvalidCharacters() {
        val a = HeaderName.fromStatic("x-a")
        assertFalse(a.equalsIgnoreCase("x-a "))
        assertFalse(a.equalsIgnoreCase("x a"))
        assertFalse(a.equalsIgnoreCase(byteArrayOf('x'.code.toByte(), 0, 'a'.code.toByte())))
    }
}
