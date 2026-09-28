package neton.http.h3.proto

import neton.io.bytes.Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Port of h3 0.0.8's fuzz target `fuzz/fuzz_targets/fuzz_varint.rs`: `VarInt::decode` on arbitrary bytes must not
// panic. The reference runs libFuzzer from `fuzz/corpus/fuzz_varint`; here the inputs are that corpus (116 files) and
// inputs from fixed seeds (reproducible), and each result is also checked against what decoding promises:
// - a result is INCOMPLETE exactly when the input is shorter than the length the first byte announces;
// - otherwise exactly that many bytes are consumed and the value is below 2^62;
// - the value re-encodes to its shortest form, which decodes to the same value and is not longer than the input's form;
// - the same bytes inside a larger array decode the same way (no read outside the range).

private const val CASES = 20_000

/**
 * The corpus: for each file, its first 8 bytes (hex) and its length. `VarInt::decode` reads at most 8 bytes, so the
 * file's first 8 bytes and whether it has more decide the result; longer files are rebuilt with zero padding.
 */
private val CORPUS: List<Pair<String, Int>> = listOf(
    "9f01002f00" to 5, "9f012f00" to 4, "8000000000000000" to 75, "f341455300000000" to 256,
    "ff00060001000000" to 2736, "ffffffffffffffff" to 2416, "ffffffffffffffff" to 1984, "8100000000000000" to 8,
    "809080f700" to 5, "ff00001b000000c0" to 3520, "f3afaeaec0ff0000" to 3232, "ff0d000000000000" to 976,
    "ffffffffffffffff" to 1728, "4d410404" to 4, "f4374301ac528729" to 3552, "f34f1493670000ff" to 480,
    "82ff000018000000" to 8, "ff00001b140d4e43" to 2848, "4c4104044db60006" to 9, "ff00001715000008" to 1920,
    "ff00400a00000000" to 3712, "ffffffffffffffff" to 2752, "fd5bb711c029ef00" to 2032, "ce00000000000003" to 11,
    "ff00f7" to 3, "f32b0000ff000000" to 32, "b9de95ea4a53c7f7" to 2016, "83ff0000000000" to 7,
    "ff00001715000008" to 1968, "82ff0000000000fe" to 8, "4c4104044db60006" to 21, "fa374301ac528729" to 4080,
    "aa" to 1, "bfb000001c1c1c1c" to 2976, "f32b000000000000" to 512, "82ff0097ff011701" to 11,
    "f300000000000000" to 135, "f3ffffffffffffff" to 416, "fff7" to 2, "ce00000000000000" to 20,
    "ff00001715000008" to 1712, "ff00001715000008" to 1824, "a200000000140000" to 27, "f30000009d000000" to 3568,
    "ff00001715000008" to 1632, "82ff0001000000ff" to 15, "ff15000000000000" to 23, "f30000009d000000" to 3728,
    "fa31313131313131" to 3744, "f300a2002d000000" to 128, "f7" to 1, "c0ff00001916" to 6,
    "c0ff00001b54" to 6, "9f6601003300" to 6, "ff2b000000171500" to 1136, "cf00000000000000" to 19,
    "4d41" to 2, "8000000000000000" to 95, "f300000000000000" to 36, "a200000000000000" to 267,
    "f3414c4c190000ff" to 64, "b78200001b140d4e" to 2688, "f300a20000000000" to 240, "f300000000000000" to 32,
    "ffffffffffffffff" to 1010, "ff00000000" to 5, "ff0d000000000000" to 928, "fa374301ac528729" to 3760,
    "a2ff00f7fb" to 5, "f300000000000000" to 31, "ce03670200000080" to 11, "ce00000000000000" to 23,
    "40" to 1, "f300a20000000000" to 784, "ff02001b000000c0" to 3536, "82ff00001700f3" to 7,
    "ffffffffffffffff" to 1520, "4c4104044db60006" to 13, "ffffffffffffffff" to 1696, "fd5bb711c02b0000" to 368,
    "ce03000200000080" to 8, "09" to 1, "ffffffffffffffff" to 1328, "f3414c4c2b0000ff" to 4064,
    "f300a20000000000" to 160, "c0ff00001d142ef9" to 1505, "b711c029ff004085" to 3824, "ff00001715000008" to 2048,
    "f4fdffffff3643db" to 3632, "ff0000c500" to 5, "ff00001715000008" to 1648, "fa374301ac528729" to 2784,
    "8200000000000000" to 43, "ffffffffffffffff" to 1536, "8200500800000059" to 9, "ff00001715000008" to 1600,
    "82000000000000bd" to 27, "ffffffffffffffff" to 2768, "ffffff54010000" to 7, "f3ff00001b000000" to 672,
    "ffffffffffffffff" to 1776, "f300000100000000" to 48, "92ff0000170000" to 7, "ff1b000000000000" to 2000,
    "ff00001715000008" to 336, "8200000000000000" to 15, "a200000000000000" to 163, "f30000009d000000" to 224,
    "ff00001715000008" to 1952, "f300000000000000" to 35, "cf00000000000000" to 523, "ffffffffffffffff" to 1344,
    "8000000000000000" to 555, "4c4104044db600" to 7, "cf00000000000000" to 59, "ce00000000000003" to 12,
)

class VarIntFuzzTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun check(input: ByteArray) {
        val announced = if (input.isEmpty()) 1 else VarInt.encodedSize(input[0])
        val x = VarInt.decode(input, 0, input.size)
        if (input.size < announced) {
            assertEquals(VarInt.INCOMPLETE, x)
        } else {
            assertTrue(x in 0..VarInt.MAX, "value $x")
            val shortest = Buffer().also { VarInt.encode(x, it) }.readAll()
            assertTrue(shortest.size <= announced)
            assertEquals(x, VarInt.decode(shortest, 0, shortest.size))
        }
        // Through a Buffer: consumes exactly the announced length, or nothing.
        val buf = Buffer()
        buf.writeBytes(input)
        assertEquals(x, VarInt.decode(buf))
        assertEquals(if (x < 0) input.size else input.size - announced, buf.readableBytes)
        // Inside a larger array.
        val noise = Random(input.contentHashCode()).nextBytes(input.size + 16)
        input.copyInto(noise, 7)
        assertEquals(x, VarInt.decode(noise, 7, 7 + input.size))
    }

    @Test
    fun corpus() {
        assertEquals(116, CORPUS.size)
        for ((prefix, len) in CORPUS) {
            val head = hex(prefix)
            check(head.copyOf(minOf(len, 16)))
        }
    }

    @Test
    fun seeded() {
        val random = Random(0x5eed)
        repeat(CASES) {
            val n = random.nextInt(0, 12)
            check(random.nextBytes(n))
        }
    }
}
