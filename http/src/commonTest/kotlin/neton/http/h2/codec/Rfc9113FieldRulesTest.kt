package neton.http.h2.codec

import neton.http.Method
import neton.http.h2.frame.Reason
import neton.http.h2.proto.ProtoError
import neton.http.h2.frame.Headers
import neton.http.h2.hpack.Header
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

// RFC 9113 §8.2.1 (field validity), which RFC 7540 (what h2spec 2.1.1 checks) left looser. Fields go on the wire as raw
// HPACK literals, so nothing on the sending side normalises them.
// - A name with characters in 0x00-0x20, 0x41-0x5a (upper case) or 0x7f-0xff, a colon outside a pseudo-header, or a value
//   with NUL, CR or LF: refused, as h2 refuses it, as a connection PROTOCOL_ERROR (the HPACK representation does not
//   decode to a field). RFC 9113 §8.1.1 asks for a stream error; a connection error is the stronger reaction (SPEC §4).
// - ⚖️ A value that starts or ends with SP or HTAB (new in RFC 9113; h2 accepts it): malformed, a stream PROTOCOL_ERROR.

class Rfc9113FieldRulesTest {
    private val get = Header.Method(Method.GET)

    /** An HPACK literal without indexing, with a new name (RFC 7541 §6.2.2), raw bytes (no Huffman). */
    private fun literal(name: ByteArray, value: ByteArray): ByteArray {
        require(name.size < 127 && value.size < 127)
        return byteArrayOf(0x00, name.size.toByte()) + name + byteArrayOf(value.size.toByte()) + value
    }

    private fun literal(name: String, value: String) = literal(name.encodeToByteArray(), value.encodeToByteArray())

    private fun headers(block: ByteArray) = frame(1, 0x4, 1, block)

    private fun request(vararg fields: ByteArray) = headers(fields.fold(hpack(get)) { acc, f -> acc + f })

    @Test
    fun aValidFieldIsDecoded() {
        val h = assertIs<Headers>(readAll(request(literal("x-a", "b c"))).single())
        assertEquals("b c", h.fields[neton.http.header.HeaderName.fromStr("x-a")]!!.tryToStr())
    }

    private fun assertConnectionError(bytes: ByteArray) {
        val e = assertFailsWith<ProtoError.GoAway> { readAll(bytes) }
        assertEquals(Reason.PROTOCOL_ERROR, e.reason)
    }

    @Test
    fun upperCaseInANameIsRefused() {
        for (name in listOf("X-A", "x-A", "Content-Type")) assertConnectionError(request(literal(name, "b")))
    }

    @Test
    fun controlSpaceAndHighBytesInANameAreRefused() {
        for (b in listOf(0x00, 0x09, 0x20, 0x7f, 0x80, 0xff)) {
            assertConnectionError(request(literal(byteArrayOf('x'.code.toByte(), b.toByte(), 'a'.code.toByte()), "b".encodeToByteArray())))
        }
    }

    @Test
    fun aColonOutsideAPseudoHeaderIsRefused() {
        assertConnectionError(request(literal("x:a", "b")))
    }

    @Test
    fun nulCrLfInAValueAreRefused() {
        for (b in listOf(0x00, 0x0a, 0x0d)) {
            assertConnectionError(request(literal("x-a".encodeToByteArray(), byteArrayOf('a'.code.toByte(), b.toByte(), 'b'.code.toByte()))))
        }
    }

    @Test
    fun whitespaceAtEitherEndOfAValueIsMalformed() {
        for (value in listOf(" b", "b ", "\tb", "b\t", " ")) assertReset(request(literal("x-a", value)), 1)
        // Inside a value it is fine, and so is an empty value.
        readAll(request(literal("x-a", "b\tc d")))
        readAll(request(literal("x-a", "")))
    }
}
