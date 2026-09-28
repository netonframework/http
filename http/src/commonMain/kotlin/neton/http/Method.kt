package neton.http

/** Thrown when bytes or a string are not a valid HTTP method (the reference's `InvalidMethod`). */
class InvalidMethod : HttpException("invalid HTTP method")

/**
 * The request method (verb), mirroring `http::Method`.
 *
 * The ten standard methods (the eight of RFC 9110, PATCH and QUERY) are shared singletons; parsing their
 * names from bytes or strings returns those singletons without allocating. Any other non-empty token
 * (RFC 9110 `tchar`s only) is an extension method. Methods are case-sensitive: `get` is an extension
 * method, not [GET].
 *
 * Equality, hashing and ordering follow the method name, as in the reference. Kotlin cannot overload `==`
 * between a `Method` and a `String`, so compare with [asStr] (`method.asStr() == "GET"`) where the
 * reference compares with `&str`. The reference's inline/allocated split for extension names is an
 * internal storage detail; here every extension keeps its name as a `String`.
 */
class Method private constructor(private val kind: Int, private val name: String) : Comparable<Method> {

    /** Whether the method is "safe" (essentially read-only): GET, HEAD, OPTIONS, TRACE, QUERY (RFC 9110 §9.2.1). */
    fun isSafe(): Boolean = kind == K_GET || kind == K_HEAD || kind == K_OPTIONS || kind == K_TRACE || kind == K_QUERY

    /** Whether repeating the request has the same effect as sending it once: the safe methods plus PUT and DELETE. */
    fun isIdempotent(): Boolean = kind == K_PUT || kind == K_DELETE || isSafe()

    /** The method name, exactly as parsed (case preserved). */
    fun asStr(): String = name

    override fun compareTo(other: Method): Int = name.compareTo(other.name)

    // Standard methods exist only as the shared constants (the constructor is private and parsing returns them), so
    // two of them are equal only when identical; names are compared for extension methods alone.
    override fun equals(other: Any?): Boolean =
        this === other || (other is Method && kind == K_EXTENSION && other.kind == K_EXTENSION && name == other.name)

    override fun hashCode(): Int = name.hashCode()

    /** The method name, as the reference's `Display`. */
    override fun toString(): String = name

    companion object {
        private const val K_OPTIONS = 0
        private const val K_GET = 1
        private const val K_POST = 2
        private const val K_PUT = 3
        private const val K_DELETE = 4
        private const val K_HEAD = 5
        private const val K_TRACE = 6
        private const val K_CONNECT = 7
        private const val K_PATCH = 8
        private const val K_QUERY = 9
        private const val K_EXTENSION = 10

        /** GET */
        val GET: Method = Method(K_GET, "GET")

        /** POST */
        val POST: Method = Method(K_POST, "POST")

        /** PUT */
        val PUT: Method = Method(K_PUT, "PUT")

        /** DELETE */
        val DELETE: Method = Method(K_DELETE, "DELETE")

        /** HEAD */
        val HEAD: Method = Method(K_HEAD, "HEAD")

        /** OPTIONS */
        val OPTIONS: Method = Method(K_OPTIONS, "OPTIONS")

        /** CONNECT */
        val CONNECT: Method = Method(K_CONNECT, "CONNECT")

        /** PATCH */
        val PATCH: Method = Method(K_PATCH, "PATCH")

        /** TRACE */
        val TRACE: Method = Method(K_TRACE, "TRACE")

        /** QUERY */
        val QUERY: Method = Method(K_QUERY, "QUERY")

        /** The default method, [GET] (the reference's `Default`). */
        val DEFAULT: Method get() = GET

        // RFC 9110 §5.6.2: tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." /
        //                          "^" / "_" / "`" / "|" / "~" / DIGIT / ALPHA
        private val TCHAR: BooleanArray = BooleanArray(256).also { table ->
            for (c in "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz") {
                table[c.code] = true
            }
        }

        /**
         * Parses `length` bytes of [src] starting at [offset] as a method.
         *
         * Standard methods return the shared constants without allocating. Throws [InvalidMethod] if the
         * range is empty or contains a byte that is not a `tchar`.
         */
        fun fromBytes(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): Method =
            tryFromBytes(src, offset, length) ?: throw InvalidMethod()

        /** As [fromBytes], but returns null instead of throwing [InvalidMethod]. */
        fun tryFromBytes(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): Method? {
            if (offset < 0 || length < 0 || offset > src.size - length) {
                throw IndexOutOfBoundsException("offset=$offset length=$length size=${src.size}")
            }
            val standard = when (length) {
                0 -> return null
                3 -> when {
                    matches(src, offset, "GET") -> GET
                    matches(src, offset, "PUT") -> PUT
                    else -> null
                }
                4 -> when {
                    matches(src, offset, "POST") -> POST
                    matches(src, offset, "HEAD") -> HEAD
                    else -> null
                }
                5 -> when {
                    matches(src, offset, "PATCH") -> PATCH
                    matches(src, offset, "TRACE") -> TRACE
                    matches(src, offset, "QUERY") -> QUERY
                    else -> null
                }
                6 -> if (matches(src, offset, "DELETE")) DELETE else null
                7 -> when {
                    matches(src, offset, "OPTIONS") -> OPTIONS
                    matches(src, offset, "CONNECT") -> CONNECT
                    else -> null
                }
                else -> null
            }
            if (standard != null) return standard
            val table = TCHAR
            for (i in offset until offset + length) {
                if (!table[src[i].toInt() and 0xFF]) return null
            }
            // Every tchar is ASCII, so decoding cannot fail or change the length.
            return Method(K_EXTENSION, src.decodeToString(offset, offset + length))
        }

        /**
         * Parses [src] as a method (the reference's `FromStr` / `TryFrom<&str>`).
         *
         * Standard methods return the shared constants. Throws [InvalidMethod] if [src] is empty or contains
         * a character that is not a `tchar`.
         */
        fun fromStr(src: String): Method = tryFromStr(src) ?: throw InvalidMethod()

        /** As [fromStr], but returns null instead of throwing [InvalidMethod]. */
        fun tryFromStr(src: String): Method? {
            when (src) {
                "GET" -> return GET
                "PUT" -> return PUT
                "POST" -> return POST
                "HEAD" -> return HEAD
                "PATCH" -> return PATCH
                "TRACE" -> return TRACE
                "QUERY" -> return QUERY
                "DELETE" -> return DELETE
                "OPTIONS" -> return OPTIONS
                "CONNECT" -> return CONNECT
            }
            if (src.isEmpty()) return null
            val table = TCHAR
            for (i in src.indices) {
                val c = src[i].code
                if (c > 0xFF || !table[c]) return null
            }
            return Method(K_EXTENSION, src)
        }

        private fun matches(src: ByteArray, offset: Int, literal: String): Boolean {
            for (i in literal.indices) {
                if (src[offset + i].toInt() != literal[i].code) return false
            }
            return true
        }
    }
}
