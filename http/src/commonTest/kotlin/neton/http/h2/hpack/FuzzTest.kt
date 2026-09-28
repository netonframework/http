package neton.http.h2.hpack

import neton.http.Method
import neton.http.StatusCode
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Ported from h2 0.4.19 `src/hpack/test/fuzz.rs`: randomized encode/decode round trips with table size changes.
// The reference's `hpack_fuzz` runs 100 QuickCheck cases from random seeds and `hpack_fuzz_seeded` replays one seed;
// here both use fixed seeds (kotlin.random.Random), so failures are reproducible. The shape of the random input is
// the reference's: 2000 source headers, 40..500 headers in frames, 1 in 20 frames with two resizes and 3 in 20 with
// one, a skewed pick of source headers, nameless fields only after a named field.

private const val MAX_CHUNK = 2 * 1024

private class HeaderFrame(val resizes: MutableList<Int>, val headers: MutableList<Header>)

private class FuzzHpack(seed: Long) {
    val frames = mutableListOf<HeaderFrame>()

    init {
        val rng = Random(seed)

        // Generate a bunch of source headers.
        val source = List(2000) { genHeader(rng) }

        // Actual test run headers.
        val num = rng.nextInt(40, 500)
        var added = 0
        val skew = rng.nextInt(1, 5)

        // Rough number of headers to add.
        while (added < num) {
            val frame = HeaderFrame(mutableListOf(), mutableListOf())
            when (rng.nextInt(0, 20)) {
                0 -> {
                    // Two resizes.
                    val high = rng.nextInt(128, MAX_CHUNK * 2)
                    val low = rng.nextInt(0, high)
                    frame.resizes.add(low)
                    frame.resizes.add(high)
                }
                1, 2, 3 -> frame.resizes.add(rng.nextInt(128, MAX_CHUNK * 2))
                else -> {}
            }

            var isNameRequired = true
            repeat(rng.nextInt(1, (num - added) + 1)) {
                var x = rng.nextDouble(0.0, 1.0)
                var p = 1.0
                repeat(skew) { p *= x }
                x = p
                val i = (x * source.size).toInt()
                val header = source[i]
                when (header) {
                    is Header.Value -> if (isNameRequired) return@repeat
                    is Header.Field -> isNameRequired = false
                    // Pseudo-headers can't be followed by a header with no name.
                    else -> isNameRequired = true
                }
                frame.headers.add(header)
                added++
            }
            frames.add(frame)
        }
    }

    fun run() {
        val expect = ArrayDeque<Header>()
        val encoder = Encoder()
        val decoder = Decoder()

        for (frame in frames) {
            // Build the expected headers, such that decoded headers always include a name.
            var prevName: HeaderName? = null
            for (header in frame.headers) {
                if (header is Header.Value) {
                    expect.addLast(Header.Field(checkNotNull(prevName) { "previous header name" }, header.value))
                } else {
                    prevName = (header as? Header.Field)?.name
                    expect.addLast(header)
                }
            }

            val buf = Buffer()

            frame.resizes.maxOrNull()?.let { decoder.queueSizeUpdate(it) }

            // Apply the resizes.
            for (resize in frame.resizes) encoder.updateMaxSize(resize)

            encoder.encode(frame.headers, buf)

            // Decode the chunk.
            val wire = buf.peekAll()
            val err = decoder.decode(wire, 0, wire.size) { h ->
                assertEquals(expect.removeFirst(), h)
                true
            }
            assertNull(err, "full decode")
            assertEquals(wire.size, decoder.consumed)
        }

        assertEquals(0, expect.size)
    }
}

private fun genHeader(g: Random): Header {
    if (g.nextInt(10) == 0) {
        return when (g.nextInt(0, 5)) {
            0 -> Header.Authority(genString(g, 4, 20))
            1 -> Header.Method(
                when (g.nextInt(0, 6)) {
                    0 -> Method.GET
                    1 -> Method.POST
                    2 -> Method.PUT
                    3 -> Method.PATCH
                    4 -> Method.DELETE
                    else -> {
                        val n = g.nextInt(3, 7)
                        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                        Method.fromBytes(ByteArray(n) { letters[g.nextInt(letters.length)].code.toByte() })
                    }
                },
            )
            2 -> Header.Scheme(if (g.nextInt(0, 2) == 0) "http" else "https")
            3 -> Header.Path(
                when (g.nextInt(0, 100)) {
                    0 -> "/"
                    1 -> "/index.html"
                    else -> genString(g, 2, 20)
                },
            )
            else -> Header.Status(StatusCode.fromU16((g.nextInt(0, 65536) % 500) + 100))
        }
    }
    val name = if (g.nextInt(10) == 0) null else genHeaderName(g)
    val value = genHeaderValue(g)
    if (g.nextInt(30) == 0) value.isSensitive = true
    return if (name == null) Header.Value(value) else Header.Field(name, value)
}

private val STANDARD_NAMES = listOf(
    HeaderName.ACCEPT, HeaderName.ACCEPT_CHARSET, HeaderName.ACCEPT_ENCODING, HeaderName.ACCEPT_LANGUAGE,
    HeaderName.ACCEPT_RANGES, HeaderName.ACCESS_CONTROL_ALLOW_CREDENTIALS, HeaderName.ACCESS_CONTROL_ALLOW_HEADERS,
    HeaderName.ACCESS_CONTROL_ALLOW_METHODS, HeaderName.ACCESS_CONTROL_ALLOW_ORIGIN,
    HeaderName.ACCESS_CONTROL_EXPOSE_HEADERS, HeaderName.ACCESS_CONTROL_MAX_AGE,
    HeaderName.ACCESS_CONTROL_REQUEST_HEADERS, HeaderName.ACCESS_CONTROL_REQUEST_METHOD, HeaderName.AGE,
    HeaderName.ALLOW, HeaderName.ALT_SVC, HeaderName.AUTHORIZATION, HeaderName.CACHE_CONTROL, HeaderName.CONNECTION,
    HeaderName.CONTENT_DISPOSITION, HeaderName.CONTENT_ENCODING, HeaderName.CONTENT_LANGUAGE,
    HeaderName.CONTENT_LENGTH, HeaderName.CONTENT_LOCATION, HeaderName.CONTENT_RANGE,
    HeaderName.CONTENT_SECURITY_POLICY, HeaderName.CONTENT_SECURITY_POLICY_REPORT_ONLY, HeaderName.CONTENT_TYPE,
    HeaderName.COOKIE, HeaderName.DNT, HeaderName.DATE, HeaderName.ETAG, HeaderName.EXPECT, HeaderName.EXPIRES,
    HeaderName.FORWARDED, HeaderName.FROM, HeaderName.HOST, HeaderName.IF_MATCH, HeaderName.IF_MODIFIED_SINCE,
    HeaderName.IF_NONE_MATCH, HeaderName.IF_RANGE, HeaderName.IF_UNMODIFIED_SINCE, HeaderName.LAST_MODIFIED,
    HeaderName.LINK, HeaderName.LOCATION, HeaderName.MAX_FORWARDS, HeaderName.ORIGIN, HeaderName.PRAGMA,
    HeaderName.PROXY_AUTHENTICATE, HeaderName.PROXY_AUTHORIZATION, HeaderName.PUBLIC_KEY_PINS,
    HeaderName.PUBLIC_KEY_PINS_REPORT_ONLY, HeaderName.RANGE, HeaderName.REFERER, HeaderName.REFERRER_POLICY,
    HeaderName.REFRESH, HeaderName.RETRY_AFTER, HeaderName.SERVER, HeaderName.SET_COOKIE,
    HeaderName.STRICT_TRANSPORT_SECURITY, HeaderName.TE, HeaderName.TRAILER, HeaderName.TRANSFER_ENCODING,
    HeaderName.USER_AGENT, HeaderName.UPGRADE, HeaderName.UPGRADE_INSECURE_REQUESTS, HeaderName.VARY,
    HeaderName.VIA, HeaderName.WARNING, HeaderName.WWW_AUTHENTICATE, HeaderName.X_CONTENT_TYPE_OPTIONS,
    HeaderName.X_DNS_PREFETCH_CONTROL, HeaderName.X_FRAME_OPTIONS, HeaderName.X_XSS_PROTECTION,
)

private fun genHeaderName(g: Random): HeaderName =
    if (g.nextInt(2) == 0) STANDARD_NAMES[g.nextInt(STANDARD_NAMES.size)]
    else HeaderName.fromBytes(genString(g, 1, 25).encodeToByteArray())

private fun genHeaderValue(g: Random): HeaderValue = HeaderValue.fromBytes(genString(g, 0, 70).encodeToByteArray())

/** Like the reference: `max - min` characters (not a random length). */
private fun genString(g: Random, min: Int, max: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVabcdefghilpqrstuvwxyz----"
    val sb = StringBuilder(max - min)
    repeat(max - min) { sb.append(chars[g.nextInt(chars.length)]) }
    return sb.toString()
}

class FuzzTest {
    /** `hpack_fuzz`: 100 cases, here from the fixed seeds 0..99. */
    @Test
    fun hpackFuzz() {
        for (seed in 0L until 100L) FuzzHpack(seed).run()
    }

    /** `hpack_fuzz_seeded` (commented out in the reference, ready to replay one seed): replays a single seed. */
    @Test
    fun hpackFuzzSeeded() {
        FuzzHpack(0x5eed_2024_0419L).run()
    }
}
