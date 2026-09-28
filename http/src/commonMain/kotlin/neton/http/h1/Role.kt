package neton.http.h1

import neton.http.Extensions
import neton.http.Method
import neton.http.RequestParts
import neton.http.ResponseParts
import neton.http.StatusCode
import neton.http.Version
import neton.http.h1.parse.HeaderSlots
import neton.http.h1.parse.HttpParseError
import neton.http.h1.parse.ParseStatus
import neton.http.h1.parse.ParsedRequest
import neton.http.h1.parse.ParsedResponse
import neton.http.h1.parse.ParserConfig
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.uri.Uri
import neton.io.bytes.Buffer

// hyper 1.11.1 `proto/h1/role.rs`: parsing a message head into its parts and the body length, and encoding an
// outgoing head with its length decision (SPEC §3.3). The safety-baseline deviations of SPEC §3.9 are marked ⚖️.

/** A decoded body length (hyper `DecodedLength`): a byte count ≥ 0, or [CHUNKED] / [CLOSE_DELIMITED]. */
object BodyLength {
    const val CHUNKED: Long = -1
    const val CLOSE_DELIMITED: Long = -2
    /** hyper accepts lengths up to u64::MAX - 2; a Long caps them at 2^63 - 1 (larger values are invalid lengths). */
    const val MAX: Long = Long.MAX_VALUE
}

/** Parse failures (hyper `error::Parse`), with the status of the automatic response where the server sends one. */
enum class H1ParseError(val autoStatus: Int) {
    Method(400), Version(400), VersionH2(0), Uri(400), UriTooLong(414),
    HeaderToken(400), ContentLengthInvalid(400), TransferEncodingInvalid(400), TransferEncodingUnexpected(400),
    /** ⚖️ Transfer-Encoding together with Content-Length (request smuggling vector, SPEC §3.9). */
    TransferEncodingWithContentLength(400),
    TooLarge(431), Status(0), Internal(0);

    companion object {
        /** hyper's `From<httparse::Error> for Parse`. */
        fun fromHttpParse(e: HttpParseError): H1ParseError = when (e) {
            HttpParseError.HeaderName, HttpParseError.HeaderValue, HttpParseError.NewLine, HttpParseError.Token -> HeaderToken
            HttpParseError.Status -> Status
            HttpParseError.TooManyHeaders -> TooLarge
            HttpParseError.Version -> Version
        }
    }
}

/** Configuration shared by both roles (hyper builder options relevant to head parsing / encoding, SPEC §3.7, §3.9). */
class H1Config(
    val parser: ParserConfig = ParserConfig.DEFAULT,
    /** Header count limit (hyper `h1_max_headers`, default 100). */
    val maxHeaders: Int = 100,
    /** ⚖️ Whole head (request / status line + headers) limit, SPEC §3.7: 64 KiB. */
    val maxHeaderSectionSize: Int = 64 * 1024,
    /** ⚖️ Request line limit, SPEC §3.9: 8 KiB (beyond → 414). */
    val maxRequestLineSize: Int = 8 * 1024,
    /** ⚖️ false: TE + CL in a request → 400 and close; true: hyper's behaviour (drop CL, process TE, close after). */
    val lenientTeWithCl: Boolean = false,
    val titleCaseHeaders: Boolean = false,
    /** Record the original case of header names in a [HeaderCaseMap] extension, and write it back (hyper `preserve_header_case`). */
    val preserveHeaderCase: Boolean = false,
    /** Client: accept an HTTP/0.9 response to the first request (hyper `http09_responses`). */
    val h09Responses: Boolean = false,
    /** ⚖️ Server: request body limit (SPEC §3.7); 0 disables. */
    val maxRequestBodySize: Long = 0,
)

/** A reason phrase different from the canonical one (hyper `ext::ReasonPhrase`), kept as an extension. */
class ReasonPhrase(private val bytes: ByteArray) {
    fun asBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = bytes.decodeToString()
    override fun equals(other: Any?): Boolean = other is ReasonPhrase && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

// ---- header helpers (hyper `headers.rs`) -----------------------------------------------------------------------

internal object H1Headers {
    /** Strict decimal digits, no sign, no whitespace, overflow → null (hyper `from_digits`). */
    fun fromDigits(a: ByteArray, s: Int, e: Int): Long? {
        if (s >= e) return null
        var r = 0L
        for (i in s until e) {
            val d = a[i] - '0'.code.toByte()
            if (d !in 0..9) return null
            if (r > (Long.MAX_VALUE - d) / 10) return null
            r = r * 10 + d
        }
        return r
    }

    fun contentLengthParse(v: HeaderValue): Long? = fromDigits(v.array, v.offset, v.offset + v.length)

    /** Client side (hyper `content_length_parse_all`): comma lists and repeats allowed, all values equal. */
    fun contentLengthParseAll(headers: HeaderMap<HeaderValue>): Long? {
        var len: Long? = null
        for (h in headers.getAll(HeaderName.CONTENT_LENGTH)) {
            val line = h.tryToStr() ?: return null
            for (v in line.split(',')) {
                val t = v.trim().encodeToByteArray()
                val n = fromDigits(t, 0, t.size) ?: return null
                if (len == null) len = n else if (len != n) return null
            }
        }
        return len
    }

    /**
     * A length that is valid digits but does not fit a Long (hyper takes up to u64::MAX - 2): reported as TooLarge,
     * like hyper's `checked_new` for the top of the u64 range. [contentLengthParseAll] rules apply to the list.
     */
    fun tooLargeForLong(headers: HeaderMap<HeaderValue>): Boolean {
        var first: ULong? = null
        for (h in headers.getAll(HeaderName.CONTENT_LENGTH)) {
            val line = h.tryToStr() ?: return false
            for (v in line.split(',')) {
                val t = v.trim()
                if (t.isEmpty() || t.any { it !in '0'..'9' }) return false
                val n = t.toULongOrNull() ?: return false
                if (first == null) first = n else if (first != n) return false
            }
        }
        return first != null && first > Long.MAX_VALUE.toULong()
    }

    fun tooLargeForLong(v: HeaderValue): Boolean {
        val t = v.tryToStr() ?: return false
        return t.isNotEmpty() && t.all { it in '0'..'9' } && (t.toULongOrNull() ?: return false) > Long.MAX_VALUE.toULong()
    }

    /** A `TE` header lists `trailers` (hyper `te_is_trailers`). */
    fun teIsTrailers(headers: HeaderMap<HeaderValue>): Boolean {
        if (!headers.containsKey(HeaderName.TE)) return false
        for (v in headers.getAll(HeaderName.TE)) if (listHas(v, "trailers")) return true
        return false
    }

    /** Any `Connection` line carries `close` (hyper `connection_any_close`). */
    fun connectionAnyClose(headers: HeaderMap<HeaderValue>): Boolean {
        if (!headers.containsKey(HeaderName.CONNECTION)) return false
        for (v in headers.getAll(HeaderName.CONNECTION)) if (connectionClose(v)) return true
        return false
    }

    private fun isWs(b: Byte) = b == ' '.code.toByte() || b == '\t'.code.toByte()

    /** Whether the comma-separated list in [v] contains [needle] (trimmed, ASCII case-insensitive). hyper `connection_has`. */
    fun listHas(v: HeaderValue, needle: String): Boolean {
        if (v.tryToStr() == null) return false
        var s = v.offset; val end = v.offset + v.length
        while (s <= end) {
            var e = s
            while (e < end && v.array[e] != ','.code.toByte()) e++
            if (tokenEquals(v.array, s, e, needle)) return true
            s = e + 1
        }
        return false
    }

    /** The last token of the comma list is `chunked` (hyper `is_chunked_`). */
    fun isChunked(v: HeaderValue): Boolean {
        if (v.tryToStr() == null) return false
        val end = v.offset + v.length
        var s = end
        while (s > v.offset && v.array[s - 1] != ','.code.toByte()) s--
        return tokenEquals(v.array, s, end, "chunked")
    }

    private fun tokenEquals(a: ByteArray, s0: Int, e0: Int, needle: String): Boolean {
        var s = s0; var e = e0
        while (s < e && isWs(a[s])) s++
        while (e > s && isWs(a[e - 1])) e--
        if (e - s != needle.length) return false
        for (i in needle.indices) {
            var c = a[s + i].toInt() and 0xff
            if (c in 'A'.code..'Z'.code) c += 32
            if (c != needle[i].code) return false
        }
        return true
    }

    fun connectionClose(v: HeaderValue) = listHas(v, "close")
    fun connectionKeepAlive(v: HeaderValue) = listHas(v, "keep-alive")
}

// ---- server: parse requests ---------------------------------------------------------------------------------

/**
 * Parses request heads for one connection (hyper `Server::parse`). Reuses its slots and result fields; the only
 * allocations per request are one copy of the head bytes (header values are slices of it), the header / URI objects
 * and the [RequestParts].
 */
class ServerHeadParser(val config: H1Config = H1Config()) {
    private val slots = HeaderSlots(config.maxHeaders)
    private val req = ParsedRequest(slots)

    // Result of the last parse (valid after a COMPLETE result).
    var parts: RequestParts? = null; private set
    /** [BodyLength] of the request body. */
    var bodyLength: Long = 0; private set
    var expectContinue: Boolean = false; private set
    var keepAlive: Boolean = false; private set
    var wantsUpgrade: Boolean = false; private set
    var error: H1ParseError? = null; private set

    /**
     * Parse `buf[off, off + len)`. Returns bytes consumed (> 0) when a head is complete (read [parts] and the other
     * fields), [ParseStatus.PARTIAL] when more bytes are needed, or -2 - ordinal of [error] on failure.
     */
    fun parse(buf: ByteArray, off: Int, len: Int): Int {
        parts = null; error = null
        val st = req.parse(buf, off, len, config.parser)
        if (st == ParseStatus.PARTIAL) {
            // ⚖️ Limits while incomplete: an unterminated request line past 8 KiB → 414; a head past 64 KiB → 431.
            if (len > config.maxRequestLineSize && !hasLineFeed(buf, off, config.maxRequestLineSize + 2)) return fail(H1ParseError.UriTooLong)
            if (len > config.maxHeaderSectionSize) return fail(H1ParseError.TooLarge)
            return ParseStatus.PARTIAL
        }
        if (st < 0) {
            val e = ParseStatus.error(st)!!
            // hyper: an invalid token is the method if none was parsed yet, else the URI.
            return fail(if (e == HttpParseError.Token) { if (req.methodStart < 0) H1ParseError.Method else H1ParseError.Uri } else H1ParseError.fromHttpParse(e))
        }
        val consumed = st
        if (consumed > config.maxHeaderSectionSize) return fail(H1ParseError.TooLarge)
        if (req.pathEnd - req.pathStart > MAX_URI_LEN) return fail(H1ParseError.UriTooLong)
        if (requestLineLength(buf) > config.maxRequestLineSize) return fail(H1ParseError.UriTooLong)
        val method = Method.tryFromBytes(buf, req.methodStart, req.methodEnd - req.methodStart) ?: return fail(H1ParseError.Method)
        val isHttp11 = req.version == 1
        var keepAlive = isHttp11
        val version = if (isHttp11) Version.HTTP_11 else Version.HTTP_10

        // One copy of the head: values and the URI are slices of it.
        val head = buf.copyOfRange(off, off + consumed)
        val uri = Uri.tryFromBytes(head, req.pathStart - off, req.pathEnd - req.pathStart) ?: return fail(H1ParseError.Uri)

        var decoder = 0L
        var expectContinue = false
        var conLen: Long? = null
        var isCl = false; var isTe = false; var isTeChunked = false
        var wantsUpgrade = method == Method.CONNECT
        val headers = HeaderMap<HeaderValue>()
        if (slots.count > 0) headers.reserve(slots.count)
        val caseMap = if (config.preserveHeaderCase) HeaderCaseMap() else null
        for (i in 0 until slots.count) {
            if (slots.nameEnd[i] - slots.nameStart[i] >= 1 shl 16) return fail(H1ParseError.TooLarge)      // hyper record_header_indices
            // httparse validated the name: a failure here is hyper's `maybe_panic!` (Internal in release builds).
            val name = HeaderName.tryFromBytes(head, slots.nameStart[i] - off, slots.nameEnd[i] - slots.nameStart[i]) ?: return fail(H1ParseError.Internal)
            val value = HeaderValue.fromMaybeSharedUnchecked(head, slots.valueStart[i] - off, slots.valueEnd[i] - slots.valueStart[i])
            when {
                name === HeaderName.TRANSFER_ENCODING -> {
                    if (!isHttp11) return fail(H1ParseError.TransferEncodingUnexpected)
                    isTe = true
                    if (isCl && conLen != null) { conLen = null; headers.remove(HeaderName.CONTENT_LENGTH) }
                    if (H1Headers.isChunked(value)) { isTeChunked = true; decoder = BodyLength.CHUNKED } else isTeChunked = false
                }
                name === HeaderName.CONTENT_LENGTH -> {
                    isCl = true
                    if (isTe) continue
                    val n = H1Headers.contentLengthParse(value)
                        ?: return fail(if (H1Headers.tooLargeForLong(value)) H1ParseError.TooLarge else H1ParseError.ContentLengthInvalid)
                    if (conLen != null) {
                        if (conLen != n) return fail(H1ParseError.ContentLengthInvalid)
                        continue                                  // identical duplicate: merged, not appended
                    }
                    decoder = n; conLen = n
                }
                name === HeaderName.CONNECTION -> keepAlive = if (keepAlive) !H1Headers.connectionClose(value) else H1Headers.connectionKeepAlive(value)
                name === HeaderName.EXPECT -> expectContinue = value.length == 12 && value.tryToStr()?.equals("100-continue", ignoreCase = true) == true
                name === HeaderName.UPGRADE -> wantsUpgrade = isHttp11
            }
            caseMap?.append(name, head.copyOfRange(slots.nameStart[i] - off, slots.nameEnd[i] - off))
            headers.append(name, value)
        }
        if (isTe && !isTeChunked) return fail(H1ParseError.TransferEncodingInvalid)
        if (isTe && isCl) {
            if (!config.lenientTeWithCl) return fail(H1ParseError.TransferEncodingWithContentLength)
            keepAlive = false
        }
        val extensions = if (caseMap != null) Extensions().also { it.insert(caseMap) } else null
        parts = RequestParts(method, uri, version, headers, extensions)
        this.bodyLength = decoder
        this.expectContinue = expectContinue
        this.keepAlive = keepAlive
        this.wantsUpgrade = wantsUpgrade
        return consumed
    }

    private fun fail(e: H1ParseError): Int { error = e; return -2 - e.ordinal }

    private fun hasLineFeed(buf: ByteArray, off: Int, n: Int): Boolean {
        for (i in off until off + n) if (i < buf.size && buf[i] == '\n'.code.toByte()) return true
        return false
    }

    /** Length of the request line (method ... version), skipping leading empty lines as httparse does. */
    private fun requestLineLength(buf: ByteArray): Int {
        var i = req.methodStart
        while (i < buf.size && buf[i] != '\n'.code.toByte()) i++
        return i - req.methodStart
    }

    companion object {
        /** hyper `MAX_URI_LEN`: u16::MAX - 1. */
        const val MAX_URI_LEN = 65534
    }
}

// ---- client: parse responses ----------------------------------------------------------------------------------

/** Parses response heads for one client connection (hyper `Client::parse` + `Client::decoder`). */
class ClientHeadParser(val config: H1Config = H1Config()) {
    private val slots = HeaderSlots(config.maxHeaders)
    private val res = ParsedResponse(slots)

    var parts: ResponseParts? = null; private set
    var bodyLength: Long = 0; private set
    var keepAlive: Boolean = false; private set
    var wantsUpgrade: Boolean = false; private set
    var error: H1ParseError? = null; private set
    /** Informational responses (1xx other than 101) skipped by the last parse, in order (hyper `on_informational`). */
    val informational = ArrayList<ResponseParts>()

    /**
     * Parses the response to a request with [reqMethod] (null if unknown) from `buf[off, off + len)`. Returns the
     * bytes consumed, including skipped 1xx responses, once a final head is complete; [ParseStatus.PARTIAL] when
     * more bytes are needed (the caller keeps the whole range, 1xx heads included, and parses again); or a failure
     * code < -1 (see [error]). [h09]: accept an HTTP/0.9 response (the connection allows it for its first response
     * only when [H1Config.h09Responses] is set).
     */
    fun parse(buf: ByteArray, off: Int, len: Int, reqMethod: Method?, h09: Boolean = false): Int {
        parts = null; error = null; informational.clear()
        var at = off
        val end = off + len
        while (true) {
            if (at >= end) return ParseStatus.PARTIAL        // parsing a 1xx response consumed the buffer
            val st = res.parse(buf, at, end - at, config.parser)
            val consumed: Int
            val status: StatusCode
            val version: Version
            var reason: ReasonPhrase? = null
            val headerCount: Int
            if (st == ParseStatus.PARTIAL) {
                if (end - at > config.maxHeaderSectionSize) return fail(H1ParseError.TooLarge)   // ⚖️ SPEC §3.7
                return ParseStatus.PARTIAL
            } else if (st < 0) {
                val e = ParseStatus.error(st)!!
                if (e != HttpParseError.Version || !h09) return fail(H1ParseError.fromHttpParse(e))
                // HTTP/0.9: no head at all.
                consumed = 0; status = StatusCode.OK; version = Version.HTTP_09; headerCount = 0
            } else {
                consumed = st
                if (consumed > config.maxHeaderSectionSize) return fail(H1ParseError.TooLarge)
                status = StatusCode.tryFromU16(res.code) ?: return fail(H1ParseError.Status)
                version = if (res.version == 1) Version.HTTP_11 else Version.HTTP_10
                // Only a reason phrase other than the canonical one is kept.
                val canonical = status.canonicalReason()
                if (canonical == null || !sameAscii(buf, res.reasonStart, res.reasonEnd - res.reasonStart, canonical)) {
                    reason = ReasonPhrase(buf.copyOfRange(res.reasonStart, res.reasonEnd))
                }
                headerCount = slots.count
            }
            val head = buf.copyOfRange(at, at + consumed)
            if (config.parser.allowObsoleteMultilineHeadersInResponses) {
                for (i in 0 until headerCount) slots.valueEnd[i] = at + obsFoldLine(head, slots.valueStart[i] - at, slots.valueEnd[i] - at)
            }
            var keepAlive = version == Version.HTTP_11
            val headers = HeaderMap<HeaderValue>()
            if (headerCount > 0) headers.reserve(headerCount)
            val caseMap = if (config.preserveHeaderCase) HeaderCaseMap() else null
            for (i in 0 until headerCount) {
                if (slots.nameEnd[i] - slots.nameStart[i] >= 1 shl 16) return fail(H1ParseError.TooLarge)
                val name = HeaderName.tryFromBytes(head, slots.nameStart[i] - at, slots.nameEnd[i] - slots.nameStart[i])
                    ?: return fail(H1ParseError.Internal)
                val value = HeaderValue.fromMaybeSharedUnchecked(head, slots.valueStart[i] - at, slots.valueEnd[i] - slots.valueStart[i])
                if (name === HeaderName.CONNECTION) {
                    keepAlive = if (keepAlive) !H1Headers.connectionClose(value) else H1Headers.connectionKeepAlive(value)
                }
                caseMap?.append(name, head.copyOfRange(slots.nameStart[i] - at, slots.nameEnd[i] - at))
                headers.append(name, value)
            }
            val extensions = if (caseMap != null || reason != null) Extensions() else null
            if (caseMap != null) extensions!!.insert(caseMap)
            if (reason != null) extensions!!.insert(reason)
            val p = ResponseParts(status, version, headers, extensions)
            at += consumed
            val length = decoder(p, reqMethod)
            if (length == SKIP) { informational.add(p); continue }
            if (length == FAILED) return error.let { -2 - it!!.ordinal }
            parts = p
            bodyLength = length
            // A client upgrade means the connection cannot be used again.
            this.keepAlive = keepAlive && !wantsUpgrade
            return at - off
        }
    }

    /** hyper `Client::decoder`: the body length, [SKIP] for an informational response, or [FAILED]. Sets [wantsUpgrade]. */
    private fun decoder(p: ResponseParts, method: Method?): Long {
        wantsUpgrade = false
        when (val code = p.status.asU16()) {
            101 -> { wantsUpgrade = true; return 0 }
            in 100..199 -> return SKIP
            204, 304 -> return 0
            else -> {
                if (method == Method.HEAD) return 0
                if (method == Method.CONNECT && code in 200..299) { wantsUpgrade = true; return 0 }
            }
        }
        val headers = p.headers
        if (headers.containsKey(HeaderName.TRANSFER_ENCODING)) {
            if (p.version == Version.HTTP_10) { error = H1ParseError.TransferEncodingUnexpected; return FAILED }
            // ⚖️ SPEC §3.9: Transfer-Encoding with Content-Length is rejected (hyper ignores the length).
            if (headers.containsKey(HeaderName.CONTENT_LENGTH)) { error = H1ParseError.TransferEncodingWithContentLength; return FAILED }
            var last: HeaderValue? = null
            for (v in headers.getAll(HeaderName.TRANSFER_ENCODING)) last = v
            return if (H1Headers.isChunked(last!!)) BodyLength.CHUNKED else BodyLength.CLOSE_DELIMITED
        }
        if (headers.containsKey(HeaderName.CONTENT_LENGTH)) {
            return H1Headers.contentLengthParseAll(headers) ?: run {
                error = if (H1Headers.tooLargeForLong(headers)) H1ParseError.TooLarge else H1ParseError.ContentLengthInvalid
                FAILED
            }
        }
        return BodyLength.CLOSE_DELIMITED
    }

    private fun fail(e: H1ParseError): Int { error = e; return -2 - e.ordinal }

    private fun sameAscii(buf: ByteArray, s: Int, n: Int, str: String): Boolean {
        if (n != str.length) return false
        for (i in 0 until n) if ((buf[s + i].toInt() and 0xff) != str[i].code) return false
        return true
    }

    private companion object {
        const val SKIP = -10L
        const val FAILED = -11L

        private fun isWs(b: Byte) = b == 0x20.toByte() || b == 0x09.toByte() || b == 0x0A.toByte() || b == 0x0C.toByte() || b == 0x0D.toByte()

        /**
         * hyper `obs_fold_line`: each obs-fold becomes one space; the value is unfolded in place in `a[s, e)`.
         * Returns the new end.
         */
        fun obsFoldLine(a: ByteArray, s: Int, e: Int): Int {
            var nl = -1
            for (i in s until e) if (a[i] == '\n'.code.toByte()) { nl = i; break }
            if (nl < 0) return e
            val out = ArrayList<Byte>(e - s)
            var t = nl
            while (t > s && isWs(a[t - 1])) t--
            for (i in s until t) out.add(a[i])
            var ls = nl + 1
            while (true) {
                var le = ls
                while (le < e && a[le] != '\n'.code.toByte()) le++
                var x = ls; var y = le
                while (x < y && isWs(a[x])) x++
                while (y > x && isWs(a[y - 1])) y--
                out.add(' '.code.toByte())
                for (i in x until y) out.add(a[i])
                if (le >= e) break
                ls = le + 1
            }
            for (i in out.indices) a[s + i] = out[i]
            return s + out.size
        }
    }
}

// ---- encoding ---------------------------------------------------------------------------------------------------

/** How the body of an outgoing message is framed (hyper `Encoder`), decided while encoding its head. */
class EncodePlan(
    /** [LENGTH], [CHUNKED] or [CLOSE_DELIMITED]. */
    kind: Int = LENGTH,
    /** For [LENGTH]: the byte count. */
    length: Long = 0,
    /** For [CHUNKED]: the trailer fields the `Trailer` header allows (null = none declared). */
    allowedTrailers: List<HeaderName>? = null,
    /** Close the connection after this message (hyper `is_last`). */
    isLast: Boolean = false,
    /**
     * Set when encoding failed. [H1EncodeError.UnexpectedHeader]: nothing was written. [H1EncodeError.UnsupportedStatusCode]:
     * a 500 head was written in place of the 1xx response, and the connection closes after it (hyper).
     */
    error: H1EncodeError? = null,
) {
    var kind = kind; internal set
    var length = length; internal set
    var allowedTrailers = allowedTrailers; internal set
    var isLast = isLast; internal set
    var error = error; internal set

    internal fun set(kind: Int, length: Long, allowedTrailers: List<HeaderName>?, isLast: Boolean, error: H1EncodeError?): EncodePlan {
        this.kind = kind; this.length = length; this.allowedTrailers = allowedTrailers; this.isLast = isLast; this.error = error
        return this
    }

    /** The body encoder this plan describes. */
    fun encoder(): BodyEncoder = when (kind) {
        CHUNKED -> BodyEncoder.chunked().let { t -> allowedTrailers?.let { t.withTrailerFields(it) } ?: t }
        CLOSE_DELIMITED -> BodyEncoder.closeDelimited()
        else -> BodyEncoder.length(length)
    }.also { it.setLast(isLast) }

    companion object { const val LENGTH = 0; const val CHUNKED = 1; const val CLOSE_DELIMITED = 2 }
}

/** An outgoing body length (hyper `BodyLength`): null = no body, [UNKNOWN] = streaming, else the byte count. */
object OutgoingBody { const val UNKNOWN: Long = -1 }

/** Errors while encoding a head (hyper user errors). */
enum class H1EncodeError { UnexpectedHeader, UnsupportedStatusCode }

/** Encodes response heads (hyper `Server::encode` + `encode_headers`). */
object ServerHeadEncoder {
    fun canChunked(method: Method?, status: StatusCode): Boolean =
        !(method == Method.HEAD || method == Method.CONNECT && status.isSuccess() || status.isInformational()) &&
            status != StatusCode.NO_CONTENT && status != StatusCode.NOT_MODIFIED

    fun canHaveContentLength(method: Method?, status: StatusCode): Boolean =
        !(status.isInformational() || method == Method.CONNECT && status.isSuccess()) &&
            status != StatusCode.NO_CONTENT && status != StatusCode.NOT_MODIFIED

    /**
     * Appends the status line and headers of [parts] to [dst]. [body]: null (no body), [OutgoingBody.UNKNOWN] or a
     * length. [reqMethod]: the request's method. [keepAlive]: the connection may stay open after this message.
     */
    fun encode(
        parts: ResponseParts, body: Long?, reqMethod: Method?, keepAlive: Boolean, config: H1Config, dateHeader: Boolean, dst: Buffer,
    ): EncodePlan = encodeInto(parts, body, reqMethod, keepAlive, config, dateHeader, dst, EncodePlan())

    /** [encode] filling [into] (a connection reuses one plan). */
    internal fun encodeInto(
        parts: ResponseParts, body: Long?, reqMethod: Method?, keepAlive: Boolean, config: H1Config, dateHeader: Boolean, dst: Buffer,
        into: EncodePlan,
    ): EncodePlan {
        var wroteLen = false
        var isLast: Boolean
        var ret: H1EncodeError? = null
        var body = body
        val status0 = parts.status
        when {
            status0 == StatusCode.SWITCHING_PROTOCOLS -> isLast = true
            reqMethod == Method.CONNECT && status0.isSuccess() -> { wroteLen = true; isLast = true }   // no CL / TE (RFC 7231)
            status0.isInformational() -> {
                // hyper: a service cannot return a 1xx response; a default 500 head goes out instead.
                parts.status = StatusCode.INTERNAL_SERVER_ERROR; parts.version = Version.HTTP_11
                parts.headers.clear(); parts.extensions.clear(); body = null
                ret = H1EncodeError.UnsupportedStatusCode; isLast = true
            }
            else -> isLast = !keepAlive
        }
        val origLen = dst.readableBytes
        val status = parts.status
        val reason = parts.extensionsOrNull?.get<ReasonPhrase>()
        if (parts.version == Version.HTTP_11 && status == StatusCode.OK && reason == null) {
            dst.writeBytes(STATUS_LINE_200)
        } else {
            dst.writeBytes(if (parts.version == Version.HTTP_10) HTTP10_SP else HTTP11_SP)   // HTTP/2 coerced to HTTP/1.1
            ascii(dst, status.asStr()); dst.writeByte(' '.code.toByte())
            if (reason != null) dst.writeBytes(reason.asBytes()) else ascii(dst, status.canonicalReason() ?: "<none>")
            dst.writeBytes(CRLF_BYTES)
        }

        val caseMap = parts.extensionsOrNull?.get<HeaderCaseMap>()
        val w = if (caseMap != null || config.titleCaseHeaders) OrigCaseWriter(caseMap, config.titleCaseHeaders) else null
        val canChunked = canChunked(reqMethod, status)
        var kind = EncodePlan.LENGTH
        var length = 0L
        var allowed: ArrayList<HeaderName>? = null
        var wroteDate = false
        var curName: HeaderName? = null
        var isNameWritten = false
        var mustWriteChunked = false
        var prevConLen = -1L
        var failed = false

        parts.headersOrNull?.forEach { name, value ->
            if (failed) return@forEach
            if (name !== curName) {
                // A new name: finish a joined line left open by the previous one (handle_is_name_written).
                if (isNameWritten) dst.writeBytes(if (mustWriteChunked) COMMA_CHUNKED_CRLF else CRLF_BYTES)
                isNameWritten = false
                curName = name
            }
            when {
                name === HeaderName.CONTENT_LENGTH -> {
                    if (wroteLen && !isNameWritten) { failed = true; return@forEach }
                    when {
                        body != null && body != OutgoingBody.UNKNOWN -> {
                            // The body knows its length: trust it matches the header (hyper), write the first value only.
                            if (!isNameWritten) {
                                kind = EncodePlan.LENGTH; length = body
                                writeName(dst, HeaderName.CONTENT_LENGTH, w); dst.writeBytes(COLON_SP); writeValue(dst, value)
                                wroteLen = true; isNameWritten = true
                            }
                            return@forEach
                        }
                        body == OutgoingBody.UNKNOWN -> {
                            val len = H1Headers.contentLengthParse(value)
                            if (len == null) { failed = true; return@forEach }
                            if (prevConLen >= 0) {
                                if (prevConLen != len) failed = true
                                return@forEach
                            }
                            kind = EncodePlan.LENGTH; length = len
                            writeName(dst, HeaderName.CONTENT_LENGTH, w); dst.writeBytes(COLON_SP); writeValue(dst, value)
                            wroteLen = true; isNameWritten = true; prevConLen = len
                            return@forEach
                        }
                        // No body: the header only makes sense for HEAD (written as is) or when it says 0 (dropped).
                        reqMethod != Method.HEAD -> return@forEach
                    }
                    wroteLen = true
                }
                name === HeaderName.TRANSFER_ENCODING -> {
                    if (wroteLen && !isNameWritten) { failed = true; return@forEach }
                    if (parts.version == Version.HTTP_10 || !canChunked) return@forEach
                    wroteLen = true
                    mustWriteChunked = !H1Headers.isChunked(value)     // `chunked` must be last, else it is added
                    if (!isNameWritten) {
                        kind = EncodePlan.CHUNKED; isNameWritten = true
                        writeName(dst, HeaderName.TRANSFER_ENCODING, w); dst.writeBytes(COLON_SP); writeValue(dst, value)
                    } else { dst.writeBytes(COMMA_SP); writeValue(dst, value) }
                    return@forEach
                }
                name === HeaderName.CONNECTION -> {
                    if (!isLast && H1Headers.connectionClose(value)) isLast = true
                    if (!isNameWritten) { isNameWritten = true; writeName(dst, HeaderName.CONNECTION, w); dst.writeBytes(COLON_SP); writeValue(dst, value) }
                    else { dst.writeBytes(COMMA_SP); writeValue(dst, value) }
                    return@forEach
                }
                name === HeaderName.DATE -> wroteDate = true
                name === HeaderName.TRAILER -> {
                    if (parts.version == Version.HTTP_10 || !canChunked) return@forEach
                    if (!isNameWritten) { isNameWritten = true; writeName(dst, HeaderName.TRAILER, w); dst.writeBytes(COLON_SP); writeValue(dst, value) }
                    else { dst.writeBytes(COMMA_SP); writeValue(dst, value) }
                    val list = allowed ?: ArrayList<HeaderName>().also { allowed = it }
                    parseNameList(value, list)
                    return@forEach
                }
            }
            writeName(dst, name, w); dst.writeBytes(COLON_SP); writeValue(dst, value); dst.writeBytes(CRLF_BYTES)
        }
        if (failed) {
            rewind(dst, origLen)
            return into.set(EncodePlan.LENGTH, 0, null, true, H1EncodeError.UnexpectedHeader)
        }
        if (isNameWritten) dst.writeBytes(if (mustWriteChunked) COMMA_CHUNKED_CRLF else CRLF_BYTES)

        if (!wroteLen) {
            when {
                body == OutgoingBody.UNKNOWN ->
                    if (parts.version == Version.HTTP_10 || !canChunked) kind = EncodePlan.CLOSE_DELIMITED
                    else { writeName(dst, HeaderName.TRANSFER_ENCODING, w); dst.writeBytes(COLON_CHUNKED_CRLF); kind = EncodePlan.CHUNKED }
                body == null || body == 0L -> {
                    if (canHaveContentLength(reqMethod, status) && reqMethod != Method.HEAD) {
                        writeName(dst, HeaderName.CONTENT_LENGTH, w); dst.writeBytes(COLON_ZERO_CRLF)
                    }
                    kind = EncodePlan.LENGTH; length = 0
                }
                !canHaveContentLength(reqMethod, status) -> { kind = EncodePlan.LENGTH; length = 0 }
                else -> {
                    writeName(dst, HeaderName.CONTENT_LENGTH, w); dst.writeBytes(COLON_SP); writeDecimal(dst, body); dst.writeBytes(CRLF_BYTES)
                    kind = EncodePlan.LENGTH; length = body
                }
            }
        }
        if (!canChunked) { kind = EncodePlan.LENGTH; length = 0 }                  // server body forced to 0 (can_have_body)
        if (!wroteDate && dateHeader) {
            writeName(dst, HeaderName.DATE, w); dst.writeBytes(COLON_SP); dst.writeBytes(HttpDate.nowBytes()); dst.writeBytes(CRLF_CRLF)
        } else dst.writeBytes(CRLF_BYTES)
        return into.set(kind, length, if (kind == EncodePlan.CHUNKED) allowed else null, isLast, ret)
    }

    /** Drops what this call appended (hyper `dst.truncate(orig_len)`); a cold path. */
    private fun rewind(dst: Buffer, origLen: Int) {
        val keep = dst.peekAll().copyOf(origLen)
        dst.clear(); dst.writeBytes(keep)
    }
}

/** Encodes request heads (hyper `Client::encode` + `Client::set_length`). */
object ClientHeadEncoder {
    fun encode(parts: RequestParts, body: Long?, config: H1Config, dst: Buffer): EncodePlan {
        val plan = setLength(parts, body)
        ascii(dst, parts.method.asStr()); dst.writeByte(' '.code.toByte())
        ascii(dst, parts.uri.toString()); dst.writeByte(' '.code.toByte())
        dst.writeBytes(if (parts.version == Version.HTTP_10) HTTP10_CRLF else HTTP11_CRLF)   // HTTP/2 coerced to HTTP/1.1
        val caseMap = parts.extensions.get<HeaderCaseMap>()
        if (caseMap != null) writeHeadersOriginalCase(parts.headers, caseMap, dst, config.titleCaseHeaders)
        else {
            val w = if (config.titleCaseHeaders) OrigCaseWriter(null, true) else null
            parts.headers.forEach { name, value -> writeName(dst, name, w); dst.writeBytes(COLON_SP); writeValue(dst, value); dst.writeBytes(CRLF_BYTES) }
        }
        dst.writeBytes(CRLF_BYTES)
        return plan
    }

    /** hyper `set_length`: decides the request body framing, fixing the headers to match. */
    fun setLength(parts: RequestParts, body: Long?): EncodePlan {
        val headers = parts.headers
        if (body == null) {
            headers.remove(HeaderName.TRANSFER_ENCODING)
            return EncodePlan(EncodePlan.LENGTH, 0, null, false)
        }
        val existingCl = H1Headers.contentLengthParseAll(headers)
        if (parts.version != Version.HTTP_11) {
            // HTTP/1.0 knows no chunked: without a length there is no body at all.
            headers.remove(HeaderName.TRANSFER_ENCODING)
            return when {
                existingCl != null -> EncodePlan(EncodePlan.LENGTH, existingCl, null, false)
                body != OutgoingBody.UNKNOWN -> setContentLength(headers, body)
                else -> EncodePlan(EncodePlan.LENGTH, 0, null, false)
            }
        }
        val chunked: Boolean
        if (headers.containsKey(HeaderName.TRANSFER_ENCODING)) {
            // Respect the user's Transfer-Encoding, making sure `chunked` is the last coding (hyper `add_chunked`).
            var last: HeaderValue? = null
            for (v in headers.getAll(HeaderName.TRANSFER_ENCODING)) last = v
            if (!H1Headers.isChunked(last!!)) addChunked(headers)
            if (existingCl != null) headers.remove(HeaderName.CONTENT_LENGTH)
            chunked = true
        } else if (existingCl != null) {
            return EncodePlan(EncodePlan.LENGTH, existingCl, null, false)
        } else if (body == OutgoingBody.UNKNOWN) {
            // GET, HEAD and CONNECT almost never have bodies: assume none rather than a chunked empty body.
            if (parts.method == Method.GET || parts.method == Method.HEAD || parts.method == Method.CONNECT) {
                return EncodePlan(EncodePlan.LENGTH, 0, null, false)
            }
            headers.insert(HeaderName.TRANSFER_ENCODING, HeaderValue.fromStatic("chunked"))
            chunked = true
        } else {
            return setContentLength(headers, body)
        }
        check(chunked)
        val allowed = ArrayList<HeaderName>()
        for (v in headers.getAll(HeaderName.TRAILER)) parseNameList(v, allowed)
        return EncodePlan(EncodePlan.CHUNKED, 0, allowed.takeIf { it.isNotEmpty() }, false)
    }

    private fun setContentLength(headers: HeaderMap<HeaderValue>, len: Long): EncodePlan {
        headers.insert(HeaderName.CONTENT_LENGTH, HeaderValue.fromStr(len.toString()))
        return EncodePlan(EncodePlan.LENGTH, len, null, false)
    }

    /** Appends `, chunked` to the last Transfer-Encoding value (hyper `headers::add_chunked`). */
    private fun addChunked(headers: HeaderMap<HeaderValue>) {
        val all = ArrayList<HeaderValue>()
        for (v in headers.getAll(HeaderName.TRANSFER_ENCODING)) all.add(v)
        headers.remove(HeaderName.TRANSFER_ENCODING)
        for (i in all.indices) {
            val v = if (i < all.lastIndex) all[i] else HeaderValue.fromBytes(all[i].asBytes() + ", chunked".encodeToByteArray())
            headers.append(HeaderName.TRANSFER_ENCODING, v)
        }
    }
}

/** The header names of a comma-separated list value (a `Trailer` header), invalid ones skipped. */
private fun parseNameList(value: HeaderValue, into: MutableList<HeaderName>) {
    val s = value.tryToStr() ?: return
    for (t in s.split(',')) HeaderName.tryFromBytes(t.trim().encodeToByteArray())?.let { into.add(it) }
}

// ---- writing helpers ------------------------------------------------------------------------------------------

private fun writeValue(dst: Buffer, v: HeaderValue) = dst.writeBytes(v.array, v.offset, v.length)

private val STATUS_LINE_200 = "HTTP/1.1 200 OK\r\n".encodeToByteArray()
private val CRLF_BYTES = "\r\n".encodeToByteArray()
private val COMMA_CHUNKED_CRLF = ", chunked\r\n".encodeToByteArray()
private val COLON_SP = ": ".encodeToByteArray()
private val COMMA_SP = ", ".encodeToByteArray()
private val COLON_CHUNKED_CRLF = ": chunked\r\n".encodeToByteArray()
private val COLON_ZERO_CRLF = ": 0\r\n".encodeToByteArray()
private val CRLF_CRLF = "\r\n\r\n".encodeToByteArray()
private val HTTP10_CRLF = "HTTP/1.0\r\n".encodeToByteArray()
private val HTTP11_CRLF = "HTTP/1.1\r\n".encodeToByteArray()
private val COLON_CRLF = ":\r\n".encodeToByteArray()
private val HTTP10_SP = "HTTP/1.0 ".encodeToByteArray()
private val HTTP11_SP = "HTTP/1.1 ".encodeToByteArray()

/** Writes [value] ≥ 0 in decimal without creating a String. */
private fun writeDecimal(dst: Buffer, value: Long) {
    var digits = 1
    var v = value
    while (v >= 10) { v /= 10; digits++ }
    dst.reserve(digits)
    val a = dst.backingArray()
    var at = dst.writerIndex() + digits
    v = value
    do { a[--at] = ('0'.code + (v % 10).toInt()).toByte(); v /= 10 } while (v > 0)
    dst.commitWrite(digits)
}

private fun ascii(dst: Buffer, s: String) {
    for (c in s) dst.writeByte(c.code.toByte())
}

/** hyper `title_case`: upper-case the first letter and every letter after a `-`. */
private fun titleCase(dst: Buffer, name: String) {
    var prev = '-'
    for (c0 in name) {
        val c = if (prev == '-' && c0 in 'a'..'z') c0 - 32 else c0
        dst.writeByte(c.code.toByte())
        prev = c
    }
}

/** Writes a header name: lower case ([w] null), or through the original-case / title-case writer. */
private fun writeName(dst: Buffer, name: HeaderName, w: OrigCaseWriter?) {
    if (w == null) dst.writeBytes(name.bytes) else w.write(dst, name)
}

/**
 * hyper `OrigCaseWriter`: each value of a name takes the next recorded original spelling; without one the name is
 * written in title case ([titleCase]) or as is.
 */
private class OrigCaseWriter(private val map: HeaderCaseMap?, private val titleCase: Boolean) {
    private var current: HeaderName? = null
    private var values: Iterator<ByteArray>? = null

    fun write(dst: Buffer, name: HeaderName) {
        if (current != name) { current = name; values = map?.getAll(name)?.iterator() }
        val v = values
        when {
            v != null && v.hasNext() -> dst.writeBytes(v.next())
            titleCase -> titleCase(dst, name.asStr())
            else -> dst.writeBytes(name.bytes)
        }
    }
}

/** hyper `write_headers_original_case` (client): an empty value is written as `Name:` with no space. */
internal fun writeHeadersOriginalCase(headers: HeaderMap<HeaderValue>, caseMap: HeaderCaseMap, dst: Buffer, titleCaseHeaders: Boolean) {
    val w = OrigCaseWriter(caseMap, titleCaseHeaders)
    headers.forEach { name, value ->
        w.write(dst, name)
        if (value.length == 0) dst.writeBytes(COLON_CRLF) else { dst.writeBytes(COLON_SP); writeValue(dst, value); dst.writeBytes(CRLF_BYTES) }
    }
}

/**
 * The original spelling of header names, in order per name (hyper `ext::HeaderCaseMap`). Filled by the parsers
 * with [H1Config.preserveHeaderCase]; used by the encoders when present in a message's extensions.
 */
class HeaderCaseMap {
    private val map = HeaderMap<ByteArray>()
    fun getAll(name: HeaderName): List<ByteArray> = map.getAll(name).toList()
    fun insert(name: HeaderName, orig: ByteArray) { map.insert(name, orig) }
    fun append(name: HeaderName, orig: ByteArray) { map.append(name, orig) }
}

/**
 * hyper `is_complete_fast`: whether `buf[off, off + len)` holds the end of a head, scanning only from 3 bytes before
 * [prevLen] (the length already scanned by an earlier partial parse). Used to skip full parses on slow connections.
 */
fun isCompleteFast(buf: ByteArray, off: Int, len: Int, prevLen: Int): Boolean {
    val end = off + len
    var i = off + maxOf(prevLen - 3, 0)
    while (i < end) {
        val b = buf[i]
        if (b == CR) {
            if (i + 3 < end && buf[i + 1] == LF && buf[i + 2] == CR && buf[i + 3] == LF) return true
        } else if (b == LF) {
            if (i + 1 < end && buf[i + 1] == LF) return true
            if (i + 2 < end && buf[i + 1] == CR && buf[i + 2] == LF) return true
        }
        i++
    }
    return false
}

private const val CR = '\r'.code.toByte()
private const val LF = '\n'.code.toByte()

/** The IMF-fixdate `Date` value (29 bytes), recomputed at most once per second per thread (hyper `common/date.rs`). */
internal object HttpDate {
    @kotlin.native.concurrent.ThreadLocal
    private object Cache { var second = Long.MIN_VALUE; var value = ""; var bytes = ByteArray(0) }

    fun now(): String {
        refresh()
        return Cache.value
    }

    /** The current value as bytes (the same array until the second changes; do not modify it). */
    fun nowBytes(): ByteArray {
        refresh()
        return Cache.bytes
    }

    private fun refresh() {
        val sec = neton.io.core.systemTimeMillis() / 1000
        if (sec != Cache.second) { Cache.second = sec; Cache.value = format(sec); Cache.bytes = Cache.value.encodeToByteArray() }
    }

    private val DAYS = arrayOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Formats Unix seconds as `Sun, 06 Nov 1994 08:49:37 GMT`. */
    fun format(epochSeconds: Long): String {
        val days = epochSeconds.floorDiv(86400L)
        val secs = epochSeconds.mod(86400L)
        // Civil date from days since 1970-01-01 (H. Hinnant's algorithm).
        val z = days + 719468
        val era = z.floorDiv(146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1L else 0L)
        fun two(v: Long) = if (v < 10) "0$v" else "$v"
        return "${DAYS[days.mod(7L).toInt()]}, ${two(d)} ${MONTHS[(m - 1).toInt()]} $y ${two(secs / 3600)}:${two(secs / 60 % 60)}:${two(secs % 60)} GMT"
    }
}
