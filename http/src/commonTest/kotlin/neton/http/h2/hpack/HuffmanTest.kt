package neton.http.h2.hpack

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Ported from h2 0.4.19 `src/hpack/huffman/mod.rs` (8 tests), plus a check that the generated decode table is the
// reference's `DECODE_TABLE`.

/** Huffman-encodes [src] (test helper). */
internal fun huffEncode(src: ByteArray): ByteArray {
    val out = ByteArray(huffmanEncodedLength(src, 0, src.size))
    val end = huffmanEncode(src, 0, src.size, out, 0)
    assertEquals(out.size, end)
    return out
}

/** Huffman-decodes [src], or null for [DecoderError.InvalidHuffmanCode] (test helper). */
internal fun huffDecodeOrNull(src: ByteArray, off: Int = 0, len: Int = src.size - off): ByteArray? {
    val out = ByteArray(2 * len)
    val n = huffmanDecode(src, off, len, out, 0)
    return if (n < 0) null else out.copyOf(n)
}

/** Huffman-decodes a range of [src] into a string, failing on an invalid code (test helper). */
internal fun huffDecode(src: ByteArray, from: Int, to: Int = src.size): String =
    huffDecodeOrNull(src, from, to - from)!!.decodeToString()

private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

private fun decode(src: ByteArray): String = huffDecodeOrNull(src)!!.decodeToString()

class HuffmanTest {
    @Test
    fun decodeSingleByte() {
        assertEquals("o", decode(bytes(0b00111111)))
        assertEquals("0", decode(bytes(7)))
        assertEquals("A", decode(bytes((0x21 shl 2) + 3)))
    }

    @Test
    fun singleCharMultiByte() {
        assertEquals("#", decode(bytes(255, 160 + 15)))
        assertEquals("$", decode(bytes(255, 200 + 7)))
        assertEquals("\u000a", decode(bytes(255, 255, 255, 240 + 3)))
    }

    @Test
    fun multiChar() {
        assertEquals("!0", decode(bytes(254, 1)))
        assertEquals(" !", decode(bytes(0b01010011, 0b11111000)))
    }

    @Test
    fun encodeSingleByte() {
        assertContentEquals(bytes(0b00111111), huffEncode("o".encodeToByteArray()))
        assertContentEquals(bytes(7), huffEncode("0".encodeToByteArray()))
        assertContentEquals(bytes((0x21 shl 2) + 3), huffEncode("A".encodeToByteArray()))
    }

    @Test
    fun encodeDecodeStr() {
        val data = listOf(
            "hello world",
            ":method",
            ":scheme",
            ":authority",
            "yahoo.co.jp",
            "GET",
            "http",
            ":path",
            "/images/top/sp2/cmn/logo-ns-130528.png",
            "example.com",
            "hpack-test",
            "xxxxxxx1",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:16.0) Gecko/20100101 Firefox/16.0",
            "accept",
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "cookie",
            "B=76j09a189a6h4&b=3&s=0b",
            "TE",
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Morbi non bibendum libero. " +
                "Etiam ultrices lorem ut.",
        )
        for (s in data) {
            val encoded = huffEncode(s.encodeToByteArray())
            assertContentEquals(s.encodeToByteArray(), huffDecodeOrNull(encoded))
            // The String encoder used for pseudo-header values produces the same bytes.
            val viaString = ByteArray(huffmanEncodedLength(s))
            huffmanEncode(s, viaString, 0)
            assertContentEquals(encoded, viaString)
        }
    }

    @Test
    fun encodeDecodeU8() {
        val data = listOf(bytes(0), bytes(0, 0, 0), bytes(0, 1, 2, 3, 4, 5), bytes(0xFF, 0xF8))
        for (s in data) {
            assertContentEquals(s, huffDecodeOrNull(huffEncode(s)))
        }
    }

    @Test
    fun encodeDecodeAllOctets() {
        val src = ByteArray(256) { it.toByte() }
        assertContentEquals(src, huffDecodeOrNull(huffEncode(src)))
    }

    @Test
    fun rejectsEosAndInvalidPadding() {
        assertNull(huffDecodeOrNull(bytes(0xff)))
        assertNull(huffDecodeOrNull(bytes(0xff, 0xff, 0xff, 0xff)))
        assertNull(huffDecodeOrNull(bytes(0)))
    }

    /** The table built at startup equals the reference's generated `DECODE_TABLE` (FNV-1a over its u16 entries). */
    @Test
    fun decodeTableMatchesReference() {
        val t = HUFF_DECODE_TABLE
        assertEquals(15 * 256, t.size)
        var h = -0x7ee3623b // 0x811c9dc5
        for (v in t) {
            h = (h xor (v ushr 8)) * 16777619
            h = (h xor (v and 0xff)) * 16777619
        }
        assertEquals(0xb7afad5c.toInt(), h)
    }
}
