package neton.http.header

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port of http 1.5.0 `tests/header_map_fuzz.rs`: random sequences of insert / append / remove are applied both to a
 * [HeaderMap] and to a simple reference model (a hash map of value lists), and the two are compared after every step.
 *
 * Same generator as the reference: 5..499 steps per case, per-case weights 1..9 for insert / remove / append, names
 * half standard and half random 24-character strings, values random 70-character strings, removes and appends biased
 * towards existing names. QuickCheck's random seeds are replaced by fixed [Random] seeds; the steps are checked as
 * they are generated instead of being generated first (the same comparisons, without cloning the model per step).
 */
class HeaderMapFuzzTest {

    @Test
    fun headerMapFuzz() {
        for (seed in 0 until 160) Fuzz(Random(seed), HeaderMap()).run()
    }

    /** The same fuzz with the map forced into the Red (SipHash) state from the start. */
    @Test
    fun headerMapFuzzRed() {
        for (seed in 1000 until 1060) {
            val map = HeaderMap<HeaderValue>()
            map.forceRedForTest()
            Fuzz(Random(seed), map).run()
            assertEquals(DANGER_RED, map.danger)
        }
    }

    private class Fuzz(private val rng: Random, private val map: HeaderMap<HeaderValue>) {
        private val model = LinkedHashMap<HeaderName, MutableList<HeaderValue>>()

        fun run() {
            val num = rng.nextInt(5, 500)
            val insert = rng.nextInt(1, 10)
            val remove = rng.nextInt(1, 10)
            val append = rng.nextInt(1, 10)
            repeat(num) {
                var n = rng.nextInt(0, insert + remove + append)
                when {
                    n < insert -> genInsert()
                    else -> {
                        n -= insert
                        if (n < remove) genRemove() else genAppend()
                    }
                }
                assertIdentical()
            }
        }

        private fun genInsert() {
            val name = genName(4)
            val value = genHeaderValue()
            val old = model.put(name, mutableListOf(value))?.firstOrNull()
            assertEquals(old, map.insert(name, value))
        }

        private fun genRemove() {
            val name = genName(-4)
            val expected = model.remove(name)?.firstOrNull()
            // Just to help track the state, load all associated values.
            map.getAll(name).toList()
            assertEquals(expected, map.remove(name))
        }

        private fun genAppend() {
            val name = genName(-5)
            val value = genHeaderValue()
            val values = model.getOrPut(name) { ArrayList() }
            val ret = values.isNotEmpty()
            values.add(value)
            assertEquals(ret, map.append(name, value))
        }

        /** Negative weights favour an existing name. */
        private fun genName(weight: Int): HeaderName {
            var existing = rng.nextInt(if (weight < 0) -weight else weight) == 0
            if (weight < 0) existing = !existing
            if (existing && model.isNotEmpty()) {
                val n = rng.nextInt(0, model.size)
                return model.keys.elementAt(n)
            }
            return genHeaderName()
        }

        private fun genHeaderName(): HeaderName =
            if (rng.nextInt(2) == 0) STANDARD_HEADERS[rng.nextInt(STANDARD_HEADERS.size)]
            else HeaderName.fromBytes(genString(1, 25).encodeToByteArray())

        private fun genHeaderValue(): HeaderValue = HeaderValue.fromBytes(genString(0, 70).encodeToByteArray())

        // As the reference: `(min..max).map(..)` yields max - min characters.
        private fun genString(min: Int, max: Int): String {
            val sb = StringBuilder(max - min)
            repeat(max - min) { sb.append(CHARS[rng.nextInt(CHARS.length)]) }
            return sb.toString()
        }

        private fun assertIdentical() {
            assertEquals(model.size, map.keysLen())
            assertEquals(model.values.sumOf { it.size }, map.len())
            for ((key, values) in model) {
                assertEquals(values.first(), map[key])
                assertEquals(values, map.getAll(key).toList())
            }
        }
    }

    private companion object {
        const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVabcdefghilpqrstuvwxyz----"

        val STANDARD_HEADERS: List<HeaderName> = with(HeaderName) {
            listOf(
                ACCEPT, ACCEPT_CHARSET, ACCEPT_ENCODING, ACCEPT_LANGUAGE, ACCEPT_RANGES,
                ACCESS_CONTROL_ALLOW_CREDENTIALS, ACCESS_CONTROL_ALLOW_HEADERS, ACCESS_CONTROL_ALLOW_METHODS,
                ACCESS_CONTROL_ALLOW_ORIGIN, ACCESS_CONTROL_EXPOSE_HEADERS, ACCESS_CONTROL_MAX_AGE,
                ACCESS_CONTROL_REQUEST_HEADERS, ACCESS_CONTROL_REQUEST_METHOD, AGE, ALLOW, ALT_SVC, AUTHORIZATION,
                CACHE_CONTROL, CACHE_STATUS, CDN_CACHE_CONTROL, CONNECTION, CONTENT_DISPOSITION, CONTENT_ENCODING,
                CONTENT_LANGUAGE, CONTENT_LENGTH, CONTENT_LOCATION, CONTENT_RANGE, CONTENT_SECURITY_POLICY,
                CONTENT_SECURITY_POLICY_REPORT_ONLY, CONTENT_TYPE, COOKIE, DNT, DATE, ETAG, EXPECT, EXPIRES,
                FORWARDED, FROM, HOST, IF_MATCH, IF_MODIFIED_SINCE, IF_NONE_MATCH, IF_RANGE, IF_UNMODIFIED_SINCE,
                LAST_MODIFIED, LINK, LOCATION, MAX_FORWARDS, ORIGIN, PRAGMA, PROXY_AUTHENTICATE, PROXY_AUTHORIZATION,
                PUBLIC_KEY_PINS, PUBLIC_KEY_PINS_REPORT_ONLY, RANGE, REFERER, REFERRER_POLICY, REFRESH, RETRY_AFTER,
                SEC_WEBSOCKET_ACCEPT, SEC_WEBSOCKET_EXTENSIONS, SEC_WEBSOCKET_KEY, SEC_WEBSOCKET_PROTOCOL,
                SEC_WEBSOCKET_VERSION, SERVER, SET_COOKIE, STRICT_TRANSPORT_SECURITY, TE, TRAILER, TRANSFER_ENCODING,
                UPGRADE, UPGRADE_INSECURE_REQUESTS, USER_AGENT, VARY, VIA, WARNING, WWW_AUTHENTICATE,
                X_CONTENT_TYPE_OPTIONS, X_DNS_PREFETCH_CONTROL, X_FRAME_OPTIONS, X_XSS_PROTECTION,
            )
        }
    }
}
