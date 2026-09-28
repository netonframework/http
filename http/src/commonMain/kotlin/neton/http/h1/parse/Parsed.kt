package neton.http.h1.parse

/**
 * Caller-provided storage for parsed headers (the `&mut [Header]` slice handed to httparse).
 *
 * Header `i` (for `i < count`) has its name at `bytes[nameStart[i] until nameEnd[i]]` and its value at
 * `bytes[valueStart[i] until valueEnd[i]]`, where `bytes` is the array that was parsed. Offsets are absolute
 * indices into that array. Names are tchar only (hence ASCII); values are trimmed of surrounding whitespace and
 * may contain obs-text bytes (0x80-0xFF) or, with obs-fold enabled in responses, `CR LF` plus whitespace.
 *
 * [capacity] bounds how many headers a parse accepts: one more is [HttpParseError.TooManyHeaders].
 *
 * [count] is set only by a `Complete` parse. After a `Partial` or failed parse, [count] keeps the value it had
 * before the call (httparse puts the caller's slice back), although the slots themselves may have been
 * overwritten with the headers that were parsed before stopping, as in the reference.
 */
class HeaderSlots(
    /** Maximum number of headers. */
    val capacity: Int,
) {
    init {
        require(capacity >= 0) { "capacity must be >= 0: $capacity" }
    }

    /** Start offsets of the names. */
    val nameStart: IntArray = IntArray(capacity)

    /** End offsets (exclusive) of the names. */
    val nameEnd: IntArray = IntArray(capacity)

    /** Start offsets of the values. */
    val valueStart: IntArray = IntArray(capacity)

    /** End offsets (exclusive) of the values. */
    val valueEnd: IntArray = IntArray(capacity)

    /** Number of headers of the last `Complete` parse. */
    var count: Int = 0
        internal set

    /** Sets [count] to 0. */
    fun clear() {
        count = 0
    }

    internal fun set(i: Int, ns: Int, ne: Int, vs: Int, ve: Int) {
        nameStart[i] = ns
        nameEnd[i] = ne
        valueStart[i] = vs
        valueEnd[i] = ve
    }

    /** Name of header [i] as a String (allocates; a helper outside the parse path). */
    fun name(i: Int, bytes: ByteArray): String = bytes.decodeToString(nameStart[i], nameEnd[i])

    /** Value of header [i] as a copied byte array (allocates). */
    fun value(i: Int, bytes: ByteArray): ByteArray = bytes.copyOfRange(valueStart[i], valueEnd[i])

    /** Value of header [i] decoded as UTF-8, invalid sequences replaced (allocates). */
    fun valueString(i: Int, bytes: ByteArray): String = bytes.decodeToString(valueStart[i], valueEnd[i])

    /** Length in bytes of the value of header [i]. */
    fun valueLength(i: Int): Int = valueEnd[i] - valueStart[i]

    /** Whether the name of header [i] equals the ASCII [name], ignoring ASCII case (allocation-free). */
    fun nameEqualsIgnoreCase(i: Int, bytes: ByteArray, name: String): Boolean {
        val s = nameStart[i]
        val len = nameEnd[i] - s
        if (len != name.length) return false
        for (k in 0 until len) {
            var a = bytes[s + k].toInt() and 0xFF
            var b = name[k].code
            if (a in 'A'.code..'Z'.code) a += 32
            if (b in 'A'.code..'Z'.code) b += 32
            if (a != b) return false
        }
        return true
    }
}

/**
 * A parsed request head (httparse `lib.rs Request`), recorded as offsets into the parsed array.
 *
 * Fields are filled in as parsing progresses, so after a `Partial` result the parts that were complete can be
 * inspected (for example the path, to reject a request early). A field not reached holds `-1`. Every parse starts
 * by resetting the fields, as if the reference's `Request::new` had been called.
 *
 * Offsets are absolute indices into the array given to [parse]; that array is kept in [bytes] for the string
 * helpers.
 */
class ParsedRequest(
    /** Storage for the headers. */
    val headers: HeaderSlots,
) {
    /** The array of the last parse (null before the first). Not copied. */
    var bytes: ByteArray? = null
        private set

    /** Method start offset (`-1` if not parsed). */
    var methodStart: Int = -1
        private set

    /** Method end offset, exclusive. */
    var methodEnd: Int = -1
        private set

    /** Request-target start offset (`-1` if not parsed). */
    var pathStart: Int = -1
        private set

    /** Request-target end offset, exclusive. */
    var pathEnd: Int = -1
        private set

    /** Minor version: `0` for HTTP/1.0, `1` for HTTP/1.1, `-1` if not parsed. */
    var version: Int = -1
        private set

    /** Resets every field to "not parsed". [headers] keeps its contents. */
    fun reset() {
        bytes = null
        methodStart = -1; methodEnd = -1
        pathStart = -1; pathEnd = -1
        version = -1
    }

    /**
     * Parses the request head in `bytes[offset until offset + length]` (httparse `Request::parse`,
     * `ParserConfig::parse_request`).
     *
     * Returns a [ParseStatus] code: the number of bytes consumed from [offset] when complete (the body starts at
     * `offset + result`), [ParseStatus.PARTIAL], or a negative [HttpParseError.code]. Allocation-free.
     */
    fun parse(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        config: ParserConfig = ParserConfig.DEFAULT,
    ): Int {
        checkRange(bytes, offset, length)
        reset()
        this.bytes = bytes
        return parseRequestHead(this, bytes, offset, offset + length, config)
    }

    internal fun setMethod(s: Int, e: Int) { methodStart = s; methodEnd = e }
    internal fun setPath(s: Int, e: Int) { pathStart = s; pathEnd = e }
    internal fun setVersion(v: Int) { version = v }

    /** The method as a String, or null if not parsed (allocates). */
    fun methodString(): String? = if (methodStart < 0) null else bytes!!.decodeToString(methodStart, methodEnd)

    /** The request-target as a String, or null if not parsed (allocates; the bytes are valid UTF-8). */
    fun pathString(): String? = if (pathStart < 0) null else bytes!!.decodeToString(pathStart, pathEnd)
}

/**
 * A parsed response head (httparse `lib.rs Response`), recorded as offsets into the parsed array.
 * See [ParsedRequest] for the conventions.
 */
class ParsedResponse(
    /** Storage for the headers. */
    val headers: HeaderSlots,
) {
    /** The array of the last parse (null before the first). Not copied. */
    var bytes: ByteArray? = null
        private set

    /** Minor version: `0` for HTTP/1.0, `1` for HTTP/1.1, `-1` if not parsed. */
    var version: Int = -1
        private set

    /** Status code 0..999, `-1` if not parsed. */
    var code: Int = -1
        private set

    /**
     * Reason-phrase start offset (`-1` if not parsed). The reason is empty (`reasonStart == reasonEnd`) when it
     * was missing or contained obs-text.
     */
    var reasonStart: Int = -1
        private set

    /** Reason-phrase end offset, exclusive. */
    var reasonEnd: Int = -1
        private set

    /** Resets every field to "not parsed". [headers] keeps its contents. */
    fun reset() {
        bytes = null
        version = -1
        code = -1
        reasonStart = -1; reasonEnd = -1
    }

    /**
     * Parses the response head in `bytes[offset until offset + length]` (httparse `Response::parse`,
     * `ParserConfig::parse_response`). Returns a [ParseStatus] code as [ParsedRequest.parse]. Allocation-free.
     */
    fun parse(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        config: ParserConfig = ParserConfig.DEFAULT,
    ): Int {
        checkRange(bytes, offset, length)
        reset()
        this.bytes = bytes
        return parseResponseHead(this, bytes, offset, offset + length, config)
    }

    internal fun setVersion(v: Int) { version = v }
    internal fun setCode(c: Int) { code = c }
    internal fun setReason(s: Int, e: Int) { reasonStart = s; reasonEnd = e }

    /** The reason-phrase as a String, or null if not parsed (allocates). */
    fun reasonString(): String? = if (reasonStart < 0) null else bytes!!.decodeToString(reasonStart, reasonEnd)
}

// Inline check, message built out of line: the string template's temporaries made every call zero a frame.
@Suppress("NOTHING_TO_INLINE")
internal inline fun checkRange(bytes: ByteArray, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > bytes.size - length) rangeError(offset, length, bytes.size)
}

internal fun rangeError(offset: Int, length: Int, size: Int): Nothing =
    throw IndexOutOfBoundsException("offset=$offset length=$length size=$size")
