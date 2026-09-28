package neton.http.uri

/**
 * The path and query of a URI (`http::uri::PathAndQuery`), the origin-form of a request target.
 *
 * Stored as a view `[start, end)` of a backing string plus the offset of `?` (like the reference's `query: u16`);
 * [path], [query] and [asStr] are materialised on first use and cached. A `#fragment` is dropped when parsing.
 *
 * Equality and hashing compare the stored text exactly (so the empty path-and-query and `/` differ although both
 * print as `/`); ordering compares [asStr] in UTF-8 byte order, as the reference does.
 */
class PathAndQuery internal constructor(
    internal val src: String,
    internal val start: Int,
    internal val end: Int,
    /** Offset of `?` relative to [start], or [NONE]. */
    internal val queryAt: Int,
) : Comparable<PathAndQuery> {

    private var dataCache: String? = null
    private var pathCache: String? = null
    private var queryCache: String? = null

    internal val isDataEmpty: Boolean get() = start == end

    private fun data(): String {
        dataCache?.let { return it }
        val s = if (start == 0 && end == src.length) src else src.substring(start, end)
        dataCache = s
        return s
    }

    /** The path (`path()`), case-sensitive; `/` when empty, `*` for the asterisk form. */
    val path: String
        get() {
            pathCache?.let { return it }
            val p = when {
                queryAt == NONE -> if (isDataEmpty) "/" else data()
                queryAt == 0 -> "/"
                else -> src.substring(start, start + queryAt)
            }
            pathCache = p
            return p
        }

    /** The query after `?` (`query()`), or null when there is no `?`; may be empty. */
    val query: String?
        get() {
            if (queryAt == NONE) return null
            queryCache?.let { return it }
            val q = src.substring(start + queryAt + 1, end)
            queryCache = q
            return q
        }

    /** The path and query as one string (`as_str`); `/` when empty. */
    fun asStr(): String = if (isDataEmpty) "/" else data()

    /**
     * Case-sensitive equality of [asStr] with a string (`PartialEq<str>` / `PartialEq<String>`).
     *
     * Kotlin shape: `==` cannot compare unrelated types, so string comparisons are the `eq` infix function.
     */
    infix fun eq(other: String): Boolean = asStr() == other

    /** Ordering of [asStr] in UTF-8 byte order. */
    override fun compareTo(other: PathAndQuery): Int {
        val a = asStr()
        val b = other.asStr()
        return compareUtf8Order(a, 0, a.length, b, 0, b.length)
    }

    /** Ordering of [asStr] against a string in UTF-8 byte order (`PartialOrd<str>`). */
    operator fun compareTo(other: String): Int {
        val a = asStr()
        return compareUtf8Order(a, 0, a.length, other, 0, other.length)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is PathAndQuery && other.end - other.start == end - start &&
            regionEquals(src, start, other.src, other.start, end - start)
    }

    override fun hashCode(): Int {
        var h = 0
        for (i in start until end) h = 31 * h + src[i].code
        return h
    }

    /** `Display`: the text, with a leading `/` added unless it starts with `/` or `*`; `/` when empty. */
    override fun toString(): String {
        if (isDataEmpty) return "/"
        val c = src[start]
        return if (c == '/' || c == '*') data() else "/" + data()
    }

    companion object {
        internal val EMPTY = PathAndQuery("", 0, 0, NONE)
        internal val SLASH = PathAndQuery("/", 0, 1, NONE)
        internal val STAR = PathAndQuery("*", 0, 1, NONE)

        /**
         * Parses a path and query (`TryFrom<&str>` / `FromStr`): non-empty, at most 65534 UTF-8 bytes, `*` or
         * starting with `/`, `?` or `#`, and only bytes allowed by the path / query tables; a fragment is dropped.
         *
         * @throws InvalidUri
         */
        fun parse(s: String): PathAndQuery = unwrapOrThrow(parseString(s))

        /** Like [parse], returning null on error. */
        fun tryParse(s: String): PathAndQuery? = parseString(s) as? PathAndQuery

        /**
         * Parses from `bytes[offset, offset + length)` (`TryFrom<&[u8]>`; also the equivalent of
         * `from_maybe_shared`). Non-ASCII bytes must be valid UTF-8 unless inside the dropped fragment.
         *
         * @throws InvalidUri
         */
        fun fromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): PathAndQuery =
            unwrapOrThrow(parseBytes(bytes, offset, length))

        /** Like [fromBytes], returning null on error. */
        fun tryFromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): PathAndQuery? =
            parseBytes(bytes, offset, length) as? PathAndQuery

        /**
         * Path and query from a constant string (`from_static`). Stricter than [parse], as in the reference:
         * a fragment or any non-ASCII character is rejected.
         *
         * @throws IllegalArgumentException if the string is not a valid path and query.
         */
        fun fromStatic(src: String): PathAndQuery {
            val r = parseString(src)
            require(r is PathAndQuery && r.end == src.length && src.all { it.code < 128 }) {
                "static str is not valid path"
            }
            return r
        }

        internal fun parseString(s: String): Any {
            if (s.isEmpty()) return InvalidUri.ErrorKind.Empty
            if (utf8LengthExceeds(s, 0, s.length, MAX_LEN)) return InvalidUri.ErrorKind.TooLong
            return scan(s, 0, s.length)
        }

        private fun parseBytes(bytes: ByteArray, offset: Int, length: Int): Any {
            checkRange(bytes.size, offset, length)
            if (length == 0) return InvalidUri.ErrorKind.Empty
            if (length > MAX_LEN) return InvalidUri.ErrorKind.TooLong
            return parseUtf8Input(bytes, offset, length, { scan(it, 0, it.length) }, { 0 })
        }

        /**
         * `scan_path_and_query` over `s[from, to)` (length already checked against [MAX_LEN]): returns a
         * [PathAndQuery] viewing `s` (truncated before any `#`) or an [InvalidUri.ErrorKind].
         */
        internal fun scan(s: String, from: Int, to: Int): Any {
            if (from == to) return InvalidUri.ErrorKind.Empty
            if (to - from == 1 && s[from] == '*') return PathAndQuery(s, from, to, NONE)
            val first = s[from]
            if (first != '/' && first != '?' && first != '#') return InvalidUri.ErrorKind.PathDoesNotStartWithSlash

            var i = from
            var queryAt = NONE
            var keep = to
            var high = false
            while (i < to) {
                val c = s[i].code
                val cls = if (c < 128) PATH_MAP[c] else CLASS_HIGH
                when (cls) {
                    CLASS_VALID -> {}
                    CLASS_QUERY -> { queryAt = i - from; i++; break }
                    CLASS_FRAGMENT -> { keep = i; break }
                    CLASS_HIGH -> high = true
                    else -> return InvalidUri.ErrorKind.InvalidUriChar
                }
                i++
            }
            if (queryAt != NONE) {
                while (i < to) {
                    val c = s[i].code
                    val cls = if (c < 128) QUERY_MAP[c] else CLASS_HIGH
                    when (cls) {
                        CLASS_VALID -> {}
                        CLASS_HIGH -> high = true
                        CLASS_FRAGMENT -> { keep = i; break }
                        else -> return InvalidUri.ErrorKind.InvalidUriChar
                    }
                    i++
                }
            }
            // A Kotlin String may hold unpaired surrogates, which are not valid UTF-8.
            if (high && !isWellFormedUtf16(s, from, keep)) return InvalidUri.ErrorKind.InvalidUriChar
            return PathAndQuery(s, from, keep, queryAt)
        }
    }
}

/** Case-sensitive equality of a string with [PathAndQuery.asStr]. */
infix fun String.eq(other: PathAndQuery): Boolean = other.eq(this)

/** Ordering of a string against [PathAndQuery.asStr] in UTF-8 byte order (`PartialOrd<PathAndQuery> for str`). */
operator fun String.compareTo(other: PathAndQuery): Int = -other.compareTo(this)
