package neton.http.h1.parse

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Helpers mirroring the reference test harness (`lib.rs` `req!` / `res!`, `EMPTY_HEADER; N`).

/** `NUM_OF_HEADERS` of the reference tests. */
internal const val NUM_OF_HEADERS = 4

/** Bytes of a Rust byte-string literal written as a Kotlin string: each char is one byte (`\u00FF` = `\xFF`). */
internal fun b(s: String): ByteArray = ByteArray(s.length) {
    val c = s[it].code
    require(c < 256) { "not a byte: $c" }
    c.toByte()
}

internal fun complete(n: Int): Int = n
internal const val PARTIAL: Int = ParseStatus.PARTIAL
internal fun err(e: HttpParseError): Int = e.code

internal val BARE_LF: ParserConfig = ParserConfig(allowBareLf = true)

internal fun assertStatus(expected: Int, actual: Int, message: String? = null) =
    assertEquals(ParseStatus.describe(expected), ParseStatus.describe(actual), message)

/** A request parse outcome, with accessors shaped like the reference `Request`. */
internal class Req(val status: Int, val r: ParsedRequest, val buf: ByteArray) {
    val method: String? get() = r.methodString()
    val path: String? get() = r.pathString()
    val version: Int get() = r.version
    val headerCount: Int get() = r.headers.count
    fun name(i: Int): String = r.headers.name(i, buf)
    fun value(i: Int): ByteArray = r.headers.value(i, buf)
}

/** A response parse outcome, with accessors shaped like the reference `Response`. */
internal class Res(val status: Int, val r: ParsedResponse, val buf: ByteArray) {
    val version: Int get() = r.version
    val code: Int get() = r.code
    val reason: String? get() = r.reasonString()
    val headerCount: Int get() = r.headers.count
    fun name(i: Int): String = r.headers.name(i, buf)
    fun value(i: Int): ByteArray = r.headers.value(i, buf)
}

internal fun assertHeader(expectedName: String, expectedValue: String, name: String, value: ByteArray) {
    assertEquals(expectedName, name)
    assertContentEquals(b(expectedValue), value, "value of $expectedName")
}

internal fun Req.assertHeader(i: Int, name: String, value: String) = assertHeader(name, value, name(i), value(i))
internal fun Res.assertHeader(i: Int, name: String, value: String) = assertHeader(name, value, name(i), value(i))

private const val PAD_BEFORE = "ZZZ"
private const val PAD_AFTER = "\r\n\r\nX: y\r\n\r\n"

/**
 * Parses [input] as a request. The same parse is repeated on a copy embedded in a larger array (junk before
 * [offset], and after `offset + length`), and both parses must agree: same status and the same offsets relative
 * to the start of the input. This checks the offset/length handling and that the parser never reads outside
 * its window.
 */
internal fun parseReq(input: ByteArray, config: ParserConfig = ParserConfig.DEFAULT, capacity: Int = NUM_OF_HEADERS): Req {
    val r = ParsedRequest(HeaderSlots(capacity))
    val status = r.parse(input, 0, input.size, config)

    val big = b(PAD_BEFORE) + input + b(PAD_AFTER)
    val r2 = ParsedRequest(HeaderSlots(capacity))
    val s2 = r2.parse(big, PAD_BEFORE.length, input.size, config)
    assertStatus(status, s2, "embedded parse")
    val d = PAD_BEFORE.length
    fun shifted(v: Int) = if (v < 0) v else v + d
    assertEquals(shifted(r.methodStart), r2.methodStart)
    assertEquals(shifted(r.methodEnd), r2.methodEnd)
    assertEquals(shifted(r.pathStart), r2.pathStart)
    assertEquals(shifted(r.pathEnd), r2.pathEnd)
    assertEquals(r.version, r2.version)
    assertSameSlots(r.headers, r2.headers, d, status >= 0)
    return Req(status, r, input)
}

/** Response counterpart of [parseReq]. */
internal fun parseRes(input: ByteArray, config: ParserConfig = ParserConfig.DEFAULT, capacity: Int = NUM_OF_HEADERS): Res {
    val r = ParsedResponse(HeaderSlots(capacity))
    val status = r.parse(input, 0, input.size, config)

    val big = b(PAD_BEFORE) + input + b(PAD_AFTER)
    val r2 = ParsedResponse(HeaderSlots(capacity))
    val s2 = r2.parse(big, PAD_BEFORE.length, input.size, config)
    assertStatus(status, s2, "embedded parse")
    val d = PAD_BEFORE.length
    fun shifted(v: Int) = if (v < 0) v else v + d
    assertEquals(r.version, r2.version)
    assertEquals(r.code, r2.code)
    assertEquals(shifted(r.reasonStart), r2.reasonStart)
    assertEquals(shifted(r.reasonEnd), r2.reasonEnd)
    assertSameSlots(r.headers, r2.headers, d, status >= 0)
    return Res(status, r, input)
}

private fun assertSameSlots(a: HeaderSlots, b: HeaderSlots, d: Int, complete: Boolean) {
    assertEquals(a.count, b.count)
    if (!complete) return
    for (i in 0 until a.count) {
        assertEquals(a.nameStart[i] + d, b.nameStart[i])
        assertEquals(a.nameEnd[i] + d, b.nameEnd[i])
        assertEquals(a.valueStart[i] + d, b.valueStart[i])
        assertEquals(a.valueEnd[i] + d, b.valueEnd[i])
    }
}

internal fun assertAllInBuffer(offsets: IntArray, size: Int) {
    for (o in offsets) assertTrue(o in 0..size, "offset $o outside 0..$size")
}
