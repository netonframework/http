package neton.http.h1.parse

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The word-at-a-time scanners against a plain byte loop over the same byte classes. */
class ScanTest {
    private fun naive(buf: ByteArray, pos: Int, end: Int, ok: (Int) -> Boolean): Int {
        var p = pos
        while (p < end && ok(buf[p].toInt() and 0xFF)) p++
        return p
    }

    @Test
    fun scannersMatchAByteLoop() {
        val rng = Random(31)
        val tchars = "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        repeat(20_000) {
            // Mostly token bytes, so runs are long, with a stop byte somewhere (or none).
            val buf = ByteArray(rng.nextInt(0, 40)) {
                if (rng.nextInt(12) == 0) rng.nextInt(256).toByte() else tchars[rng.nextInt(tchars.length)].code.toByte()
            }
            val pos = if (buf.isEmpty()) 0 else rng.nextInt(0, buf.size + 1)
            val end = rng.nextInt(pos, buf.size + 1)
            val what = "${buf.toList()} [$pos, $end)"
            assertEquals(naive(buf, pos, end, ::isHeaderNameToken), scanHeaderName(buf, pos, end), "name $what")
            assertEquals(naive(buf, pos, end, ::isHeaderValueToken), scanHeaderValue(buf, pos, end), "value $what")
            assertEquals(naive(buf, pos, end, ::isUriToken), scanUri(buf, pos, end), "uri $what")
        }
    }
}
