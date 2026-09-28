package neton.http.uri

/**
 * The scheme component of a URI (`http::uri::Scheme`).
 *
 * `http` and `https` are the shared constants [HTTP] and [HTTPS]; any other scheme keeps its text as given.
 * Equality follows the reference: the two standard schemes equal only themselves, other schemes compare
 * ASCII case-insensitively (so `Scheme.parse("HTTP")`, which is not the standard constant, is not equal to
 * [HTTP], while `eq("http")` is true). Hashing is case-insensitive.
 */
class Scheme private constructor(private val id: Int, private val other: String?) {

    /** The scheme text (`as_str`): `"http"`, `"https"`, or the scheme as written. */
    fun asStr(): String = when (id) {
        ID_HTTP -> "http"
        ID_HTTPS -> "https"
        else -> other!!
    }

    /**
     * ASCII case-insensitive equality with a string (`PartialEq<str>`).
     *
     * Kotlin shape: `==` cannot compare unrelated types, so string comparisons are the `eq` infix function.
     */
    infix fun eq(other: String): Boolean {
        val s = asStr()
        return s.length == other.length && asciiRegionEqualsIgnoreCase(s, 0, other, 0, s.length)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Scheme || other.id != id) return false
        if (id != ID_OTHER) return true
        val a = this.other!!
        val b = other.other!!
        return a.length == b.length && asciiRegionEqualsIgnoreCase(a, 0, b, 0, a.length)
    }

    override fun hashCode(): Int = when (id) {
        ID_HTTP -> 1
        ID_HTTPS -> 2
        else -> {
            val s = other!!
            var h = s.length
            for (c in s) h = 31 * h + asciiLower(c).code
            h
        }
    }

    override fun toString(): String = asStr()

    companion object {
        private const val ID_OTHER = 0
        private const val ID_HTTP = 1
        private const val ID_HTTPS = 2

        /** HTTP protocol scheme. */
        val HTTP: Scheme = Scheme(ID_HTTP, null)

        /** HTTP protocol over TLS. */
        val HTTPS: Scheme = Scheme(ID_HTTPS, null)

        internal fun other(s: String): Scheme = Scheme(ID_OTHER, s)

        /**
         * Parses a scheme (`TryFrom<&str>` / `FromStr`): exactly `http` / `https` become the standard constants
         * (case-sensitive, as in the reference); anything else must be at most 64 bytes of scheme characters
         * (`SchemeTooLong`, `InvalidScheme`). Note the reference accepts the empty string.
         *
         * @throws InvalidUri
         */
        fun parse(s: String): Scheme = unwrapOrThrow(parseExact(s))

        /** Like [parse], returning null on error. */
        fun tryParse(s: String): Scheme? = parseExact(s) as? Scheme

        /**
         * Parses a scheme from `bytes[offset, offset + length)` (`TryFrom<&[u8]>`).
         *
         * @throws InvalidUri
         */
        fun fromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Scheme =
            unwrapOrThrow(parseExactBytes(bytes, offset, length))

        /** Like [fromBytes], returning null on error. */
        fun tryFromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Scheme? =
            parseExactBytes(bytes, offset, length) as? Scheme

        /** `Scheme2::parse_exact` over a String: a [Scheme] or an [InvalidUri.ErrorKind]. */
        internal fun parseExact(s: String): Any {
            if (s == "http") return HTTP
            if (s == "https") return HTTPS
            if (utf8LengthExceeds(s, 0, s.length, MAX_SCHEME_LEN)) return InvalidUri.ErrorKind.SchemeTooLong
            for (c in s) {
                val code = c.code
                val ch = if (code < 128) SCHEME_CHARS[code].toInt() else 0
                // ':' is in the table (for `parse`) but never part of an exact scheme.
                if (ch == 0 || ch == ':'.code) return InvalidUri.ErrorKind.InvalidScheme
            }
            return other(s)
        }

        private fun parseExactBytes(b: ByteArray, offset: Int, length: Int): Any {
            checkRange(b.size, offset, length)
            if (length == 4 && bytesAre(b, offset, "http")) return HTTP
            if (length == 5 && bytesAre(b, offset, "https")) return HTTPS
            if (length > MAX_SCHEME_LEN) return InvalidUri.ErrorKind.SchemeTooLong
            for (i in offset until offset + length) {
                val ch = SCHEME_CHARS[b[i].toInt() and 0xFF].toInt()
                if (ch == 0 || ch == ':'.code) return InvalidUri.ErrorKind.InvalidScheme
            }
            return other(b.decodeToString(offset, offset + length))
        }

        private fun bytesAre(b: ByteArray, offset: Int, s: String): Boolean {
            for (k in s.indices) if (b[offset + k].toInt() != s[k].code) return false
            return true
        }

        internal const val PARSE_NONE = -1
        internal const val PARSE_HTTP = -2
        internal const val PARSE_HTTPS = -3
        internal const val PARSE_TOO_LONG = -4

        /**
         * `Scheme2::parse`: detects a `scheme://` prefix of a full URI. Returns [PARSE_NONE], [PARSE_HTTP],
         * [PARSE_HTTPS] (matched ASCII case-insensitively), [PARSE_TOO_LONG], or the length of another scheme.
         */
        internal fun parsePrefix(s: String): Int {
            val len = s.length
            if (len >= 7 && asciiRegionEqualsIgnoreCase(s, 0, "http://", 0, 7)) return PARSE_HTTP
            if (len >= 8 && asciiRegionEqualsIgnoreCase(s, 0, "https://", 0, 8)) return PARSE_HTTPS
            if (len > 3) {
                for (i in 0 until len) {
                    val code = s[i].code
                    val ch = if (code < 128) SCHEME_CHARS[code].toInt() else 0
                    if (ch == ':'.code) {
                        // Not enough data remaining, or not followed by "//": not a scheme.
                        if (len < i + 3) break
                        if (s[i + 1] != '/' || s[i + 2] != '/') break
                        if (i > MAX_SCHEME_LEN) return PARSE_TOO_LONG
                        return i
                    } else if (ch == 0) {
                        break
                    }
                }
            }
            return PARSE_NONE
        }
    }
}

/** ASCII case-insensitive equality of a string with a [Scheme] (`PartialEq<Scheme> for str`). */
infix fun String.eq(other: Scheme): Boolean = other.eq(this)
