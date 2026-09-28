package neton.http.h2.frame

import neton.http.Method
import neton.http.Request
import neton.http.StatusCode
import neton.http.h2.hpack.Decoder
import neton.http.h2.hpack.Encoder
import neton.http.h2.hpack.Header
import neton.http.h2.hpack.HeaderSink
import neton.http.h2.hpack.K_AUTHORITY
import neton.http.h2.hpack.K_METHOD
import neton.http.h2.hpack.K_PATH
import neton.http.h2.hpack.K_PROTOCOL
import neton.http.h2.hpack.K_SCHEME
import neton.http.h2.hpack.K_STATUS
import neton.http.h2.hpack.utf8Length
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.uri.Scheme
import neton.http.uri.Uri
import neton.io.bytes.Buffer

// HEADERS, PUSH_PROMISE and CONTINUATION (`src/frame/headers.rs`).

/** `MAX_HEADER_LIST_ABUSE_MULTIPLIER`: a decoded header list this many times over the limit ends the connection. */
private const val MAX_HEADER_LIST_ABUSE_MULTIPLIER = 4L

/**
 * The pseudo-header fields of a header block (`h2::frame::Pseudo`). Request fields: [method], [scheme], [authority],
 * [path], [protocol] (extended CONNECT); response field: [status].
 *
 * ⚖️ The reference's `BytesStr` and `Protocol` values are [String]s, as in the HPACK port.
 */
data class Pseudo(
    var method: Method? = null,
    var scheme: String? = null,
    var authority: String? = null,
    var path: String? = null,
    var protocol: String? = null,
    var status: StatusCode? = null,
) {
    /** Sets `:scheme` from a URI scheme (`set_scheme`). */
    fun setScheme(scheme: Scheme) {
        this.scheme = scheme.asStr()
    }

    /** Whether `:status` is 1xx (`is_informational`). */
    val isInformational: Boolean get() = status?.isInformational() ?: false

    companion object {
        /**
         * The pseudo-headers of a request (`Pseudo::request`). A plain CONNECT (no [protocol]) carries neither
         * `:scheme` nor `:path` (RFC 9113 §8.5); otherwise `:path` is the path and query, or `*` for an OPTIONS
         * request without one, or `/`.
         */
        fun request(method: Method, uri: Uri, protocol: String? = null): Pseudo {
            val parts = uri.intoParts()
            var scheme: Scheme? = null
            var path: String? = null
            if (method != Method.CONNECT || protocol != null) {
                val p = parts.pathAndQuery?.asStr() ?: ""
                path = when {
                    p.isNotEmpty() -> p
                    method == Method.OPTIONS -> "*"
                    else -> "/"
                }
                scheme = parts.scheme
            }
            val pseudo = Pseudo(method = method, path = path, protocol = protocol)
            if (scheme != null) pseudo.setScheme(scheme)
            parts.authority?.let { pseudo.authority = it.asStr() }
            return pseudo
        }

        /** The pseudo-headers of a response (`Pseudo::response`). */
        fun response(status: StatusCode): Pseudo = Pseudo(status = status)
    }
}

/**
 * A mutable window `[start, end)` over a byte array: the Kotlin stand-in for the `&mut BytesMut` the reference passes
 * through `load` / `load_hpack` (it advances past what was consumed and is truncated to drop padding).
 */
internal class ByteWindow {
    var array: ByteArray = EMPTY
    var start = 0
    var end = 0

    val length: Int get() = end - start

    fun set(array: ByteArray, start: Int, end: Int) {
        this.array = array
        this.start = start
        this.end = end
    }

    fun advance(n: Int) {
        start += n
    }

    fun truncate(len: Int) {
        end = start + len
    }

    operator fun get(i: Int): Byte = array[start + i]

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * A HEADERS frame (`h2::frame::Headers`): a request or response head, 1xx head or trailers.
 *
 * Received frames are built in two steps like the reference: [load] parses padding and priority, then [loadHpack]
 * decodes the header block fragment(s) into [fields] and [pseudo], validating them (SPEC §4.1).
 */
class Headers internal constructor(
    val streamId: StreamId,
    /** The stream dependency of a received frame with the PRIORITY flag (parsed and then ignored). */
    val streamDep: StreamDependency?,
    internal val block: HeaderBlock,
    private var flags: Int,
) : Frame() {
    /** A HEADERS frame with END_HEADERS set (`Headers::new`). */
    constructor(streamId: StreamId, pseudo: Pseudo, fields: HeaderMap<HeaderValue>) :
        this(streamId, null, HeaderBlock(fields, calculateHeaderMapSize(fields), pseudo), END_HEADERS)

    /** Decodes a header block fragment (`load_hpack`); see [HeaderBlock.load]. */
    internal fun loadHpack(src: ByteWindow, maxHeaderListSize: Int, decoder: Decoder) =
        block.load(src, maxHeaderListSize, decoder)

    val isEndHeaders: Boolean get() = flags and END_HEADERS != 0

    fun setEndHeaders() {
        flags = flags or END_HEADERS
    }

    val isEndStream: Boolean get() = flags and END_STREAM != 0

    fun setEndStream() {
        flags = flags or END_STREAM
    }

    /**
     * Whether the decoded header list reached the local SETTINGS_MAX_HEADER_LIST_SIZE (or the header map's
     * capacity): the fields past that point were dropped, and the stream is to be refused (server: 431, then
     * REFUSED_STREAM).
     */
    val isOverSize: Boolean get() = block.isOverSize

    val pseudo: Pseudo get() = block.pseudo

    val fields: HeaderMap<HeaderValue> get() = block.fields

    /** Whether `:status` is 1xx (`is_informational`). */
    val isInformational: Boolean get() = block.pseudo.isInformational

    /** The pseudo-headers and the fields (`into_parts`). */
    fun intoParts(): Pair<Pseudo, HeaderMap<HeaderValue>> = block.pseudo to block.fields

    /** The frame header (without length). */
    fun head(): Head = Head(Kind.Headers, flags, streamId)

    /**
     * HPACK-encodes the block and appends the frame to [dst], writing at most [limit] bytes (frame header included,
     * the reference's `Limit<&mut BytesMut>`): the codec passes max frame size + 9. When the block does not fit, the
     * frame is written without END_HEADERS and the rest is returned as a [Continuation] (`Headers::encode`).
     *
     * ⚖️ The reference consumes the frame (its header map is moved into the encoder); here the frame is left as is.
     */
    fun encode(encoder: Encoder, dst: Buffer, limit: Int): Continuation? {
        check(isEndHeaders) { "END_HEADERS must be set when encoding" }
        return encodeBlock(head(), block.intoEncoding(encoder), dst, limit, encoder, -1)
    }

    override fun equals(other: Any?): Boolean =
        other is Headers && other.streamId == streamId && other.streamDep == streamDep && other.flags == flags &&
            other.block == block

    override fun hashCode(): Int = streamId.value * 31 + flags

    override fun toString(): String = buildString {
        append("Headers { stream_id: ").append(streamId.value).append(", flags: ")
        append(debugFlags(flags, END_HEADERS to "END_HEADERS", END_STREAM to "END_STREAM", PADDED to "PADDED", PRIORITY to "PRIORITY"))
        block.pseudo.protocol?.let { append(", protocol: ").append(it) }
        streamDep?.let { append(", stream_dep: ").append(it) }
        append(" }") // fields and pseudo purposefully not included, as in the reference
    }

    companion object {
        /** A trailers frame: END_STREAM and END_HEADERS, no pseudo-headers (`Headers::trailers`). */
        fun trailers(streamId: StreamId, fields: HeaderMap<HeaderValue>): Headers =
            Headers(streamId, null, HeaderBlock(fields, calculateHeaderMapSize(fields), Pseudo()), END_HEADERS or END_STREAM)

        /**
         * Parses the frame without decoding the header block (`Headers::load`): [src] is the payload; on return it is
         * the header block fragment (padding length, priority fields and padding removed).
         * @throws FrameException [FrameError.InvalidStreamId] on stream 0, [FrameError.MalformedMessage] when the
         * padding length or priority fields are missing, [FrameError.InvalidDependencyId] when the stream depends on
         * itself, [FrameError.TooMuchPadding].
         */
        internal fun load(head: Head, src: ByteWindow): Headers {
            val flags = head.flag
            var pad = 0
            if (head.streamId.isZero) throw FrameException(FrameError.InvalidStreamId)
            if (flags and PADDED != 0) {
                if (src.length == 0) throw FrameException(FrameError.MalformedMessage)
                pad = src[0].toInt() and 0xff
                src.advance(1)
            }
            var streamDep: StreamDependency? = null
            if (flags and PRIORITY != 0) {
                if (src.length < 5) throw FrameException(FrameError.MalformedMessage)
                val dep = StreamDependency.load(src.array, src.start, 5)
                if (dep.dependencyId == head.streamId) throw FrameException(FrameError.InvalidDependencyId)
                src.advance(5)
                streamDep = dep
            }
            if (pad > 0) {
                if (pad > src.length) throw FrameException(FrameError.TooMuchPadding)
                src.truncate(src.length - pad)
            }
            return Headers(head.streamId, streamDep, HeaderBlock(HeaderMap(), 0, Pseudo()), flags)
        }
    }
}

/** Why a promised request is not acceptable (`PushPromiseHeaderError`). */
sealed class PushPromiseHeaderError {
    /** A non-zero content-length; [parsed] is the value, or null when it is not a number (`InvalidContentLength`). */
    data class InvalidContentLength(val parsed: ULong?) : PushPromiseHeaderError()

    /** The method is not safe and cacheable (only GET and HEAD are). */
    data object NotSafeAndCacheable : PushPromiseHeaderError()
}

/** A PUSH_PROMISE frame (`h2::frame::PushPromise`). */
class PushPromise internal constructor(
    val streamId: StreamId,
    /** The stream reserved by this promise. */
    val promisedId: StreamId,
    internal val block: HeaderBlock,
    private var flags: Int,
) : Frame() {
    /** A PUSH_PROMISE with END_HEADERS set (`PushPromise::new`). */
    constructor(streamId: StreamId, promisedId: StreamId, pseudo: Pseudo, fields: HeaderMap<HeaderValue>) :
        this(streamId, promisedId, HeaderBlock(fields, calculateHeaderMapSize(fields), pseudo), END_HEADERS)

    /** Decodes a header block fragment (`load_hpack`); see [HeaderBlock.load]. */
    internal fun loadHpack(src: ByteWindow, maxHeaderListSize: Int, decoder: Decoder) =
        block.load(src, maxHeaderListSize, decoder)

    val fields: HeaderMap<HeaderValue> get() = block.fields

    val pseudo: Pseudo get() = block.pseudo

    val isEndHeaders: Boolean get() = flags and END_HEADERS != 0

    fun setEndHeaders() {
        flags = flags or END_HEADERS
    }

    /** As [Headers.isOverSize]. */
    val isOverSize: Boolean get() = block.isOverSize

    /** The pseudo-headers and the fields (`into_parts`). */
    fun intoParts(): Pair<Pseudo, HeaderMap<HeaderValue>> = block.pseudo to block.fields

    /** The frame header (without length). */
    fun head(): Head = Head(Kind.PushPromise, flags, streamId)

    /** As [Headers.encode]; the promised stream ID precedes the block fragment (`PushPromise::encode`). */
    fun encode(encoder: Encoder, dst: Buffer, limit: Int): Continuation? {
        check(isEndHeaders) { "END_HEADERS must be set when encoding" }
        return encodeBlock(head(), block.intoEncoding(encoder), dst, limit, encoder, promisedId.value)
    }

    override fun equals(other: Any?): Boolean =
        other is PushPromise && other.streamId == streamId && other.promisedId == promisedId && other.flags == flags &&
            other.block == block

    override fun hashCode(): Int = (streamId.value * 31 + promisedId.value) * 31 + flags

    override fun toString(): String =
        "PushPromise { stream_id: ${streamId.value}, promised_id: ${promisedId.value}, flags: " +
            debugFlags(flags, END_HEADERS to "END_HEADERS", PADDED to "PADDED") + " }"

    companion object {
        /**
         * Checks the requirements for a promised request (`validate_request`, RFC 9113 §8.4): no request body
         * (content-length absent or 0) and a safe, cacheable method (GET or HEAD).
         *
         * ⚖️ Returns null when acceptable, the error otherwise, instead of a `Result`.
         */
        fun validateRequest(req: Request<*>): PushPromiseHeaderError? {
            val contentLength = req.headers[HeaderName.CONTENT_LENGTH]
            if (contentLength != null) {
                val parsed = parseU64(contentLength.array, contentLength.offset, contentLength.length)
                if (parsed != 0UL) return PushPromiseHeaderError.InvalidContentLength(parsed)
            }
            if (req.method != Method.GET && req.method != Method.HEAD) return PushPromiseHeaderError.NotSafeAndCacheable
            return null
        }

        /**
         * Parses the frame without decoding the header block (`PushPromise::load`); see [Headers.load].
         * @throws FrameException [FrameError.InvalidStreamId] on stream 0, [FrameError.MalformedMessage] when the
         * padding length is missing or fewer than 5 bytes remain, [FrameError.TooMuchPadding].
         */
        internal fun load(head: Head, src: ByteWindow): PushPromise {
            val flags = head.flag
            var pad = 0
            if (head.streamId.isZero) throw FrameException(FrameError.InvalidStreamId)
            if (flags and PADDED != 0) {
                if (src.length == 0) throw FrameException(FrameError.MalformedMessage)
                pad = src[0].toInt() and 0xff
                src.advance(1)
            }
            // The reference requires 5 bytes here, although the promised stream ID takes 4.
            if (src.length < 5) throw FrameException(FrameError.MalformedMessage)
            val promisedId = StreamId.parse(src.array, src.start)
            src.advance(4)
            if (pad > 0) {
                if (pad > src.length) throw FrameException(FrameError.TooMuchPadding)
                src.truncate(src.length - pad)
            }
            return PushPromise(head.streamId, promisedId, HeaderBlock(HeaderMap(), 0, Pseudo()), flags)
        }
    }
}

/**
 * The rest of a header block that did not fit in its HEADERS or PUSH_PROMISE frame (`h2::frame::Continuation`),
 * written as CONTINUATION frames by the codec.
 */
class Continuation internal constructor(val streamId: StreamId, private val hpack: Buffer) {
    /** Appends one CONTINUATION frame of at most [limit] bytes; returns what still does not fit (`encode`). */
    fun encode(dst: Buffer, limit: Int): Continuation? =
        encodeBlock(Head(Kind.Continuation, END_HEADERS, streamId), hpack, dst, limit, null, -1)

    override fun toString(): String = "Continuation { stream_id: ${streamId.value}, remaining: ${hpack.readableBytes} }"
}

/**
 * `EncodingHeaderBlock::encode`: writes one frame of the encoded block [hpack] (preceded by the promised stream ID
 * when [promisedId] >= 0) into at most [limit] bytes of [dst]. When the block does not fit, END_HEADERS is cleared
 * and the rest is returned; otherwise the block buffer goes back to [encoder] for reuse.
 *
 * The reference writes the header with length 0 and patches it afterwards; here the length is computed first.
 */
private fun encodeBlock(head: Head, hpack: Buffer, dst: Buffer, limit: Int, encoder: Encoder?, promisedId: Int): Continuation? {
    val extra = if (promisedId >= 0) 4 else 0
    val room = limit - HEADER_LEN - extra
    require(room >= 0) { "limit $limit too small for a frame header" }
    val total = hpack.readableBytes
    val more = total > room
    val n = if (more) room else total
    val flag = if (more) head.flag and END_HEADERS.inv() else head.flag
    Head(head.kind, flag, head.streamId).encode(n + extra, dst)
    if (promisedId >= 0) dst.writeInt(promisedId)
    dst.writeBytes(hpack.backingArray(), hpack.readerIndex(), n)
    hpack.skip(n)
    if (more) return Continuation(head.streamId, hpack)
    encoder?.returnScratch(hpack)
    return null
}

/**
 * A header block: decoded fields and pseudo-headers (`HeaderBlock`). While decoding, it is the HPACK decoder's
 * [HeaderSink], so decoding a block allocates nothing beyond the headers themselves.
 */
internal class HeaderBlock(
    val fields: HeaderMap<HeaderValue>,
    /** Precomputed size of the regular fields (32 + name + value each). */
    var fieldSize: Int,
    val pseudo: Pseudo,
) : HeaderSink {
    /** Set when decoding reached the max header list size (or the header map's capacity). */
    var isOverSize = false

    // Decoding state of the fragment being loaded.
    private var reg = false
    private var headersSize = 0L
    private var maxHeaderListSize = 0L
    private var maxAbuseSize = 0L
    private var wayTooLarge = false

    /**
     * ⚖️ Kept across the fragments of one block and reported once the whole block is decoded. In the reference it
     * is a local of each `load` call: a violation in a fragment that ends inside a representation (HPACK `NeedMore`)
     * was forgotten, so the header was dropped silently instead of making the message malformed.
     */
    private var malformed = false

    /**
     * Decodes the fragment in [src] (`HeaderBlock::load`), advancing [src] past the complete representations
     * decoded; what is left is an incomplete representation to be continued by the next CONTINUATION.
     *
     * HPACK decoding always runs through the whole fragment, even for a malformed message (the dynamic table is
     * connection state and must stay in sync); the violations are reported at the end:
     * - a pseudo-header after a regular one, a repeated pseudo-header, a connection-specific header (connection,
     *   transfer-encoding, upgrade, keep-alive, proxy-connection) or `te` other than `trailers`:
     *   [FrameError.MalformedMessage];
     * - a decoded header list (32 + name + value per field) reaching [maxHeaderListSize]: [isOverSize] is set and
     *   later fields are dropped; exceeding 4 times the limit stops decoding with
     *   [FrameError.HeaderListWayTooLarge].
     *
     * @throws FrameException [FrameError.Hpack] for a decoder error (including `NeedMore*` for an incomplete
     * fragment), or as described above.
     */
    fun load(src: ByteWindow, maxHeaderListSize: Int, decoder: Decoder) {
        reg = !fields.isEmpty()
        wayTooLarge = false
        headersSize = calculateHeaderListSize()
        this.maxHeaderListSize = maxHeaderListSize.toLong()
        maxAbuseSize = maxHeaderListSize.toLong() * MAX_HEADER_LIST_ABUSE_MULTIPLIER

        val err = decoder.decode(src.array, src.start, src.length, this)
        src.advance(decoder.consumed)
        if (err != null) throw FrameException(FrameError.Hpack, err)
        if (wayTooLarge) throw FrameException(FrameError.HeaderListWayTooLarge)
        if (malformed) throw FrameException(FrameError.MalformedMessage)
    }

    override fun onHeader(header: Header): Boolean {
        when (header) {
            is Header.Field -> {
                val name = header.name
                val value = header.value
                // Connection-level header fields are not supported and make the message malformed.
                if (name == HeaderName.CONNECTION || name == HeaderName.TRANSFER_ENCODING || name == HeaderName.UPGRADE ||
                    name.equalsIgnoreCase("keep-alive") || name.equalsIgnoreCase("proxy-connection")
                ) {
                    malformed = true
                } else if (name == HeaderName.TE && !value.contentEquals("trailers")) {
                    malformed = true
                } else {
                    reg = true
                    val size = decodedHeaderSize(name.length, value.length)
                    headersSize += size
                    if (checkSizeBreaks()) return false
                    if (!isOverSize) {
                        fieldSize += size
                        // Past the header map's capacity: treated as over size (the stream is refused), never a crash.
                        if (fields.tryAppend(name, value).isFailure) isOverSize = true
                    }
                }
            }
            is Header.Authority -> when (admitPseudo(pseudo.authority != null, 10, utf8Length(header.value))) {
                STORE -> pseudo.authority = header.value
                BREAK -> return false
            }
            is Header.Method -> when (admitPseudo(pseudo.method != null, 7, header.value.asStr().length)) {
                STORE -> pseudo.method = header.value
                BREAK -> return false
            }
            is Header.Scheme -> when (admitPseudo(pseudo.scheme != null, 7, utf8Length(header.value))) {
                STORE -> pseudo.scheme = header.value
                BREAK -> return false
            }
            is Header.Path -> when (admitPseudo(pseudo.path != null, 5, utf8Length(header.value))) {
                STORE -> pseudo.path = header.value
                BREAK -> return false
            }
            is Header.Protocol -> when (admitPseudo(pseudo.protocol != null, 9, utf8Length(header.value))) {
                STORE -> pseudo.protocol = header.value
                BREAK -> return false
            }
            is Header.Status -> when (admitPseudo(pseudo.status != null, 7, 3)) {
                STORE -> pseudo.status = header.value
                BREAK -> return false
            }
            is Header.Value -> throw IllegalStateException("the decoder never yields a nameless header")
        }
        return true
    }

    /** The reference's `set_pseudo!`: whether to store a pseudo-header, ignore it, or stop decoding. */
    private fun admitPseudo(alreadySet: Boolean, nameLen: Int, valueLen: Int): Int {
        if (reg || alreadySet) {
            // Pseudo-header not at the head of the block, or repeated.
            malformed = true
            return IGNORE
        }
        headersSize += decodedHeaderSize(nameLen, valueLen)
        if (checkSizeBreaks()) return BREAK
        return if (isOverSize) IGNORE else STORE
    }

    /** The reference's `check_size!`: true (break) when over the abuse limit; marks [isOverSize] at the limit. */
    private fun checkSizeBreaks(): Boolean {
        if (headersSize > maxAbuseSize) {
            wayTooLarge = true
            return true
        }
        if (headersSize >= maxHeaderListSize && !isOverSize) isOverSize = true
        return false
    }

    /**
     * The size of the header list decoded so far (`calculate_header_list_size`). As in the reference, `:protocol`
     * is not counted here (it is counted while decoding).
     */
    private fun calculateHeaderListSize(): Long {
        var size = fieldSize.toLong()
        pseudo.method?.let { size += decodedHeaderSize(7, it.asStr().length) }
        pseudo.scheme?.let { size += decodedHeaderSize(7, utf8Length(it)) }
        pseudo.status?.let { size += decodedHeaderSize(7, 3) }
        pseudo.authority?.let { size += decodedHeaderSize(10, utf8Length(it)) }
        pseudo.path?.let { size += decodedHeaderSize(5, utf8Length(it)) }
        return size
    }

    /**
     * HPACK-encodes the pseudo-headers (method, scheme, authority, path, protocol, status) and then the fields into
     * the encoder's reusable block buffer (`into_encoding`). A repeated name is encoded as a value for the previous
     * name, like the reference's header map iterator yielding `None` names.
     */
    fun intoEncoding(encoder: Encoder): Buffer {
        val hpack = encoder.takeScratch()
        hpack.clear()
        encoder.beginBlock(hpack)
        pseudo.method?.let { encoder.encodePseudo(K_METHOD, it, hpack) }
        pseudo.scheme?.let { encoder.encodePseudo(K_SCHEME, it, hpack) }
        pseudo.authority?.let { encoder.encodePseudo(K_AUTHORITY, it, hpack) }
        pseudo.path?.let { encoder.encodePseudo(K_PATH, it, hpack) }
        pseudo.protocol?.let { encoder.encodePseudo(K_PROTOCOL, it, hpack) }
        pseudo.status?.let { encoder.encodePseudo(K_STATUS, it, hpack) }
        var last: HeaderName? = null
        fields.forEach { name, value ->
            if (name === last) {
                encoder.encodeSameName(value, hpack)
            } else {
                encoder.encodeField(name, value, hpack)
                last = name
            }
        }
        return hpack
    }

    override fun equals(other: Any?): Boolean =
        other is HeaderBlock && other.fieldSize == fieldSize && other.isOverSize == isOverSize &&
            other.pseudo == pseudo && other.fields == fields

    override fun hashCode(): Int = fieldSize * 31 + pseudo.hashCode()

    private companion object {
        const val STORE = 0
        const val IGNORE = 1
        const val BREAK = 2
    }
}

/** The header list size of [map] (`calculate_headermap_size`). */
private fun calculateHeaderMapSize(map: HeaderMap<HeaderValue>): Int {
    var size = 0
    map.forEach { name, value -> size += decodedHeaderSize(name.length, value.length) }
    return size
}

/** RFC 9113 §6.5.2: a field counts its name and value lengths plus 32 (`decoded_header_size`). */
private fun decodedHeaderSize(name: Int, value: Int): Int = name + value + 32

/**
 * Parses a decimal content-length (`parse_u64`): digits only, at most 19 of them.
 *
 * ⚖️ Returns null when not a number instead of `Err(ParseU64Error)`.
 */
fun parseU64(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): ULong? {
    if (length > 19) return null // At danger of overflow.
    var ret = 0UL
    for (i in offset until offset + length) {
        val d = src[i].toInt()
        if (d < '0'.code || d > '9'.code) return null
        ret = ret * 10UL + (d - '0'.code).toULong()
    }
    return ret
}
