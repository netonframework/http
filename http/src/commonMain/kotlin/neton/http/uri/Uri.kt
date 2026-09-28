package neton.http.uri

/**
 * The URI component of a request (`http::Uri`): one of the four request-target forms
 *
 * - origin-form `/path?query`,
 * - absolute-form `scheme://authority/path?query`,
 * - authority-form `host:port`,
 * - asterisk-form `*`.
 *
 * Parsing enforces the reference's limits (at most 65534 bytes in total, scheme at most 64 bytes) and reports
 * one of the twelve [InvalidUri.ErrorKind]s. A `#fragment` is dropped. The components are views of a single backing
 * string (the parsed input), so parsing an origin-form target allocates no substrings.
 *
 * Equality compares scheme and authority ASCII case-insensitively and path and query exactly; [eq] compares with a
 * string using the reference's rules (an absolute URI with path `/` equals the same text without the `/`).
 */
class Uri internal constructor(
    private val schemePart: Scheme?,
    private val authorityPart: Authority?,
    private val pq: PathAndQuery,
) {
    /** The scheme (`scheme()`), or null for a relative URI. */
    val scheme: Scheme? get() = schemePart

    /** The scheme text (`scheme_str()`), or null. */
    val schemeStr: String? get() = schemePart?.asStr()

    /** The authority (`authority()`), or null. */
    val authority: Authority? get() = authorityPart

    /** The host of the authority (`host()`), or null. */
    val host: String? get() = authorityPart?.host

    /** The port of the authority (`port()`), or null. */
    val port: Port? get() = authorityPart?.port

    /** The port number (`port_u16()`), or null. */
    val portU16: Int? get() = authorityPart?.portU16

    /**
     * The path (`path()`), case-sensitive. Empty for the authority-form; `*` for the asterisk-form; `/` for an
     * absolute URI written without a path.
     */
    val path: String get() = if (hasPath()) pq.path else ""

    /** The query after `?` (`query()`), or null. The fragment is never part of it. */
    val query: String? get() = pq.query

    /**
     * The path and query (`path_and_query()`): present for absolute URIs and when there is no authority,
     * null for the authority-form.
     */
    val pathAndQuery: PathAndQuery? get() = if (schemePart != null || authorityPart == null) pq else null

    private fun hasPath(): Boolean = !pq.isDataEmpty || schemePart != null

    /** Splits the URI into its parts (`into_parts`). */
    fun intoParts(): UriParts = UriParts(
        scheme = schemePart,
        authority = authorityPart,
        pathAndQuery = if (hasPath()) pq else null,
    )

    /**
     * Equality with a string under the reference's rules (`PartialEq<str>`): scheme and authority ASCII
     * case-insensitive, path and query exact, `/` may be omitted after an authority, a trailing `#...` in the
     * string is ignored.
     *
     * Kotlin shape: `==` cannot compare unrelated types, so string comparisons are the `eq` infix function.
     */
    infix fun eq(other: String): Boolean {
        val n = other.length
        var o = 0
        var absolute = false

        val sch = schemePart
        if (sch != null) {
            val s = sch.asStr()
            absolute = true
            if (n - o < s.length + 3) return false
            if (!asciiRegionEqualsIgnoreCase(s, 0, other, o, s.length)) return false
            o += s.length
            if (other[o] != ':' || other[o + 1] != '/' || other[o + 2] != '/') return false
            o += 3
        }

        val auth = authorityPart
        if (auth != null) {
            val len = auth.length
            absolute = true
            if (n - o < len) return false
            if (!asciiRegionEqualsIgnoreCase(auth.src, auth.start, other, o, len)) return false
            o += len
        }

        val p = path
        if (n - o < p.length || !regionEquals(p, 0, other, o, p.length)) {
            // The path-and-query "/" may be omitted after an authority; otherwise a mismatch.
            if (!(absolute && p == "/")) return false
        } else {
            o += p.length
        }

        val q = query
        if (q != null) {
            if (o == n) return q.isEmpty()
            if (other[o] != '?') return false
            o++
            if (n - o < q.length) return false
            if (!regionEquals(q, 0, other, o, q.length)) return false
            o += q.length
        }

        return o == n || other[o] == '#'
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Uri) return false
        return schemePart == other.schemePart &&
            authorityPart == other.authorityPart &&
            path == other.path &&
            query == other.query
    }

    override fun hashCode(): Int {
        var h = schemePart?.hashCode() ?: 0
        h = 31 * h + (authorityPart?.hashCode() ?: 0)
        h = 31 * h + path.hashCode()
        h = 31 * h + (query?.hashCode()?.plus(1) ?: 0)
        return h
    }

    /** `Display`: `scheme://authority` (when present), then the path and `?query`. */
    override fun toString(): String {
        if (schemePart == null && authorityPart == null && !pq.isDataEmpty) {
            val c = pq.src[pq.start]
            if (c == '/' || c == '*') return pq.asStr()
        }
        val sb = StringBuilder()
        if (schemePart != null) sb.append(schemePart.asStr()).append("://")
        if (authorityPart != null) sb.append(authorityPart.asStr())
        sb.append(path)
        val q = query
        if (q != null) sb.append('?').append(q)
        return sb.toString()
    }

    companion object {
        private val DEFAULT = Uri(null, null, PathAndQuery.SLASH)

        /** The URI `/` (`Default`). */
        fun default(): Uri = DEFAULT

        /** A builder for a URI (`Uri::builder()`). */
        fun builder(): UriBuilder = UriBuilder()

        /**
         * Parses a URI (`FromStr` / `TryFrom<&str>`).
         *
         * @throws InvalidUri
         */
        fun parse(s: String): Uri = unwrapOrThrow(parseString(s))

        /** Like [parse], returning null on error. */
        fun tryParse(s: String): Uri? = parseString(s) as? Uri

        /**
         * Parses a URI from `bytes[offset, offset + length)` (`TryFrom<&[u8]>`; also the equivalent of
         * `from_maybe_shared`). The bytes are decoded once into the backing string. Non-ASCII bytes must be
         * valid UTF-8 unless they are inside the dropped fragment.
         *
         * @throws InvalidUri
         */
        fun fromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Uri =
            unwrapOrThrow(parseBytes(bytes, offset, length))

        /** Like [fromBytes], returning null on error. */
        fun tryFromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Uri? =
            parseBytes(bytes, offset, length) as? Uri

        /**
         * A URI from a constant string (`from_static`).
         *
         * @throws IllegalArgumentException if the string is not a valid URI.
         */
        fun fromStatic(src: String): Uri {
            val r = parseString(src)
            if (r is InvalidUri.ErrorKind) {
                throw IllegalArgumentException("static str is not valid URI: ${r.description}", InvalidUri(r))
            }
            return r as Uri
        }

        /**
         * Builds a URI from parts (`from_parts`): a scheme requires an authority and a path-and-query; an
         * authority together with a path-and-query requires a scheme.
         *
         * @throws InvalidUriParts
         */
        fun fromParts(parts: UriParts): Uri {
            val r = partsResult(parts)
            if (r is InvalidUri.ErrorKind) throw InvalidUriParts(InvalidUri(r))
            return r as Uri
        }

        /** Like [fromParts], returning null on error. */
        fun tryFromParts(parts: UriParts): Uri? = partsResult(parts) as? Uri

        /** The authority-form URI of an [Authority] (`From<Authority>`). */
        fun from(authority: Authority): Uri = Uri(null, authority, PathAndQuery.EMPTY)

        /** The origin-form URI of a [PathAndQuery] (`From<PathAndQuery>`). */
        fun from(pathAndQuery: PathAndQuery): Uri = Uri(null, null, pathAndQuery)

        private fun partsResult(src: UriParts): Any {
            if (src.scheme != null) {
                if (src.authority == null) return InvalidUri.ErrorKind.AuthorityMissing
                if (src.pathAndQuery == null) return InvalidUri.ErrorKind.PathAndQueryMissing
            } else if (src.authority != null && src.pathAndQuery != null) {
                return InvalidUri.ErrorKind.SchemeMissing
            }
            return Uri(src.scheme, src.authority, src.pathAndQuery ?: PathAndQuery.EMPTY)
        }

        private fun parseBytes(bytes: ByteArray, offset: Int, length: Int): Any {
            checkRange(bytes.size, offset, length)
            if (length > MAX_LEN) return InvalidUri.ErrorKind.TooLong
            if (length == 0) return InvalidUri.ErrorKind.Empty
            // `from_shared`'s one-byte cases, before anything is decoded or allocated (a URI is immutable, so one
            // instance serves every request for `/` or `*`).
            if (length == 1) {
                if (bytes[offset] == '/'.code.toByte()) return ROOT
                if (bytes[offset] == '*'.code.toByte()) return ASTERISK
            }
            return parseUtf8Input(bytes, offset, length, { parseChecked(it) }) { r ->
                val pq = (r as Uri).pq
                if (pq === PathAndQuery.SLASH || pq === PathAndQuery.STAR || pq === PathAndQuery.EMPTY) -1
                else pq.start
            }
        }

        internal fun parseString(s: String): Any {
            if (utf8LengthExceeds(s, 0, s.length, MAX_LEN)) return InvalidUri.ErrorKind.TooLong
            return parseChecked(s)
        }

        private val ROOT = Uri(null, null, PathAndQuery.SLASH)
        private val ASTERISK = Uri(null, null, PathAndQuery.STAR)

        /** `Uri::from_shared` after the length check: dispatches on the request-target form. */
        private fun parseChecked(s: String): Any {
            val len = s.length
            if (len == 0) return InvalidUri.ErrorKind.Empty
            if (len == 1) {
                return when (s[0]) {
                    '/' -> Uri(null, null, PathAndQuery.SLASH)
                    '*' -> Uri(null, null, PathAndQuery.STAR)
                    else -> {
                        val end = Authority.parseNonEmpty(s, 0, 1)
                        when {
                            end < 0 -> Authority.decodeError(end)
                            end != 1 -> InvalidUri.ErrorKind.InvalidUriChar
                            else -> Uri(null, Authority(s, 0, 1), PathAndQuery.EMPTY)
                        }
                    }
                }
            }
            if (s[0] == '/') {
                val pq = PathAndQuery.scan(s, 0, len)
                return if (pq is PathAndQuery) Uri(null, null, pq) else pq
            }
            return parseFull(s)
        }

        private fun parseFull(s: String): Any {
            val len = s.length
            val schemeRes = Scheme.parsePrefix(s)
            val scheme: Scheme?
            val pos: Int
            when (schemeRes) {
                Scheme.PARSE_NONE -> { scheme = null; pos = 0 }
                Scheme.PARSE_HTTP -> { scheme = Scheme.HTTP; pos = 7 }
                Scheme.PARSE_HTTPS -> { scheme = Scheme.HTTPS; pos = 8 }
                Scheme.PARSE_TOO_LONG -> return InvalidUri.ErrorKind.SchemeTooLong
                else -> { scheme = Scheme.other(s.substring(0, schemeRes)); pos = schemeRes + 3 }
            }

            // Find the end of the authority.
            val authEnd = Authority.validate(s, pos, len)
            if (authEnd < 0) return Authority.decodeError(authEnd)

            if (scheme == null) {
                if (authEnd != len) return InvalidUri.ErrorKind.InvalidFormat
                return Uri(null, Authority(s, 0, len), PathAndQuery.EMPTY)
            }

            // Authority is required when absolute.
            if (authEnd == pos) return InvalidUri.ErrorKind.InvalidFormat
            val authority = Authority(s, pos, authEnd)

            // When absolute, an empty path is coerced to "/".
            if (authEnd == len) return Uri(scheme, authority, PathAndQuery.SLASH)
            val pq = PathAndQuery.scan(s, authEnd, len)
            return if (pq is PathAndQuery) Uri(scheme, authority, pq) else pq
        }
    }
}

/** Equality of a string with a [Uri] under [Uri.eq]'s rules. */
infix fun String.eq(other: Uri): Boolean = other.eq(this)

/**
 * The parts of a URI (`http::uri::Parts`), given to [Uri.fromParts] and returned by [Uri.intoParts].
 *
 * Kotlin shape: named `UriParts` (not `Parts`) so it does not clash with the request / response parts types.
 */
class UriParts(
    /** The scheme component. */
    var scheme: Scheme? = null,
    /** The authority component. */
    var authority: Authority? = null,
    /** The origin-form component. */
    var pathAndQuery: PathAndQuery? = null,
) {
    override fun toString(): String = "Parts(scheme=$scheme, authority=$authority, pathAndQuery=$pathAndQuery)"
}
