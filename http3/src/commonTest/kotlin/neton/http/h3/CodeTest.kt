package neton.http.h3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// `src/error/codes.rs` has no tests; these check the values against RFC 9114 §8.1, RFC 9204 §6 and RFC 9297 §5.2.
class CodeTest {
    @Test
    fun valuesAndNames() {
        val expected = listOf(
            Code.H3_DATAGRAM_ERROR to 0x33L, Code.H3_NO_ERROR to 0x100L, Code.H3_GENERAL_PROTOCOL_ERROR to 0x101L,
            Code.H3_INTERNAL_ERROR to 0x102L, Code.H3_STREAM_CREATION_ERROR to 0x103L,
            Code.H3_CLOSED_CRITICAL_STREAM to 0x104L, Code.H3_FRAME_UNEXPECTED to 0x105L, Code.H3_FRAME_ERROR to 0x106L,
            Code.H3_EXCESSIVE_LOAD to 0x107L, Code.H3_ID_ERROR to 0x108L, Code.H3_SETTINGS_ERROR to 0x109L,
            Code.H3_MISSING_SETTINGS to 0x10aL, Code.H3_REQUEST_REJECTED to 0x10bL,
            Code.H3_REQUEST_CANCELLED to 0x10cL, Code.H3_REQUEST_INCOMPLETE to 0x10dL, Code.H3_MESSAGE_ERROR to 0x10eL,
            Code.H3_CONNECT_ERROR to 0x10fL, Code.H3_VERSION_FALLBACK to 0x110L,
            Code.QPACK_DECOMPRESSION_FAILED to 0x200L, Code.QPACK_ENCODER_STREAM_ERROR to 0x201L,
            Code.QPACK_DECODER_STREAM_ERROR to 0x202L,
        )
        for ((code, value) in expected) {
            assertEquals(value, code.value)
            assertEquals(code, Code(value))
        }
        assertEquals("H3_NO_ERROR", Code.H3_NO_ERROR.toString())
        assertEquals("QPACK_DECODER_STREAM_ERROR", Code(0x202).toString())
        assertEquals("0x1f40", Code(0x1f40).toString())
        assertNotEquals(Code.H3_NO_ERROR, Code.H3_INTERNAL_ERROR)
    }
}
