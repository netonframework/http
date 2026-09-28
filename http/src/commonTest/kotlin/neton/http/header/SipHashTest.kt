package neton.http.header

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * SipHash-1-3 against the 64 test vectors of Rust's libcore (`library/coretests/tests/hash/sip.rs`,
 * `test_siphash_1_3`): key bytes 00..0f (k0 = 0x0706050403020100, k1 = 0x0f0e0d0c0b0a0908), message i = bytes
 * 0, 1, ..., i-1, expected output as 8 little-endian bytes. These are the reference implementation's own vectors for
 * `SipHasher13`, the hasher behind `RandomState`. Lengths 0..63 cover every tail length and 0..7 full blocks.
 */
class SipHashTest {
    private val k0 = 0x0706050403020100L
    private val k1 = 0x0f0e0d0c0b0a0908L

    @Test
    fun testSiphash13Vectors() {
        val buf = ByteArray(64) { it.toByte() }
        for (t in 0 until 64) {
            val expected = leHex(VECTORS[t])
            assertEquals(expected, sipHash13(k0, k1, buf, 0, t), "vector $t")
            // The inline byte-source form gives the same result.
            assertEquals(expected, sipHash13(k0, k1, t) { buf[it].toInt() and 0xff }, "vector $t (inline)")
        }
    }

    @Test
    fun offsetAndLengthSelectTheInput() {
        val buf = ByteArray(80) { (it - 5).toByte() } // bytes 0..63 start at offset 5
        for (t in 0 until 64) assertEquals(leHex(VECTORS[t]), sipHash13(k0, k1, buf, 5, t))
    }

    @Test
    fun stringPathMatchesLowercaseBytes() {
        // HeaderMap hashes a string key through HEADER_CHARS on the fly; it must equal the hash of the stored name.
        for (s in listOf("Content-Type", "X-CUSTOM-header", "a", "ACCESS-CONTROL-ALLOW-CREDENTIALS")) {
            val name = HeaderName.fromStr(s)
            val viaBytes = sipHash13(k0, k1, name.bytes)
            val viaString = sipHash13(k0, k1, s.length) { HEADER_CHARS[charToByte(s[it].code)].toInt() }
            assertEquals(viaBytes, viaString, s)
        }
    }

    @Test
    fun keysChangeTheHash() {
        val b = "host".encodeToByteArray()
        assertNotEquals(sipHash13(k0, k1, b), sipHash13(k0 + 1, k1, b))
        assertNotEquals(sipHash13(k0, k1, b), sipHash13(k0, k1 + 1, b))
    }

    private fun leHex(s: String): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or s.substring(2 * i, 2 * i + 2).toLong(16)
        return v
    }

    private companion object {
        val VECTORS = arrayOf(
            "dcc40f055801acab",
            "93ca577df39bf4c9",
            "4dd4c74d029bcb82",
            "fbf7dde7b80af88b",
            "2883d388605775cf",
            "673b53492fd5f9de",
            "a7229fc5502b0dc5",
            "4011b19b987d92d3",
            "8e9a298d11959036",
            "e43d066cb38ea425",
            "7f09ff92ee85de79",
            "52c34df9c118c170",
            "a2d9b457b184a378",
            "a7ff29120c766f30",
            "345df9c011a15a60",
            "5699512a6dd820d3",
            "668b907d1add4fcc",
            "0cd8db639068f29c",
            "3ee673b49c38fc8f",
            "1c7d298de59d1ff2",
            "40e0cca6462fdcc0",
            "44f8452bfeab92b9",
            "2e8720a39b7bfe7f",
            "23c1e6da7f0e5a52",
            "8c9c3467b2ae64f4",
            "79095b702859cd45",
            "a51399cae3353e3a",
            "353bde4a4ec71da9",
            "0dd06cef02ed0bfb",
            "f4e1b14ab43cd988",
            "63e6c543d6110f54",
            "bcd1218c1fdd7023",
            "0db6a7166c7b1581",
            "bff98f7ae5b9544d",
            "3e752a1f78129f75",
            "916b18bfbea3a1ce",
            "0662a2add308f52c",
            "5730c3a32d1c10b6",
            "a1363aae9674f4b3",
            "9283107b54576b62",
            "3115e4993236d2c1",
            "44d91a3f92c17c66",
            "258813c8fe4f7065",
            "a64989c2d180f224",
            "6b87f8faed1ccac2",
            "9621049ffc4b16c2",
            "23d6b168939c6ea1",
            "fd14518b9c16fb49",
            "464c07dff843319f",
            "b386cc1224affdc6",
            "8f09520ad149af7e",
            "9a2f299d5513f31c",
            "121ff4a2dd304ac4",
            "d01ea74389e9fa36",
            "e6bcf0734cb38f31",
            "80e9a77036bf7aa2",
            "756d3c24dbc0bcb4",
            "1315b7fd52d8f823",
            "088a7da64d5f038f",
            "48f1e8b7e5d09cd8",
            "ee44a6f7bce6f4f6",
            "f237180fd89ac5ae",
            "e094664b15f6b2c3",
            "a8b3bbb76290199d",
        )
    }
}
