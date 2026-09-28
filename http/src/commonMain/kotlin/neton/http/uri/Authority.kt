package neton.http.uri

/**
 * The authority component of a URI (`http::uri::Authority`): `[userinfo@]host[:port]`.
 *
 * Stored as a view `[start, end)` of a backing string (usually the whole request target), materialised on first
 * [asStr]. Equality, ordering and hashing are ASCII case-insensitive, like the reference.
 */
class Authority internal constructor(
    internal val src: String,
    internal val start: Int,
    internal val end: Int,
) : Comparable<Authority> {

    private var str: String? = null

    internal val length: Int get() = end - start

    /** The authority text (`as_str`). */
    fun asStr(): String {
        str?.let { return it }
        val s = if (start == 0 && end == src.length) src else src.substring(start, end)
        str = s
        return s
    }

    /**
     * The host (`host()`): the part after the last `@`; an IP literal keeps its brackets (`[::1]`), otherwise
     * everything up to the first `:`.
     */
    val host: String
        get() {
            var hp = start
            for (i in end - 1 downTo start) if (src[i] == '@') { hp = i + 1; break }
            var hEnd = end
            if (hp < end && src[hp] == '[') {
                for (i in hp until end) if (src[i] == ']') { hEnd = i + 1; break }
            } else {
                for (i in hp until end) if (src[i] == ':') { hEnd = i; break }
            }
            return if (hp == start && hEnd == end) asStr() else src.substring(hp, hEnd)
        }

    /** The port (`port()`): the text after the last `:` if it is a valid `u16`, else null. */
    val port: Port?
        get() {
            val c = lastColon()
            if (c < 0) return null
            val v = Port.parseU16(src, c + 1, end)
            return if (v < 0) null else Port(v, src.substring(c + 1, end))
        }

    /** The port number (`port_u16()`), without allocating. */
    val portU16: Int?
        get() {
            val c = lastColon()
            if (c < 0) return null
            val v = Port.parseU16(src, c + 1, end)
            return if (v < 0) null else v
        }

    private fun lastColon(): Int {
        for (i in end - 1 downTo start) if (src[i] == ':') return i
        return -1
    }

    /**
     * ASCII case-insensitive equality with a string (`PartialEq<str>` / `PartialEq<String>`).
     *
     * Kotlin shape: `==` cannot compare unrelated types, so string comparisons are the `eq` infix function.
     */
    infix fun eq(other: String): Boolean =
        other.length == length && asciiRegionEqualsIgnoreCase(src, start, other, 0, length)

    /** ASCII case-insensitive ordering. */
    override fun compareTo(other: Authority): Int = compareIgnoreCase(src, start, end, other.src, other.start, other.end)

    /** ASCII case-insensitive ordering against a string (`PartialOrd<str>`). */
    operator fun compareTo(other: String): Int = compareIgnoreCase(src, start, end, other, 0, other.length)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is Authority && other.length == length &&
            asciiRegionEqualsIgnoreCase(src, start, other.src, other.start, length)
    }

    override fun hashCode(): Int {
        var h = length
        for (i in start until end) h = 31 * h + asciiLower(src[i]).code
        return h
    }

    override fun toString(): String = asStr()

    companion object {
        /**
         * Parses an authority (`TryFrom<&str>` / `FromStr`). The whole string must be a valid, non-empty
         * authority; a `/`, `?` or `#` inside is `InvalidUriChar`.
         *
         * @throws InvalidUri
         */
        fun parse(s: String): Authority = unwrapOrThrow(create(s))

        /** Like [parse], returning null on error. */
        fun tryParse(s: String): Authority? = create(s) as? Authority

        /**
         * Parses an authority from `bytes[offset, offset + length)` (`TryFrom<&[u8]>`; also the equivalent of
         * `from_maybe_shared`).
         *
         * @throws InvalidUri
         */
        fun fromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Authority =
            unwrapOrThrow(createBytes(bytes, offset, length))

        /** Like [fromBytes], returning null on error. */
        fun tryFromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Authority? =
            createBytes(bytes, offset, length) as? Authority

        /**
         * Authority from a constant string (`from_static`).
         *
         * Mirrors the reference exactly: the string is validated up to the first `/`, `?` or `#` and then kept
         * whole, so text after such a character is not rejected.
         *
         * @throws IllegalArgumentException if the string is empty or not a valid authority.
         */
        fun fromStatic(src: String): Authority {
            require(validate(src, 0, src.length) >= 0) { "static str is not valid authority" }
            return Authority(src, 0, src.length)
        }

        internal fun create(s: String): Any {
            val end = parseNonEmpty(s, 0, s.length)
            if (end < 0) return decodeError(end)
            if (end != s.length) return InvalidUri.ErrorKind.InvalidUriChar
            return Authority(s, 0, s.length)
        }

        private fun createBytes(bytes: ByteArray, offset: Int, length: Int): Any {
            checkRange(bytes.size, offset, length)
            // Every authority char is ASCII: a lossy decode rejects invalid UTF-8 at the same position.
            return create(bytes.decodeToString(offset, offset + length))
        }

        /** `parse_non_empty`: the end of the authority in `s[from, to)`, or a negative error code. */
        internal fun parseNonEmpty(s: String, from: Int, to: Int): Int =
            if (from == to) errorCode(InvalidUri.ErrorKind.Empty) else validate(s, from, to)

        internal fun errorCode(kind: InvalidUri.ErrorKind): Int = -1 - kind.ordinal

        internal fun decodeError(code: Int): InvalidUri.ErrorKind = InvalidUri.ErrorKind.entries[-1 - code]

        private const val MAX_COLONS = 8 // e.g. [FEDC:BA98:7654:3210:FEDC:BA98:7654:3210]:80

        /**
         * `validate_authority_bytes` + the `Authority::parse` error mapping: returns the (absolute) index of the
         * first `/`, `?` or `#` (or [to]), or a negative error code ([errorCode]).
         */
        internal fun validate(s: String, from: Int, to: Int): Int {
            if (from == to) return errorCode(InvalidUri.ErrorKind.Empty)
            var colonCnt = 0
            var startBracket = false
            var endBracket = false
            var hasPercent = false
            var end = to
            var atSignPos = to
            var i = from
            while (i < to) {
                val b = s[i].code
                val ch = if (b < 128) URI_CHARS[b].toInt() else 0
                if (ch == '/'.code || ch == '?'.code || ch == '#'.code) {
                    end = i
                    break
                }
                when (ch) {
                    0 -> {
                        // A '%' is allowed in the userinfo (percent-encoding) and in an IPv6 zone id; anywhere
                        // else it is rejected below unless cleared by a later '@' or ']'.
                        if (b == '%'.code) hasPercent = true
                        else return errorCode(InvalidUri.ErrorKind.InvalidUriChar)
                    }
                    ':'.code -> {
                        if (colonCnt >= MAX_COLONS) return errorCode(InvalidUri.ErrorKind.InvalidAuthority)
                        colonCnt++
                    }
                    '['.code -> {
                        if (hasPercent || startBracket) return errorCode(InvalidUri.ErrorKind.InvalidAuthority)
                        startBracket = true
                    }
                    ']'.code -> {
                        if (!startBracket || endBracket) return errorCode(InvalidUri.ErrorKind.InvalidAuthority)
                        endBracket = true
                        // Those were part of an IPv6 hostname.
                        colonCnt = 0
                        hasPercent = false
                    }
                    '@'.code -> {
                        atSignPos = i
                        // Those were part of the userinfo, not a port colon.
                        colonCnt = 0
                        hasPercent = false
                    }
                }
                i++
            }
            if (startBracket != endBracket) return errorCode(InvalidUri.ErrorKind.InvalidAuthority)
            // Things like 'localhost:8080:3030' are rejected.
            if (colonCnt > 1) return errorCode(InvalidUri.ErrorKind.InvalidAuthority)
            // Nothing after an '@'.
            if (end > from && atSignPos == end - 1) return errorCode(InvalidUri.ErrorKind.InvalidAuthority)
            // A '%' after the userinfo and outside an IPv6 literal.
            if (hasPercent) return errorCode(InvalidUri.ErrorKind.InvalidAuthority)
            return end
        }

        private fun compareIgnoreCase(a: String, aFrom: Int, aTo: Int, b: String, bFrom: Int, bTo: Int): Int {
            val n = minOf(aTo - aFrom, bTo - bFrom)
            for (k in 0 until n) {
                val x = asciiLower(a[aFrom + k])
                val y = asciiLower(b[bFrom + k])
                if (x != y) return x.code - y.code
            }
            return (aTo - aFrom) - (bTo - bFrom)
        }
    }
}

/** ASCII case-insensitive equality of a string with an [Authority]. */
infix fun String.eq(other: Authority): Boolean = other.eq(this)

/** ASCII case-insensitive ordering of a string against an [Authority] (`PartialOrd<Authority> for str`). */
operator fun String.compareTo(other: Authority): Int = -other.compareTo(this)
