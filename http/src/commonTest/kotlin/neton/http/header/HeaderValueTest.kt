package neton.http.header

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports of the tests in http 1.5.0 `src/header/value.rs`, plus its asserting doc examples. */
class HeaderValueTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    // ===== from_integers! (one test per generated Rust test) =====

    private fun checkInteger(small: HeaderValue, smallText: String, max: HeaderValue, maxText: String) {
        assertTrue(small.contentEquals(smallText), "$small != $smallText")
        assertTrue(max.contentEquals(maxText), "$max != $maxText")
    }

    @Test fun fromU16() = checkInteger(HeaderValue.from(55.toUShort()), "55", HeaderValue.from(UShort.MAX_VALUE), UShort.MAX_VALUE.toString())
    @Test fun fromI16() = checkInteger(HeaderValue.from(55.toShort()), "55", HeaderValue.from(Short.MAX_VALUE), Short.MAX_VALUE.toString())
    @Test fun fromU32() = checkInteger(HeaderValue.from(55u), "55", HeaderValue.from(UInt.MAX_VALUE), UInt.MAX_VALUE.toString())
    @Test fun fromI32() = checkInteger(HeaderValue.from(55), "55", HeaderValue.from(Int.MAX_VALUE), Int.MAX_VALUE.toString())
    @Test fun fromU64() = checkInteger(HeaderValue.from(55uL), "55", HeaderValue.from(ULong.MAX_VALUE), ULong.MAX_VALUE.toString())
    @Test fun fromI64() = checkInteger(HeaderValue.from(55L), "55", HeaderValue.from(Long.MAX_VALUE), Long.MAX_VALUE.toString())

    // usize / isize on the 64-bit targets map to ULong / Long.
    @Test fun fromUsize() = checkInteger(HeaderValue.from(55uL), "55", HeaderValue.from(ULong.MAX_VALUE), "18446744073709551615")
    @Test fun fromIsize() = checkInteger(HeaderValue.from(55L), "55", HeaderValue.from(Long.MAX_VALUE), "9223372036854775807")

    @Test
    fun fromNegativeIntegers() {
        val cases = listOf(
            HeaderValue.from(Short.MIN_VALUE) to Short.MIN_VALUE.toString(),
            HeaderValue.from(Int.MIN_VALUE) to Int.MIN_VALUE.toString(),
            HeaderValue.from(Long.MIN_VALUE) to Long.MIN_VALUE.toString(),
            HeaderValue.from(-1) to "-1",
            HeaderValue.from(0) to "0",
            HeaderValue.from(0uL) to "0",
        )
        for ((v, s) in cases) assertEquals(s, v.toStr())
    }

    // ===== mod from_header_name_tests =====

    @Test
    fun itCanInsertHeaderNameAsHeaderValue() {
        // The reference inserts through a HeaderMap (ported separately); the conversion under test is From<HeaderName>.
        assertEquals(
            HeaderValue.fromBytes("sec-websocket-protocol".encodeToByteArray()),
            HeaderValue.fromName(HeaderName.SEC_WEBSOCKET_PROTOCOL),
        )
        assertEquals(
            HeaderValue.fromBytes("hello-world".encodeToByteArray()),
            HeaderValue.fromName(HeaderName.fromBytes("hello-world".encodeToByteArray())),
        )
    }

    // ===== mod try_from_header_name_tests =====

    @Test
    fun itConvertsUsingTryFrom() {
        assertEquals(HeaderValue.fromBytes("upgrade".encodeToByteArray()), HeaderValue.fromName(HeaderName.UPGRADE))
    }

    // ===== top-level tests =====

    @Test
    fun testTryFrom() {
        assertNull(HeaderValue.tryFromMaybeShared(bytes(127)))
        assertFailsWith<InvalidHeaderValue> { HeaderValue.fromMaybeShared(bytes(127)) }
    }

    @Test
    fun testDebug() {
        val cases = listOf(
            "hello" to "\"hello\"",
            "hello \"world\"" to "\"hello \\\"world\\\"\"",
            "翿hello" to "\"\\xe7\\xbf\\xbfhello\"",
        )
        for ((value, expected) in cases) {
            val v = HeaderValue.fromBytes(value.encodeToByteArray())
            assertEquals(expected, v.toString())
        }

        val sensitive = HeaderValue.fromStatic("password")
        sensitive.isSensitive = true
        assertEquals("Sensitive", sensitive.toString())
    }

    // ===== doc examples =====

    @Test
    fun docFromStatic() {
        assertTrue(HeaderValue.fromStatic("hello").contentEquals("hello"))
    }

    @Test
    fun docFromStr() {
        assertTrue(HeaderValue.fromStr("hello").contentEquals("hello"))
        assertNull(HeaderValue.tryFromStr("\n"))
        assertFailsWith<InvalidHeaderValue> { HeaderValue.fromStr("\n") }
    }

    @Test
    fun docFromName() {
        assertEquals(HeaderValue.fromBytes("accept".encodeToByteArray()), HeaderValue.fromName(HeaderName.ACCEPT))
    }

    @Test
    fun docFromBytes() {
        val raw = "hello".encodeToByteArray() + bytes(0xfa)
        assertTrue(HeaderValue.fromBytes(raw).contentEquals(raw))
        assertNull(HeaderValue.tryFromBytes(bytes('\n'.code)))
    }

    @Test
    fun docToStr() {
        assertEquals("hello", HeaderValue.fromStatic("hello").toStr())
    }

    @Test
    fun docLen() {
        assertEquals(5, HeaderValue.fromStatic("hello").length)
    }

    @Test
    fun docIsEmpty() {
        assertTrue(HeaderValue.fromStatic("").isEmpty())
        assertFalse(HeaderValue.fromStatic("hello").isEmpty())
    }

    @Test
    fun docAsBytes() {
        assertTrue(HeaderValue.fromStatic("hello").asBytes().contentEquals("hello".encodeToByteArray()))
    }

    @Test
    fun docSetSensitive() {
        val v = HeaderValue.fromStatic("my secret")
        v.isSensitive = true
        assertTrue(v.isSensitive)
        v.isSensitive = false
        assertFalse(v.isSensitive)
    }

    // ===== additional coverage of the SPEC rules =====

    @Test
    fun byteRuleAllowsObsTextAndTab() {
        for (b in 0 until 256) {
            val valid = b >= 32 && b != 127 || b == '\t'.code
            assertEquals(valid, HeaderValue.tryFromBytes(bytes(b)) != null, "byte $b")
        }
    }

    @Test
    fun fromStaticIsStricter() {
        HeaderValue.fromStatic("a\tb ~")
        assertFailsWith<InvalidHeaderValue> { HeaderValue.fromStatic("café") }
        assertFailsWith<InvalidHeaderValue> { HeaderValue.fromStatic("a\u007f") }
        assertFailsWith<InvalidHeaderValue> { HeaderValue.fromStatic("a\r\n") }
        // fromStr accepts non-ASCII text as obs-text bytes.
        assertEquals(5, HeaderValue.fromStr("café").length)
    }

    @Test
    fun toStrOnlyForVisibleAsciiOrTab() {
        assertEquals("a\tb", HeaderValue.fromBytes(bytes('a'.code, 9, 'b'.code)).toStr())
        val obs = HeaderValue.fromBytes(bytes('a'.code, 0x80))
        assertNull(obs.tryToStr())
        assertFailsWith<ToStrError> { obs.toStr() }
    }

    @Test
    fun sensitivityIsNotPartOfEquality() {
        val a = HeaderValue.fromStatic("x")
        val b = HeaderValue.fromStatic("x")
        b.isSensitive = true
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(0, a.compareTo(b))
    }

    @Test
    fun comparisonsFollowTheBytes() {
        val a = HeaderValue.fromStatic("abc")
        assertTrue(a < HeaderValue.fromStatic("abd"))
        assertTrue(a > HeaderValue.fromStatic("ab"))
        assertTrue(a < "abcd")
        assertTrue(a > "ab")
        assertEquals(0, a.compareTo("abc"))
        // Unsigned: obs-text sorts after ASCII.
        assertTrue(HeaderValue.fromBytes(bytes(0xfa)) > HeaderValue.fromStatic("z"))
        assertTrue(HeaderValue.fromBytes(bytes(0xfa)) > "z")
        assertTrue(HeaderValue.fromStr("é").contentEquals("é"))
        assertTrue(HeaderValue.fromStr("aé") > "a")
        assertTrue(HeaderValue.fromStatic("a") < "aé")
        assertTrue(a.contentEquals("abc".encodeToByteArray()))
        assertFalse(a.contentEquals("abC"))
    }

    @Test
    fun fromMaybeSharedDoesNotCopyAndFromBytesDoes() {
        val buf = "xxvaluexx".encodeToByteArray()
        val shared = HeaderValue.fromMaybeShared(buf, 2, 5)
        val copied = HeaderValue.fromBytes(buf, 2, 5)
        assertEquals("value", shared.toStr())
        buf[2] = 'V'.code.toByte()
        assertEquals("Value", shared.toStr())
        assertEquals("value", copied.toStr())
        assertEquals(5, shared.length)
        assertEquals('V'.code.toByte(), shared.byteAt(0))
    }
}
