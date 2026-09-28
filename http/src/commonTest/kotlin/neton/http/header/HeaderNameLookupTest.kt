package neton.http.header

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [HeaderName.tryFromBytes] looks standard names up by direct comparison (letters in either case, `-` exactly) before
 * the validating path; [HeaderName.tryFromStr] still takes the table-and-hash path. Both must agree on every input.
 */
class HeaderNameLookupTest {
    private val standard = listOf(
        "host", "accept", "accept-encoding", "accept-language", "cache-control", "content-length", "content-type",
        "cookie", "user-agent", "upgrade-insecure-requests", "x-dns-prefetch-control", "www-authenticate", "te",
    )

    private fun latin1(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toChar().toString() }

    private fun assertAgree(b: ByteArray) {
        val fast = HeaderName.tryFromBytes(b)
        val slow = HeaderName.tryFromStr(latin1(b))
        val what = b.joinToString(",") { (it.toInt() and 0xff).toString() }
        if (slow == null) assertNull(fast, what) else {
            assertEquals(slow, fast, what)
            if (slow.standardIndex >= 0) assertSame(slow, fast, what)
        }
    }

    @Test
    fun caseVariantsOfStandardNamesAreTheSharedConstants() {
        val rng = Random(11)
        for (name in standard) {
            repeat(50) {
                val b = name.encodeToByteArray()
                for (i in b.indices) if (b[i] in 'a'.code.toByte()..'z'.code.toByte() && rng.nextBoolean()) b[i] = (b[i] - 32).toByte()
                val found = HeaderName.tryFromBytes(b)
                assertSame(HeaderName.tryFromStr(name), found, latin1(b))
                assertAgree(b)
            }
        }
    }

    @Test
    fun nearMissesAgreeWithTheValidatingPath() {
        // `-` xor 0x20 is CR: case folding must not make "content\rlength" a standard name.
        assertNull(HeaderName.tryFromBytes("content\rlength".encodeToByteArray()))
        assertNull(HeaderName.tryFromBytes("x\rdns-prefetch-control".encodeToByteArray()))
        val rng = Random(12)
        repeat(20_000) {
            val b = standard[rng.nextInt(standard.size)].encodeToByteArray()
            val i = rng.nextInt(b.size)
            b[i] = when (rng.nextInt(4)) {
                0 -> (b[i].toInt() xor 0x20).toByte()
                1 -> (b[i].toInt() xor (1 shl rng.nextInt(8))).toByte()
                2 -> rng.nextInt(256).toByte()
                else -> "_^~|!#.0-".random(rng).code.toByte()
            }
            assertAgree(b)
        }
    }

    @Test
    fun randomBytesAgree() {
        val rng = Random(13)
        repeat(20_000) { assertAgree(ByteArray(rng.nextInt(1, 30)) { rng.nextInt(256).toByte() }) }
    }
}
