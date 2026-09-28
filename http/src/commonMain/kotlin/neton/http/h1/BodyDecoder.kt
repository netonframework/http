package neton.http.h1

import neton.http.HttpException
import neton.http.h1.parse.HeaderSlots
import neton.http.h1.parse.ParseStatus
import neton.http.h1.parse.parseHeaders
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes

/**
 * The class of a body codec error, mirroring the `std::io::ErrorKind` hyper attaches to each decoder error
 * (`proto/h1/decode.rs`).
 */
enum class BodyErrorKind {
    /** `io::ErrorKind::UnexpectedEof`: the transport ended before the body did. */
    UNEXPECTED_EOF,

    /** `io::ErrorKind::InvalidInput`: malformed chunked framing. */
    INVALID_INPUT,

    /** `io::ErrorKind::InvalidData`: a limit was exceeded or a value overflowed. */
    INVALID_DATA,
}

/**
 * An error found while decoding a message body. Each entry carries the reference's error kind and message
 * (`decode.rs`); the entries marked "safety baseline" are this library's additions (SPEC §3.4, §3.9).
 */
enum class BodyDecodeError(
    /** The `io::ErrorKind` hyper reports for this error. */
    val kind: BodyErrorKind,
    /** The reference's error text. */
    val message: String,
) {
    /** The transport ended before the declared length or the current chunk was read (`decode.rs:161`, `:491`). */
    INCOMPLETE_BODY(BodyErrorKind.UNEXPECTED_EOF, "end of file before message length reached"),

    /** The transport ended inside chunk framing (`decode.rs:252` `byte!`). */
    UNEXPECTED_EOF_IN_CHUNK(BodyErrorKind.UNEXPECTED_EOF, "unexpected EOF during chunk size line"),

    /** The chunk size does not fit in 64 bits (`decode.rs:264` `or_overflow!`). */
    CHUNK_SIZE_OVERFLOW(BodyErrorKind.INVALID_DATA, "invalid chunk size: overflow"),

    /** The chunk size line does not start with a hex digit (`decode.rs:364`). */
    MISSING_SIZE_DIGIT(BodyErrorKind.INVALID_INPUT, "Invalid chunk size line: missing size digit"),

    /** An unexpected byte inside the chunk size (`decode.rs:399`). */
    INVALID_SIZE(BodyErrorKind.INVALID_INPUT, "Invalid chunk size line: Invalid Size"),

    /** An unexpected byte in the whitespace after the chunk size (`decode.rs:417`). */
    INVALID_SIZE_LWS(BodyErrorKind.INVALID_INPUT, "Invalid chunk size linear white space"),

    /** A bare `LF` inside a chunk extension (`decode.rs:437`). */
    EXTENSION_NEWLINE(BodyErrorKind.INVALID_DATA, "invalid chunk extension contains newline"),

    /** The chunk extensions of the whole body exceed 16 KiB (`decode.rs:443`). */
    EXTENSIONS_OVER_LIMIT(BodyErrorKind.INVALID_DATA, "chunk extensions over limit"),

    /** The chunk size line is not ended by `CR LF` (`decode.rs:469`). */
    INVALID_SIZE_LF(BodyErrorKind.INVALID_INPUT, "Invalid chunk size LF"),

    /** The chunk data is not followed by `CR` (`decode.rs:511`). */
    INVALID_BODY_CR(BodyErrorKind.INVALID_INPUT, "Invalid chunk body CR"),

    /** The chunk data is not followed by `CR LF` (`decode.rs:523`). */
    INVALID_BODY_LF(BodyErrorKind.INVALID_INPUT, "Invalid chunk body LF"),

    /** The trailer section reaches the byte limit (`decode.rs:276` `put_u8!`). */
    TRAILERS_OVER_LIMIT(BodyErrorKind.INVALID_DATA, "chunk trailers bytes over limit"),

    /** More trailer fields than the header count limit (`decode.rs:562`). */
    TRAILERS_COUNT_OVERFLOW(BodyErrorKind.INVALID_DATA, "chunk trailers count overflow"),

    /** A trailer line's `CR` is not followed by `LF` (`decode.rs:578`). */
    INVALID_TRAILER_LF(BodyErrorKind.INVALID_INPUT, "Invalid trailer end LF"),

    /** The last-chunk / trailer section is not ended by `CR LF` (`decode.rs:630`). */
    INVALID_END_LF(BodyErrorKind.INVALID_INPUT, "Invalid chunk end LF"),

    /** A trailer field name is not a valid header name (`decode.rs:650`). */
    INVALID_TRAILER_NAME(BodyErrorKind.INVALID_INPUT, "Invalid header name"),

    /** A trailer field value is not a valid header value (`decode.rs:660`). */
    INVALID_TRAILER_VALUE(BodyErrorKind.INVALID_INPUT, "Invalid header value"),

    /** The trailer section is incomplete (`decode.rs:672`). */
    PARTIAL_TRAILERS(BodyErrorKind.INVALID_INPUT, "Partial header"),

    /** The trailer section is not a valid field section, e.g. a bare `LF` line ending (`decode.rs:676`). */
    INVALID_TRAILERS(BodyErrorKind.INVALID_INPUT, "invalid trailer section"),

    /** Safety baseline: a chunk size line (size, extensions and `CR LF`) longer than the per-line limit (1 KiB). */
    SIZE_LINE_TOO_LONG(BodyErrorKind.INVALID_DATA, "chunk size line over limit"),

    /** Safety baseline: more than 16 hex digits in a chunk size. */
    TOO_MANY_SIZE_DIGITS(BodyErrorKind.INVALID_DATA, "invalid chunk size: too many digits");

    override fun toString(): String = message
}

/** Thrown by the convenience APIs ([BodyDecoder.decodeOrThrow], [decodeTrailers]); the decoder itself never throws. */
class BodyDecodeException(
    /** The error. */
    val error: BodyDecodeError,
) : HttpException(error.message)

/** What [BodyDecoder.decode] produced. */
enum class DecodeResult {
    /** A data frame: [BodyDecoder.data] is a non-empty zero-copy slice of the read buffer. */
    DATA,

    /** The chunked trailer section: [BodyDecoder.trailers]. The body is complete ([BodyDecoder.isEof]). */
    TRAILERS,

    /** The body is complete. Every later call returns [END] again. */
    END,

    /** More input is needed: read into the buffer (see [BodyDecoder.wanted]) and call again. */
    NEED_MORE,

    /** The body is invalid or truncated: [BodyDecoder.error]. Every later call returns [ERROR] again. */
    ERROR,
}

/**
 * The HTTP/1 message body decoder (hyper `proto/h1/decode.rs` `Decoder`), sans-I/O.
 *
 * Three kinds, chosen by the head (SPEC §3.3): [length] (`Content-Length`), [chunked]
 * (`Transfer-Encoding: chunked`) and [eof] (a response delimited by closing the connection).
 *
 * The decoder consumes bytes from the connection's read buffer and never reads the transport itself. The caller's
 * loop is:
 * ```
 * while (true) when (decoder.decode(readBuf, eof)) {
 *     DecodeResult.DATA -> deliver(decoder.data)
 *     DecodeResult.TRAILERS -> deliverTrailers(decoder.trailers!!)
 *     DecodeResult.END -> break
 *     DecodeResult.NEED_MORE -> eof = readMore(readBuf) < 0     // at least 1 byte; decoder.wanted is a hint
 *     DecodeResult.ERROR -> fail(decoder.error!!)
 * }
 * ```
 * `eof = true` tells the decoder the transport has ended: bytes still in the buffer are decoded first, and needing a
 * byte afterwards is an error (or, for [eof] bodies, the end). Bytes after the end of the body (a pipelined next
 * message) are left in the buffer.
 *
 * Allocation: none per call, except the returned [data] slice, and the trailer section buffer and objects.
 *
 * Deviations from hyper (SPEC §3.4, §3.9, safety baseline): a chunk size line is limited to 1 KiB
 * ([BodyDecodeError.SIZE_LINE_TOO_LONG]) in addition to the 16 KiB whole-body extension limit; a chunk size has at
 * most 16 hex digits; the trailer section is limited to 8 KiB by default and is parsed with bare `LF` line endings
 * rejected. An error is sticky.
 */
class BodyDecoder private constructor(
    private var kind: Int,
    /** Length: bytes left. */
    private var remaining: Long,
    private var maxHeaders: Int,
    private var maxTrailerSize: Int,
    private var maxSizeLine: Int,
) {
    /**
     * Makes this decoder a fresh one of the given kind (what [length], [chunked] and [eof] create), so a connection
     * keeps one decoder for all its messages. [length]: [BodyLength] of the message (> 0, chunked or close-delimited).
     */
    internal fun reset(length: Long, maxHeaders: Int) {
        when (length) {
            BodyLength.CHUNKED -> { kind = KIND_CHUNKED; remaining = 0; this.maxHeaders = maxHeaders; maxTrailerSize = DEFAULT_MAX_TRAILER_SIZE; maxSizeLine = DEFAULT_MAX_CHUNK_SIZE_LINE }
            BodyLength.CLOSE_DELIMITED -> { kind = KIND_EOF; remaining = 0; this.maxHeaders = 0; maxTrailerSize = 0; maxSizeLine = 0 }
            else -> { require(length >= 0); kind = KIND_LENGTH; remaining = length; this.maxHeaders = 0; maxTrailerSize = 0; maxSizeLine = 0 }
        }
        state = ChunkedState.START
        chunkLen = 0uL
        extensionsCnt = 0
        trailersBuf = null
        trailersLen = 0
        trailersCnt = 0
        sizeLineLen = 0
        sizeDigits = 0
        sawEof = false
        data = Bytes.EMPTY
        trailers = null
        error = null
        wanted = 0
    }

    // ---- chunked state (`Kind::Chunked`) ----
    internal var state: ChunkedState = ChunkedState.START
        private set

    /** `chunk_len`: the size being parsed, then the data bytes left in the current chunk (unsigned 64-bit). */
    internal var chunkLen: ULong = 0uL
        private set
    private var extensionsCnt: Long = 0
    private var trailersBuf: ByteArray? = null
    private var trailersLen: Int = 0
    private var trailersCnt: Int = 0

    /** Safety baseline: bytes of the current chunk size line and its hex digit count. */
    private var sizeLineLen: Int = 0
    private var sizeDigits: Int = 0

    // ---- Eof: `Eof(bool)` ----
    private var sawEof: Boolean = false

    /** The data frame of the last [DecodeResult.DATA]. */
    var data: Bytes = Bytes.EMPTY
        private set

    /** The trailers of the last [DecodeResult.TRAILERS]. */
    var trailers: HeaderMap<HeaderValue>? = null
        private set

    /** The error of [DecodeResult.ERROR]. */
    var error: BodyDecodeError? = null
        private set

    /**
     * After [DecodeResult.NEED_MORE]: how many more bytes this body can take right now (the remaining length, the rest
     * of the current chunk, 1 inside chunk framing, 8192 for an EOF-delimited body). A hint for sizing the read; the
     * decoder makes progress with any positive amount.
     */
    var wanted: Long = 0
        private set

    /** Whether the body is complete (`is_eof`): length 0 reached, the chunked terminator read, or EOF seen. */
    val isEof: Boolean
        get() = when (kind) {
            KIND_LENGTH -> remaining == 0L
            KIND_CHUNKED -> state == ChunkedState.END
            else -> sawEof
        }

    /** Whether this is a chunked decoder. */
    val isChunked: Boolean get() = kind == KIND_CHUNKED

    /**
     * Decodes the next frame from [buf] (hyper `Decoder::decode`, `decode.rs:144`). [eof] is true once the transport
     * has ended. See the class documentation for the loop.
     */
    fun decode(buf: Buffer, eof: Boolean): DecodeResult {
        if (error != null) return DecodeResult.ERROR
        return when (kind) {
            KIND_LENGTH -> decodeLength(buf, eof)
            KIND_CHUNKED -> decodeChunked(buf, eof)
            else -> decodeEof(buf, eof)
        }
    }

    /**
     * Convenience for tests and cold paths: [decode], throwing [BodyDecodeException] on [DecodeResult.ERROR].
     */
    fun decodeOrThrow(buf: Buffer, eof: Boolean): DecodeResult {
        val r = decode(buf, eof)
        if (r == DecodeResult.ERROR) throw BodyDecodeException(error!!)
        return r
    }

    private fun decodeLength(buf: Buffer, eof: Boolean): DecodeResult {
        val rem = remaining
        if (rem == 0L) return DecodeResult.END
        val avail = buf.readableBytes
        if (avail == 0) {
            if (eof) return fail(BodyDecodeError.INCOMPLETE_BODY)
            wanted = rem
            return DecodeResult.NEED_MORE
        }
        val n = if (rem < avail) rem.toInt() else avail
        data = buf.readSlice(n)
        remaining = rem - n
        return DecodeResult.DATA
    }

    private fun decodeEof(buf: Buffer, eof: Boolean): DecodeResult {
        if (sawEof) return DecodeResult.END
        val avail = buf.readableBytes
        if (avail == 0) {
            if (eof) {
                sawEof = true
                return DecodeResult.END
            }
            wanted = EOF_READ_SIZE.toLong()
            return DecodeResult.NEED_MORE
        }
        // 8192: about two packets (`decode.rs:228`).
        data = buf.readSlice(if (avail < EOF_READ_SIZE) avail else EOF_READ_SIZE)
        return DecodeResult.DATA
    }

    private fun fail(e: BodyDecodeError): DecodeResult {
        error = e
        return DecodeResult.ERROR
    }

    /**
     * The chunked loop (`decode.rs:171-222`): steps [ChunkedState] one byte at a time over the buffer's backing
     * array (consumed in one go), returns at the first data slice, at the end, or when input runs out.
     */
    private fun decodeChunked(buf: Buffer, eof: Boolean): DecodeResult {
        val arr = buf.backingArray()
        val start = buf.readerIndex()
        val end = start + buf.readableBytes
        var p = start
        while (true) {
            val st = state
            if (st == ChunkedState.END) {
                buf.consume(p - start)
                val tb = trailersBuf ?: return DecodeResult.END
                trailersBuf = null
                return try {
                    trailers = decodeTrailers(tb, 0, trailersLen, trailersCnt)
                    DecodeResult.TRAILERS
                } catch (e: BodyDecodeException) {
                    fail(e.error)
                }
            }
            if (st == ChunkedState.BODY) {
                // `read_body` (`decode.rs:476`).
                buf.consume(p - start)
                val avail = buf.readableBytes
                val rem = chunkLen
                if (avail == 0) {
                    if (eof) {
                        chunkLen = 0uL
                        return fail(BodyDecodeError.INCOMPLETE_BODY)
                    }
                    wanted = if (rem > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else rem.toLong()
                    return DecodeResult.NEED_MORE
                }
                val n = if (rem < avail.toULong()) rem.toInt() else avail
                data = buf.readSlice(n)
                chunkLen = rem - n.toULong()
                if (chunkLen == 0uL) state = ChunkedState.BODY_CR
                return DecodeResult.DATA
            }
            // Every other state reads one byte (`byte!`, `decode.rs:252`).
            if (p >= end) {
                buf.consume(p - start)
                if (eof) return fail(BodyDecodeError.UNEXPECTED_EOF_IN_CHUNK)
                wanted = 1
                return DecodeResult.NEED_MORE
            }
            val b = arr[p++].toInt() and 0xFF
            val e = step(st, b)
            if (e != null) {
                buf.consume(p - start)
                return fail(e)
            }
        }
    }

    /** One transition of `ChunkedState::step` (`decode.rs:303`) on byte [b]; returns an error or null. */
    private fun step(st: ChunkedState, b: Int): BodyDecodeError? {
        when (st) {
            ChunkedState.START -> {
                // `read_start` (`decode.rs:342`).
                sizeLineLen = 0
                sizeDigits = 0
                countSizeLine()?.let { return it }
                val d = hexValue(b)
                if (d < 0) return BodyDecodeError.MISSING_SIZE_DIGIT
                addDigit(d)?.let { return it }
                state = ChunkedState.SIZE
            }
            ChunkedState.SIZE -> {
                // `read_size` (`decode.rs:374`).
                countSizeLine()?.let { return it }
                val d = hexValue(b)
                if (d >= 0) return addDigit(d)
                state = when (b) {
                    TAB, SP -> ChunkedState.SIZE_LWS
                    SEMI -> ChunkedState.EXTENSION
                    CR -> ChunkedState.SIZE_LF
                    else -> return BodyDecodeError.INVALID_SIZE
                }
            }
            ChunkedState.SIZE_LWS -> {
                // `read_size_lws` (`decode.rs:407`): LWS can follow the size, but no more digits can come.
                countSizeLine()?.let { return it }
                state = when (b) {
                    TAB, SP -> ChunkedState.SIZE_LWS
                    SEMI -> ChunkedState.EXTENSION
                    CR -> ChunkedState.SIZE_LF
                    else -> return BodyDecodeError.INVALID_SIZE_LWS
                }
            }
            ChunkedState.EXTENSION -> {
                // `read_extension` (`decode.rs:423`): extensions are ignored; they end at the next CRLF, and a plain
                // LF is rejected to save implementations that do not check for the CR.
                countSizeLine()?.let { return it }
                when (b) {
                    CR -> state = ChunkedState.SIZE_LF
                    LF -> return BodyDecodeError.EXTENSION_NEWLINE
                    else -> {
                        extensionsCnt++
                        if (extensionsCnt >= CHUNKED_EXTENSIONS_LIMIT) return BodyDecodeError.EXTENSIONS_OVER_LIMIT
                    }
                }
            }
            ChunkedState.SIZE_LF -> {
                // `read_size_lf` (`decode.rs:454`).
                countSizeLine()?.let { return it }
                if (b != LF) return BodyDecodeError.INVALID_SIZE_LF
                state = if (chunkLen == 0uL) ChunkedState.END_CR else ChunkedState.BODY
            }
            ChunkedState.BODY_CR -> {
                // `read_body_cr` (`decode.rs:505`).
                if (b != CR) return BodyDecodeError.INVALID_BODY_CR
                state = ChunkedState.BODY_LF
            }
            ChunkedState.BODY_LF -> {
                // `read_body_lf` (`decode.rs:517`).
                if (b != LF) return BodyDecodeError.INVALID_BODY_LF
                state = ChunkedState.START
            }
            ChunkedState.TRAILER -> {
                // `read_trailer` (`decode.rs:530`).
                putTrailer(b)?.let { return it }
                state = if (b == CR) ChunkedState.TRAILER_LF else ChunkedState.TRAILER
            }
            ChunkedState.TRAILER_LF -> {
                // `read_trailer_lf` (`decode.rs:551`).
                if (b != LF) return BodyDecodeError.INVALID_TRAILER_LF
                if (trailersCnt >= maxHeaders) return BodyDecodeError.TRAILERS_COUNT_OVERFLOW
                trailersCnt++
                putTrailer(b)?.let { return it }
                state = ChunkedState.END_CR
            }
            ChunkedState.END_CR -> {
                // `read_end_cr` (`decode.rs:585`).
                if (b == CR) {
                    if (trailersBuf != null) putTrailer(b)?.let { return it }
                    state = ChunkedState.END_LF
                } else {
                    if (trailersBuf == null) {
                        // 64 will fit a single Expires header without reallocating.
                        trailersBuf = ByteArray(64)
                        trailersLen = 0
                        appendTrailer(b)
                    } else {
                        putTrailer(b)?.let { return it }
                    }
                    state = ChunkedState.TRAILER
                }
            }
            ChunkedState.END_LF -> {
                // `read_end_lf` (`decode.rs:616`).
                if (b != LF) return BodyDecodeError.INVALID_END_LF
                if (trailersBuf != null) putTrailer(b)?.let { return it }
                state = ChunkedState.END
            }
            ChunkedState.BODY, ChunkedState.END -> error("unreachable: $st")
        }
        return null
    }

    /** Safety baseline: the size line (size, LWS, extensions, CR LF) is at most [maxSizeLine] bytes. */
    private fun countSizeLine(): BodyDecodeError? {
        sizeLineLen++
        return if (sizeLineLen > maxSizeLine) BodyDecodeError.SIZE_LINE_TOO_LONG else null
    }

    /** `size = size * 16 + d` with the digit-count limit (safety baseline) and hyper's overflow check. */
    private fun addDigit(d: Int): BodyDecodeError? {
        sizeDigits++
        if (sizeDigits > MAX_SIZE_DIGITS) return BodyDecodeError.TOO_MANY_SIZE_DIGITS
        val size = chunkLen
        if (size > ULong.MAX_VALUE / 16uL) return BodyDecodeError.CHUNK_SIZE_OVERFLOW
        val mul = size * 16uL
        val sum = mul + d.toULong()
        if (sum < mul) return BodyDecodeError.CHUNK_SIZE_OVERFLOW
        chunkLen = sum
        return null
    }

    /** `put_u8!` (`decode.rs:276`): append, then fail once the section reaches the limit. */
    private fun putTrailer(b: Int): BodyDecodeError? {
        appendTrailer(b)
        return if (trailersLen >= maxTrailerSize) BodyDecodeError.TRAILERS_OVER_LIMIT else null
    }

    private fun appendTrailer(b: Int) {
        var tb = trailersBuf!!
        if (trailersLen == tb.size) {
            tb = tb.copyOf(tb.size * 2)
            trailersBuf = tb
        }
        tb[trailersLen++] = b.toByte()
    }

    override fun toString(): String = when (kind) {
        KIND_LENGTH -> "Length($remaining)"
        KIND_CHUNKED -> "Chunked(state=$state, chunkLen=$chunkLen, extensionsCnt=$extensionsCnt)"
        else -> "Eof($sawEof)"
    }

    companion object {
        /** hyper `role.rs DEFAULT_MAX_HEADERS`: the trailer field count limit when none is configured. */
        const val DEFAULT_MAX_HEADERS: Int = 100

        /** Safety baseline trailer section limit (hyper's `TRAILER_LIMIT` is 16 KiB), SPEC §3.9. */
        const val DEFAULT_MAX_TRAILER_SIZE: Int = 8 * 1024

        /** Safety baseline limit of one chunk size line, SPEC §3.9. */
        const val DEFAULT_MAX_CHUNK_SIZE_LINE: Int = 1024

        /** `CHUNKED_EXTENSIONS_LIMIT` (`decode.rs:20`): extension bytes allowed in the whole body. */
        const val CHUNKED_EXTENSIONS_LIMIT: Long = 16 * 1024

        /** Safety baseline: hex digits allowed in a chunk size. */
        const val MAX_SIZE_DIGITS: Int = 16

        /** Bytes per data frame of an EOF-delimited body (`decode.rs:228`). */
        const val EOF_READ_SIZE: Int = 8192

        private const val KIND_LENGTH = 0
        private const val KIND_CHUNKED = 1
        private const val KIND_EOF = 2

        /** `Decoder::length` (`decode.rs:89`): a body of exactly [length] bytes (`Content-Length`). */
        fun length(length: Long): BodyDecoder {
            require(length >= 0) { "negative length: $length" }
            return BodyDecoder(KIND_LENGTH, length, 0, 0, 0)
        }

        /**
         * `Decoder::chunked` (`decode.rs:95`): a `Transfer-Encoding: chunked` body.
         *
         * @param maxHeaders trailer field count limit (`h1_max_headers`).
         * @param maxTrailerSize trailer section byte limit (`h1_max_header_size`); the section must stay below it.
         * @param maxChunkSizeLine safety baseline limit of one chunk size line, extensions included.
         */
        fun chunked(
            maxHeaders: Int = DEFAULT_MAX_HEADERS,
            maxTrailerSize: Int = DEFAULT_MAX_TRAILER_SIZE,
            maxChunkSizeLine: Int = DEFAULT_MAX_CHUNK_SIZE_LINE,
        ): BodyDecoder {
            require(maxHeaders >= 0 && maxTrailerSize > 0 && maxChunkSizeLine > 0) { "bad limits" }
            return BodyDecoder(KIND_CHUNKED, 0, maxHeaders, maxTrailerSize, maxChunkSizeLine)
        }

        /** `Decoder::eof` (`decode.rs:112`): a response body delimited by the end of the connection. */
        fun eof(): BodyDecoder = BodyDecoder(KIND_EOF, 0, 0, 0, 0)
    }
}

/** hyper's `ChunkedState` (`decode.rs:70`), every state kept. */
internal enum class ChunkedState {
    START, SIZE, SIZE_LWS, EXTENSION, SIZE_LF, BODY, BODY_CR, BODY_LF, TRAILER, TRAILER_LF, END_CR, END_LF, END,
}

/**
 * `decode_trailers` (`decode.rs:639`): parses the trailer section `bytes[offset until offset + length]` (ending with
 * the empty line) holding at most [count] fields into a [HeaderMap], keeping duplicate fields. Bare `LF` line
 * endings are rejected (SPEC §3.9). The values share [bytes], which the caller must not modify afterwards.
 *
 * @throws BodyDecodeException for an invalid or incomplete section.
 */
internal fun decodeTrailers(bytes: ByteArray, offset: Int, length: Int, count: Int): HeaderMap<HeaderValue> {
    val trailers = HeaderMap<HeaderValue>()
    val slots = HeaderSlots(count)
    val status = parseHeaders(bytes, offset, length, slots, allowBareLf = false)
    if (status == ParseStatus.PARTIAL) throw BodyDecodeException(BodyDecodeError.PARTIAL_TRAILERS)
    if (status < 0) throw BodyDecodeException(BodyDecodeError.INVALID_TRAILERS)
    for (i in 0 until slots.count) {
        val ns = slots.nameStart[i]
        val name = HeaderName.tryFromBytes(bytes, ns, slots.nameEnd[i] - ns)
            ?: throw BodyDecodeException(BodyDecodeError.INVALID_TRAILER_NAME)
        val vs = slots.valueStart[i]
        val value = HeaderValue.tryFromMaybeShared(bytes, vs, slots.valueEnd[i] - vs)
            ?: throw BodyDecodeException(BodyDecodeError.INVALID_TRAILER_VALUE)
        trailers.append(name, value)
    }
    return trailers
}

private const val TAB = '\t'.code
private const val SP = ' '.code
private const val SEMI = ';'.code
private const val CR = '\r'.code
private const val LF = '\n'.code

private fun hexValue(b: Int): Int = when (b) {
    in '0'.code..'9'.code -> b - '0'.code
    in 'a'.code..'f'.code -> b + 10 - 'a'.code
    in 'A'.code..'F'.code -> b + 10 - 'A'.code
    else -> -1
}
