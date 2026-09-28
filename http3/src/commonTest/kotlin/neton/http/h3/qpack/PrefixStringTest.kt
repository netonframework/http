package neton.http.h3.qpack

import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Huffman-decodes [src] with the shared HPACK code; null for an invalid string. */
private fun huffmanDecode(src: ByteArray): ByteArray? {
    val out = ByteArray(2 * src.size + 1)
    val n = decodeHuffman(src, 0, src.size, out)
    return if (n < 0) null else out.copyOf(n)
}

private fun huffmanEncode(src: ByteArray): ByteArray {
    val b = Buffer()
    PrefixString.encode(8, 0, src, b)
    // Drop the length prefix (one byte below 127, else PrefixInt's continuation bytes).
    val n = PrefixInt.decode(7, b).value.toInt()
    val out = b.readAll()
    assertEquals(n, out.size)
    return out
}

/**
 * The reference's `test_decode_single_value` / `test_encode_single_value` vectors (`symbol:hex`), extracted by
 * evaluating its byte expressions (RFC 7541 Appendix B codes, padded with ones).
 */
private const val DECODE_SINGLE = "48:07 49:0f 50:17 97:1f 99:27 101:2f 105:37 111:3f 115:47 116:4f 32:53 37:57 45:5b 46:5f 47:63 51:67 52:6b 53:6f 54:73 55:77 56:7b 57:7f 61:83 65:87 95:8b 98:8f 100:93 102:97 103:9b 104:9f 108:a3 109:a7 110:ab 112:af 114:b3 117:b7 58:b9 66:bb 67:bd 68:bf 69:c1 70:c3 71:c5 72:c7 73:c9 74:cb 75:cd 76:cf 77:d1 78:d3 79:d5 80:d7 81:d9 82:db 83:dd 84:df 85:e1 86:e3 87:e5 89:e7 106:e9 107:eb 113:ed 118:ef 119:f1 120:f3 121:f5 122:f7 38:f8ff 42:f9ff 44:faff 59:fbff 88:fcff 90:fdff 33:fe3f 34:fe7f 40:febf 41:feff 63:ff3f 39:ff5f 43:ff7f 124:ff9f 35:ffaf 62:ffbf 0:ffc7 36:ffcf 64:ffd7 91:ffdf 93:ffe7 126:ffef 94:fff3 125:fff7 60:fff9 96:fffb 123:fffd 92:fffe1f 195:fffe3f 208:fffe5f 128:fffe6f 130:fffe7f 131:fffe8f 162:fffe9f 184:fffeaf 194:fffebf 224:fffecf 226:fffedf 153:fffee7 161:fffeef 167:fffef7 172:fffeff 176:ffff07 177:ffff0f 179:ffff17 209:ffff1f 216:ffff27 217:ffff2f 227:ffff37 229:ffff3f 230:ffff47 129:ffff4b 132:ffff4f 133:ffff53 134:ffff57 136:ffff5b 146:ffff5f 154:ffff63 156:ffff67 160:ffff6b 163:ffff6f 164:ffff73 169:ffff77 170:ffff7b 173:ffff7f 178:ffff83 181:ffff87 185:ffff8b 186:ffff8f 187:ffff93 189:ffff97 190:ffff9b 196:ffff9f 198:ffffa3 228:ffffa7 232:ffffab 233:ffffaf 1:ffffb1 135:ffffb3 137:ffffb5 138:ffffb7 139:ffffb9 140:ffffbb 141:ffffbd 143:ffffbf 147:ffffc1 149:ffffc3 150:ffffc5 151:ffffc7 152:ffffc9 155:ffffcb 157:ffffcd 158:ffffcf 165:ffffd1 166:ffffd3 168:ffffd5 174:ffffd7 175:ffffd9 180:ffffdb 182:ffffdd 183:ffffdf 188:ffffe1 191:ffffe3 197:ffffe5 231:ffffe7 239:ffffe9 9:ffffeaff 142:ffffebff 144:ffffecff 145:ffffedff 148:ffffeeff 159:ffffefff 171:fffff0ff 206:fffff1ff 215:fffff2ff 225:fffff3ff 236:fffff4ff 237:fffff5ff 199:fffff67f 207:fffff6ff 234:fffff77f 235:fffff7ff 192:fffff83f 193:fffff87f 200:fffff8bf 201:fffff8ff 202:fffff93f 205:fffff97f 210:fffff9bf 213:fffff9ff 218:fffffa3f 219:fffffa7f 238:fffffabf 240:fffffaff 242:fffffb3f 243:fffffb7f 255:fffffbbf 203:fffffbdf 204:fffffbff 211:fffffc1f 212:fffffc3f 214:fffffc5f 221:fffffc7f 222:fffffc9f 223:fffffcbf 241:fffffcdf 244:fffffcff 245:fffffd1f 246:fffffd3f 247:fffffd5f 248:fffffd7f 250:fffffd9f 251:fffffdbf 252:fffffddf 253:fffffdff 254:fffffe1f 2:fffffe2f 3:fffffe3f 4:fffffe4f 5:fffffe5f 6:fffffe6f 7:fffffe7f 8:fffffe8f 11:fffffe9f 12:fffffeaf 14:fffffebf 15:fffffecf 16:fffffedf 17:fffffeef 18:fffffeff 19:ffffff0f 20:ffffff1f 21:ffffff2f 23:ffffff3f 24:ffffff4f 25:ffffff5f 26:ffffff6f 27:ffffff7f 28:ffffff8f 29:ffffff9f 30:ffffffaf 31:ffffffbf 127:ffffffcf 220:ffffffdf 249:ffffffef 10:fffffff3 13:fffffff7 22:fffffffb"

private const val ENCODE_SINGLE = "48:07 49:0f 50:17 97:1f 99:27 101:2f 105:37 111:3f 115:47 116:4f 32:53 37:57 45:5b 46:5f 47:63 51:67 52:6b 53:6f 54:73 55:77 56:7b 57:7f 61:83 65:87 95:8b 98:8f 100:93 102:97 103:9b 104:9f 108:a3 109:a7 110:ab 112:af 114:b3 117:b7 58:b9 66:bb 67:bd 68:bf 69:c1 70:c3 71:c5 72:c7 73:c9 74:cb 75:cd 76:cf 77:d1 78:d3 79:d5 80:d7 81:d9 82:db 83:dd 84:df 85:e1 86:e3 87:e5 89:e7 106:e9 107:eb 113:ed 118:ef 119:f1 120:f3 121:f5 122:f7 38:f8 42:f9 44:fa 59:fb 88:fc 90:fd 33:fe3f 34:fe7f 40:febf 41:feff 63:ff3f 39:ff5f 43:ff7f 124:ff9f 35:ffaf 62:ffbf 0:ffc7 36:ffcf 64:ffd7 91:ffdf 93:ffe7 126:ffef 94:fff3 125:fff7 60:fff9 96:fffb 123:fffd 92:fffe1f 195:fffe3f 208:fffe5f 128:fffe6f 130:fffe7f 131:fffe8f 162:fffe9f 184:fffeaf 194:fffebf 224:fffecf 226:fffedf 153:fffee7 161:fffeef 167:fffef7 172:fffeff 176:ffff07 177:ffff0f 179:ffff17 209:ffff1f 216:ffff27 217:ffff2f 227:ffff37 229:ffff3f 230:ffff47 129:ffff4b 132:ffff4f 133:ffff53 134:ffff57 136:ffff5b 146:ffff5f 154:ffff63 156:ffff67 160:ffff6b 163:ffff6f 164:ffff73 169:ffff77 170:ffff7b 173:ffff7f 178:ffff83 181:ffff87 185:ffff8b 186:ffff8f 187:ffff93 189:ffff97 190:ffff9b 196:ffff9f 198:ffffa3 228:ffffa7 232:ffffab 233:ffffaf 1:ffffb1 135:ffffb3 137:ffffb5 138:ffffb7 139:ffffb9 140:ffffbb 141:ffffbd 143:ffffbf 147:ffffc1 149:ffffc3 150:ffffc5 151:ffffc7 152:ffffc9 155:ffffcb 157:ffffcd 158:ffffcf 165:ffffd1 166:ffffd3 168:ffffd5 174:ffffd7 175:ffffd9 180:ffffdb 182:ffffdd 183:ffffdf 188:ffffe1 191:ffffe3 197:ffffe5 231:ffffe7 239:ffffe9 9:ffffea 142:ffffeb 144:ffffec 145:ffffed 148:ffffee 159:ffffef 171:fffff0 206:fffff1 215:fffff2 225:fffff3 236:fffff4 237:fffff5 199:fffff67f 207:fffff6ff 234:fffff77f 235:fffff7ff 192:fffff83f 193:fffff87f 200:fffff8bf 201:fffff8ff 202:fffff93f 205:fffff97f 210:fffff9bf 213:fffff9ff 218:fffffa3f 219:fffffa7f 238:fffffabf 240:fffffaff 242:fffffb3f 243:fffffb7f 255:fffffbbf 203:fffffbdf 204:fffffbff 211:fffffc1f 212:fffffc3f 214:fffffc5f 221:fffffc7f 222:fffffc9f 223:fffffcbf 241:fffffcdf 244:fffffcff 245:fffffd1f 246:fffffd3f 247:fffffd5f 248:fffffd7f 250:fffffd9f 251:fffffdbf 252:fffffddf 253:fffffdff 254:fffffe1f 2:fffffe2f 3:fffffe3f 4:fffffe4f 5:fffffe5f 6:fffffe6f 7:fffffe7f 8:fffffe8f 11:fffffe9f 12:fffffeaf 14:fffffebf 15:fffffecf 16:fffffedf 17:fffffeef 18:fffffeff 19:ffffff0f 20:ffffff1f 21:ffffff2f 23:ffffff3f 24:ffffff4f 25:ffffff5f 26:ffffff6f 27:ffffff7f 28:ffffff8f 29:ffffff9f 30:ffffffaf 31:ffffffbf 127:ffffffcf 220:ffffffdf 249:ffffffef 10:fffffff3 13:fffffff7 22:fffffffb"

/**
 * `test_encode_all_code_joined`: symbols 0..255 in order, Huffman-encoded, padded with ones (583 bytes). The
 * reference's `test_decode_all_code_joined` input is these bytes followed by `ffffff`, which completes the 30-bit
 * EOS code.
 */
private const val ALL_CODES = "ffc7fffd8fffffe2fffffe3fffffe4fffffe5fffffe6fffffe7fffffe8ffffeafffffff3fffffa7fffffabffffffdfffffebfffffecfffffedfffffeefffffefffffff0ffffff1ffffff2fffffffbfffffcffffffd3fffffd7fffffdbfffffdffffffe3fffffe7fffffebfffffed4fe3f9ffaffcabf1febfafefe7fdfd2cbb00089969b71d79fb9f7fff20ffbff3ff50ddbd7f061c58f265cd9f469d5af66dddbf871e5f9cff7ff7fffc3ff9ffe45fff4719242cb34e6e9d68a6a3d7dac426defe3cfaf7fffbfe7ffbffdffffffcfffe6ffff4bfff9ffffa3fffd3ffff53fffd5ffffb3fffeb7fffdaffffb7ffff73fffeeffffdeffffebffffbfffffd9ffffdbfffebffffe0ffffeeffffc3ffff8bffff1ffffe4fffee7fffb1ffff97fffd9ffffcdffff9fffffbffffdafffeeffff4ffffb7fffee7fffe8ffffd3fffdeffffd5fffeeffffbdffffe1fffdfffff7fffff5ffffecffff07fff87fffe0ffff17fffedffff87ffff77fffeffffeaffff8bfffe3ffff93ffff87fffcbffff37ffff1fffff83ffffe1fffebfffe3ffff3fffff2ffffa3ffffd9fffff17ffffc7fffff27ffffdefffffbffffff2fffff8fffffb7fff97fff8fffffe6fffffc1fffff87ffffe7fffffc5ffffe5fffe4ffff2fffffd1fffff4ffffffefffffe3fffffc9fffff97fffb3ffffcffffb7fffcdffff4ffff9ffffd1ffffcffffeaffffafffffddffffeffffff4fffff5fffffabffffa7ffffd7fffff9bffffecfffffb7fffff3fffffe8fffffd3fffffabfffff5fffffff7ffffecfffffdbfffffbbfffff7ffffff0fffffbbf"

private fun vectors(s: String): List<Pair<Int, ByteArray>> =
    s.split(' ').map { val (c, h) = it.split(':'); c.toInt() to hex(h) }

// Tests of `src/qpack/prefix_string/mod.rs` (5), `decode.rs` (3) and `encode.rs` (5).
class PrefixStringTest {
    // ---- mod.rs ----

    @Test
    fun codec_6() {
        val b = Buffer()
        PrefixString.encode(6, 0b01, "name without ref".encodeToByteArray(), b)
        assertContentEquals(bytes(0b0110_1100, 168, 116, 149, 79, 6, 76, 231, 181, 42, 88, 89, 127), b.peekAll())
        assertContentEquals("name without ref".encodeToByteArray(), PrefixString.decode(6, b))
    }

    @Test
    fun codec_8() {
        val b = Buffer()
        PrefixString.encode(8, 0b01, "name with ref".encodeToByteArray(), b)
        assertContentEquals(bytes(0b1000_1010, 168, 116, 149, 79, 6, 76, 234, 88, 89, 127), b.peekAll())
        assertContentEquals("name with ref".encodeToByteArray(), PrefixString.decode(8, b))
    }

    @Test
    fun codec_8_empty() {
        val b = Buffer()
        PrefixString.encode(8, 0b01, ByteArray(0), b)
        assertContentEquals(bytes(0b1000_0000), b.peekAll())
        assertContentEquals(ByteArray(0), PrefixString.decode(8, b))
    }

    @Test
    fun decode_non_huffman() {
        val b = Buffer().also { it.writeBytes(bytes(0b0100_0011, 'b'.code, 'a'.code, 'r'.code)) }
        assertContentEquals("bar".encodeToByteArray(), PrefixString.decode(6, b))
    }

    @Test
    fun decode_too_short() {
        val b = Buffer().also { it.writeBytes(bytes(0b0100_0011, 'b'.code, 'a'.code)) }
        assertEquals(PrefixStringError.UnexpectedEnd, assertFailsWith<PrefixStringException> { PrefixString.decode(6, b) }.error)
    }

    // ---- decode.rs ----
    // `test_read_bits` is not applicable: it tests `read_bits`, a helper of the reference's bit-by-bit decoder; the
    // Huffman decoder here is the shared table-driven one of HPACK (see PrefixString).

    @Test
    fun test_decode_single_value() {
        val decode = vectors(DECODE_SINGLE)
        val encode = vectors(ENCODE_SINGLE).toMap()
        assertEquals(256, decode.size)
        var padded = 0
        for ((code, input) in decode) {
            val exact = encode.getValue(code)
            if (input.size == exact.size) {
                assertContentEquals(bytes(code), huffmanDecode(input), "symbol $code")
            } else {
                // ⚖️ The reference's vector for this symbol ends with a whole byte of padding (8 bits of ones), which
                // RFC 7541 §5.2 requires to be treated as a decoding error; the reference accepts it.
                assertEquals(exact.size + 1, input.size)
                assertEquals(0xff.toByte(), input.last())
                assertEquals(null, huffmanDecode(input), "symbol $code with a padding byte")
                assertContentEquals(bytes(code), huffmanDecode(exact), "symbol $code")
                padded++
            }
        }
        assertEquals(18, padded)
    }

    @Test
    fun test_decode_all_code_joined() {
        val all = hex(ALL_CODES)
        // ⚖️ The reference's input ends with a complete EOS symbol (30 bits), which RFC 7541 §5.2 requires to be
        // treated as a decoding error; the reference accepts it and stops there.
        assertEquals(null, huffmanDecode(all + hex("ffffff")))
        // The same symbols with at most 7 bits of padding decode to 0..255.
        assertContentEquals(ByteArray(256) { it.toByte() }, huffmanDecode(all))
    }

    // ---- encode.rs ----
    // `test_set_bits` is not applicable: it tests `write_bits`, a helper of the reference's bit-by-bit encoder.

    @Test
    fun test_encode_single_value() {
        val encode = vectors(ENCODE_SINGLE)
        assertEquals(256, encode.size)
        for ((code, expected) in encode) assertContentEquals(expected, huffmanEncode(bytes(code)), "symbol $code")
    }

    @Test
    fun test_encode_all_code_joined() {
        assertContentEquals(hex(ALL_CODES), huffmanEncode(ByteArray(256) { it.toByte() }))
    }

    @Test
    fun byte_count_exact_when_bit_count_multiple_of_8() {
        val encoded = hex("8c2d4b70ddf45abefb4005db")
        val decoded = huffmanDecode(encoded)!!
        assertEquals(0xdb.toByte(), huffmanEncode(decoded).last())
    }

    @Test
    fun byte_() {
        val encoded = hex("5592beff4836cb86373d68cac961cededce5fc")
        val decoded = huffmanDecode(encoded)!!
        assertEquals(0xfc.toByte(), huffmanEncode(decoded).last())
    }

    // ---- Beyond the reference's tests ----

    @Test
    fun invalidHuffmanIsAnError() {
        // An EOS code inside the string.
        val b = Buffer().also { it.writeBytes(bytes(0b1000_0100, 0xff, 0xff, 0xff, 0xff)) }
        assertEquals(PrefixStringError.HuffmanDecoding, assertFailsWith<PrefixStringException> { PrefixString.decode(8, b) }.error)
    }

    @Test
    fun lengthBeyondTheInputIsUnexpectedEnd() {
        val b = Buffer()
        PrefixInt.encode(7, 0, Long.MAX_VALUE, b)
        assertEquals(PrefixStringError.UnexpectedEnd, assertFailsWith<PrefixStringException> { PrefixString.decode(8, b) }.error)
    }
}
