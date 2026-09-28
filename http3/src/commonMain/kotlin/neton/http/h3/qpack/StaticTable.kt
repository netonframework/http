package neton.http.h3.qpack

import neton.http.header.HeaderName
import neton.http.header.HeaderValue

/**
 * The QPACK static table (RFC 9204 Appendix A), `h3::qpack::static_`: 99 entries, indexed from 0 (the HPACK table
 * starts at 1). The reference's `find` / `find_name` are hand-written matches; here they are derived from the table
 * (the first entry of a name for [findName]), which gives the same indices (checked by the tests).
 */
object StaticTable {
    /** The number of entries. */
    const val SIZE: Int = 99

    private val ENTRIES: List<Pair<String, String>> = listOf(
        ":authority" to "",
        ":path" to "/",
        "age" to "0",
        "content-disposition" to "",
        "content-length" to "0",
        "cookie" to "",
        "date" to "",
        "etag" to "",
        "if-modified-since" to "",
        "if-none-match" to "",
        "last-modified" to "",
        "link" to "",
        "location" to "",
        "referer" to "",
        "set-cookie" to "",
        ":method" to "CONNECT",
        ":method" to "DELETE",
        ":method" to "GET",
        ":method" to "HEAD",
        ":method" to "OPTIONS",
        ":method" to "POST",
        ":method" to "PUT",
        ":scheme" to "http",
        ":scheme" to "https",
        ":status" to "103",
        ":status" to "200",
        ":status" to "304",
        ":status" to "404",
        ":status" to "503",
        "accept" to "*/*",
        "accept" to "application/dns-message",
        "accept-encoding" to "gzip, deflate, br",
        "accept-ranges" to "bytes",
        "access-control-allow-headers" to "cache-control",
        "access-control-allow-headers" to "content-type",
        "access-control-allow-origin" to "*",
        "cache-control" to "max-age=0",
        "cache-control" to "max-age=2592000",
        "cache-control" to "max-age=604800",
        "cache-control" to "no-cache",
        "cache-control" to "no-store",
        "cache-control" to "public, max-age=31536000",
        "content-encoding" to "br",
        "content-encoding" to "gzip",
        "content-type" to "application/dns-message",
        "content-type" to "application/javascript",
        "content-type" to "application/json",
        "content-type" to "application/x-www-form-urlencoded",
        "content-type" to "image/gif",
        "content-type" to "image/jpeg",
        "content-type" to "image/png",
        "content-type" to "text/css",
        "content-type" to "text/html; charset=utf-8",
        "content-type" to "text/plain",
        "content-type" to "text/plain;charset=utf-8",
        "range" to "bytes=0-",
        "strict-transport-security" to "max-age=31536000",
        "strict-transport-security" to "max-age=31536000; includesubdomains",
        "strict-transport-security" to "max-age=31536000; includesubdomains; preload",
        "vary" to "accept-encoding",
        "vary" to "origin",
        "x-content-type-options" to "nosniff",
        "x-xss-protection" to "1; mode=block",
        ":status" to "100",
        ":status" to "204",
        ":status" to "206",
        ":status" to "302",
        ":status" to "400",
        ":status" to "403",
        ":status" to "421",
        ":status" to "425",
        ":status" to "500",
        "accept-language" to "",
        "access-control-allow-credentials" to "FALSE",
        "access-control-allow-credentials" to "TRUE",
        "access-control-allow-headers" to "*",
        "access-control-allow-methods" to "get",
        "access-control-allow-methods" to "get, post, options",
        "access-control-allow-methods" to "options",
        "access-control-expose-headers" to "content-length",
        "access-control-request-headers" to "content-type",
        "access-control-request-method" to "get",
        "access-control-request-method" to "post",
        "alt-svc" to "clear",
        "authorization" to "",
        "content-security-policy" to "script-src 'none'; object-src 'none'; base-uri 'none'",
        "early-data" to "1",
        "expect-ct" to "",
        "forwarded" to "",
        "if-range" to "",
        "origin" to "",
        "purpose" to "prefetch",
        "server" to "",
        "timing-allow-origin" to "*",
        "upgrade-insecure-requests" to "1",
        "user-agent" to "",
        "x-forwarded-for" to "",
        "x-frame-options" to "deny",
        "x-frame-options" to "sameorigin",
    )

    private val FIELDS: Array<HeaderField> = Array(ENTRIES.size) { HeaderField(ENTRIES[it].first, ENTRIES[it].second) }

    /** The entries of each name, in table order. */
    private val BY_NAME: Map<String, IntArray> =
        ENTRIES.indices.groupBy { ENTRIES[it].first }.mapValues { it.value.toIntArray() }

    /** The entries of each regular (non-pseudo) name, keyed by [HeaderName]; lookups by name hash, no allocation. */
    private val BY_HEADER_NAME: Map<HeaderName, IntArray> =
        BY_NAME.filterKeys { !it.startsWith(":") }.mapKeys { HeaderName.fromStatic(it.key) }

    init {
        check(FIELDS.size == SIZE)
    }

    /**
     * The entry at [index] (`get`).
     * @throws StaticTableException `Unknown(index)` past the table.
     */
    fun get(index: Long): HeaderField = getOrNull(index) ?: throw StaticTableException(index)

    /** The entry at [index], or null past the table. */
    fun getOrNull(index: Long): HeaderField? = if (index in 0 until SIZE) FIELDS[index.toInt()] else null

    /** The index of the entry with this name and value, or null (`find`). */
    fun find(field: HeaderField): Int? = find(field.name, field.value)

    /** The index of the entry with this name and value, or null. */
    fun find(name: ByteArray, value: ByteArray): Int? {
        val candidates = BY_NAME[name.decodeToString()] ?: return null
        for (i in candidates) if (FIELDS[i].value.contentEquals(value)) return i
        return null
    }

    /** The index of the first entry with this name, or null (`find_name`). */
    fun findName(name: ByteArray): Int? = BY_NAME[name.decodeToString()]?.get(0)

    /** The index of the first entry with this regular name, or -1. */
    internal fun findName(name: HeaderName): Int = BY_HEADER_NAME[name]?.get(0) ?: -1

    /** The index of the entry with this regular name and value, or -1. */
    internal fun find(name: HeaderName, value: HeaderValue): Int {
        val candidates = BY_HEADER_NAME[name] ?: return -1
        for (i in candidates) {
            val v = FIELDS[i].value
            if (value.contentEquals(v, 0, v.size)) return i
        }
        return -1
    }

    /** The index of the entry with this pseudo-header [name] (e.g. `":status"`) and ASCII [value], or -1. */
    internal fun findPseudo(name: String, value: String): Int {
        val candidates = BY_NAME[name] ?: return -1
        for (i in candidates) if (asciiEquals(FIELDS[i].value, value)) return i
        return -1
    }

    /** The first entry of the pseudo-header [name]. */
    internal fun findPseudoName(name: String): Int = BY_NAME.getValue(name)[0]

    private fun asciiEquals(a: ByteArray, s: String): Boolean {
        if (a.size != s.length) return false
        for (i in a.indices) if ((a[i].toInt() and 0xff) != s[i].code) return false
        return true
    }
}

/** An index past the static table (`static_::Error::Unknown`). */
class StaticTableException(val index: Long) : Exception("unknown static index: $index")
