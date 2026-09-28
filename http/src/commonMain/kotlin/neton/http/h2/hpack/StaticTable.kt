package neton.http.h2.hpack

import neton.http.StatusCode
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.Method as HttpMethod

// The HPACK static table (RFC 7541 Appendix A), 61 entries: `get_static` in the reference's `decoder.rs` and
// `index_static` in its `table.rs`.

/** Number of static table entries; dynamic indices start at `STATIC_TABLE_LEN + 1` (the reference's `DYN_OFFSET`). */
internal const val STATIC_TABLE_LEN = 61

/** Names of the regular static entries 15..61, in index order. */
private val STATIC_FIELD_NAMES: Array<HeaderName> = arrayOf(
    HeaderName.ACCEPT_CHARSET,              // 15
    HeaderName.ACCEPT_ENCODING,             // 16
    HeaderName.ACCEPT_LANGUAGE,             // 17
    HeaderName.ACCEPT_RANGES,               // 18
    HeaderName.ACCEPT,                      // 19
    HeaderName.ACCESS_CONTROL_ALLOW_ORIGIN, // 20
    HeaderName.AGE,                         // 21
    HeaderName.ALLOW,                       // 22
    HeaderName.AUTHORIZATION,               // 23
    HeaderName.CACHE_CONTROL,               // 24
    HeaderName.CONTENT_DISPOSITION,         // 25
    HeaderName.CONTENT_ENCODING,            // 26
    HeaderName.CONTENT_LANGUAGE,            // 27
    HeaderName.CONTENT_LENGTH,              // 28
    HeaderName.CONTENT_LOCATION,            // 29
    HeaderName.CONTENT_RANGE,               // 30
    HeaderName.CONTENT_TYPE,                // 31
    HeaderName.COOKIE,                      // 32
    HeaderName.DATE,                        // 33
    HeaderName.ETAG,                        // 34
    HeaderName.EXPECT,                      // 35
    HeaderName.EXPIRES,                     // 36
    HeaderName.FROM,                        // 37
    HeaderName.HOST,                        // 38
    HeaderName.IF_MATCH,                    // 39
    HeaderName.IF_MODIFIED_SINCE,           // 40
    HeaderName.IF_NONE_MATCH,               // 41
    HeaderName.IF_RANGE,                    // 42
    HeaderName.IF_UNMODIFIED_SINCE,         // 43
    HeaderName.LAST_MODIFIED,               // 44
    HeaderName.LINK,                        // 45
    HeaderName.LOCATION,                    // 46
    HeaderName.MAX_FORWARDS,                // 47
    HeaderName.PROXY_AUTHENTICATE,          // 48
    HeaderName.PROXY_AUTHORIZATION,         // 49
    HeaderName.RANGE,                       // 50
    HeaderName.REFERER,                     // 51
    HeaderName.REFRESH,                     // 52
    HeaderName.RETRY_AFTER,                 // 53
    HeaderName.SERVER,                      // 54
    HeaderName.SET_COOKIE,                  // 55
    HeaderName.STRICT_TRANSPORT_SECURITY,   // 56
    HeaderName.TRANSFER_ENCODING,           // 57
    HeaderName.USER_AGENT,                  // 58
    HeaderName.VARY,                        // 59
    HeaderName.VIA,                         // 60
    HeaderName.WWW_AUTHENTICATE,            // 61
)

/**
 * The static entries as decoded headers, index 1..61 (index 0 unused), shared by every decoder (`get_static`).
 * Returning them allocates nothing.
 */
internal val STATIC_HEADERS: Array<Header> = buildStaticHeaders()

private fun buildStaticHeaders(): Array<Header> {
    val gzip = HeaderValue.fromStatic("gzip, deflate")
    val out = ArrayList<Header>(STATIC_TABLE_LEN + 1)
    out.add(Header.Authority("")) // placeholder for index 0, never returned
    out.add(Header.Authority(""))
    out.add(Header.Method(HttpMethod.GET))
    out.add(Header.Method(HttpMethod.POST))
    out.add(Header.Path("/"))
    out.add(Header.Path("/index.html"))
    out.add(Header.Scheme("http"))
    out.add(Header.Scheme("https"))
    out.add(Header.Status(StatusCode.OK))
    out.add(Header.Status(StatusCode.NO_CONTENT))
    out.add(Header.Status(StatusCode.PARTIAL_CONTENT))
    out.add(Header.Status(StatusCode.NOT_MODIFIED))
    out.add(Header.Status(StatusCode.BAD_REQUEST))
    out.add(Header.Status(StatusCode.NOT_FOUND))
    out.add(Header.Status(StatusCode.INTERNAL_SERVER_ERROR))
    for (i in STATIC_FIELD_NAMES.indices) {
        val name = STATIC_FIELD_NAMES[i]
        val value = if (name === HeaderName.ACCEPT_ENCODING) gzip else HeaderValue.fromStatic("")
        out.add(Header.Field(name, value))
    }
    check(out.size == STATIC_TABLE_LEN + 1)
    return out.toTypedArray()
}

/**
 * Static index of each standard header name that has a static entry, keyed by the name's standard index (0 when the
 * name has none). Replaces the reference's `match` over header constants in `index_static`.
 */
private val STATIC_INDEX_BY_NAME: IntArray = run {
    var max = 0
    for (n in STATIC_FIELD_NAMES) if (n.standardIndex > max) max = n.standardIndex
    val a = IntArray(max + 1)
    for (i in STATIC_FIELD_NAMES.indices) a[STATIC_FIELD_NAMES[i].standardIndex] = 15 + i
    a
}

/** Result of [indexStatic] when the header has no static entry. */
internal const val STATIC_NONE = -1

/** Flag in an [indexStatic] result: the value matched as well as the name. */
internal const val STATIC_VALUE_MATCH = 0x100

/**
 * Checks the static table for a header (`index_static`). Returns [STATIC_NONE], or the static index, or'ed with
 * [STATIC_VALUE_MATCH] when the value matched too.
 */
internal fun indexStatic(kind: Int, name: HeaderName?, value: Any): Int = when (kind) {
    K_FIELD -> {
        val si = name!!.standardIndex
        val idx = if (si >= 0 && si < STATIC_INDEX_BY_NAME.size) STATIC_INDEX_BY_NAME[si] else 0
        when {
            idx == 0 -> STATIC_NONE
            idx == 16 && (value as HeaderValue).contentEquals("gzip, deflate") -> 16 or STATIC_VALUE_MATCH
            else -> idx
        }
    }
    K_AUTHORITY -> 1
    K_METHOD -> when (value as HttpMethod) {
        HttpMethod.GET -> 2 or STATIC_VALUE_MATCH
        HttpMethod.POST -> 3 or STATIC_VALUE_MATCH
        else -> 2
    }
    K_SCHEME -> when (value as String) {
        "http" -> 6 or STATIC_VALUE_MATCH
        "https" -> 7 or STATIC_VALUE_MATCH
        else -> 6
    }
    K_PATH -> when (value as String) {
        "/" -> 4 or STATIC_VALUE_MATCH
        "/index.html" -> 5 or STATIC_VALUE_MATCH
        else -> 4
    }
    K_PROTOCOL -> STATIC_NONE
    K_STATUS -> when ((value as StatusCode).asU16()) {
        200 -> 8 or STATIC_VALUE_MATCH
        204 -> 9 or STATIC_VALUE_MATCH
        206 -> 10 or STATIC_VALUE_MATCH
        304 -> 11 or STATIC_VALUE_MATCH
        400 -> 12 or STATIC_VALUE_MATCH
        404 -> 13 or STATIC_VALUE_MATCH
        500 -> 14 or STATIC_VALUE_MATCH
        else -> 8
    }
    else -> throw IllegalArgumentException("kind $kind")
}

/**
 * Whether only the name of this header may be indexed, never its value (`Header::skip_value_index`, borrowed by the
 * reference from nghttp2).
 */
internal fun skipValueIndex(kind: Int, name: HeaderName?): Boolean = when (kind) {
    K_FIELD -> name === HeaderName.AGE || name === HeaderName.AUTHORIZATION || name === HeaderName.CONTENT_LENGTH ||
        name === HeaderName.ETAG || name === HeaderName.IF_MODIFIED_SINCE || name === HeaderName.IF_NONE_MATCH ||
        name === HeaderName.LOCATION || name === HeaderName.COOKIE || name === HeaderName.SET_COOKIE
    K_PATH -> true
    else -> false
}
