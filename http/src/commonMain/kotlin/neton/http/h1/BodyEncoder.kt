package neton.http.h1

import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes

/**
 * The HTTP/1 message body encoder (hyper `proto/h1/encode.rs` `Encoder`).
 *
 * Three kinds, chosen with the outgoing head (SPEC §3.3): [chunked] (optionally with the trailer fields announced in
 * the `Trailer` header, [withTrailerFields]), [length] (`Content-Length`) and [closeDelimited] (server only: the
 * body ends when the connection closes).
 *
 * The encoder writes the wire format into the connection's write buffer. Chunk headers are hex digits written
 * straight into the buffer's backing array; nothing is allocated. The connection layer uses it as:
 * - each data frame: [encode] (copies the data), or, to queue the data slice separately (writev),
 *   [writeDataPrefix] + the first `n` data bytes + [writeDataSuffix];
 * - the last data frame, when known to be last: [encodeAndEnd] (the last chunk and the terminator are coalesced);
 * - the end of the body: [encodeEnd] with the trailers frame, if any. It returns false when a `Content-Length`
 *   body ended early: the caller reports `BodyWriteAborted` ([remaining] bytes were missing) and closes.
 *
 * Bytes beyond the declared length are silently dropped (`encode.rs:145`).
 */
class BodyEncoder private constructor(
    private val kind: Int,
    private var remainingLen: Long,
    /** `Kind::Chunked(Option<Vec<HeaderName>>)`: the fields announced by `Trailer`. */
    private val trailerFields: List<HeaderName>?,
    /** `is_last`: the connection closes after this message. */
    var isLast: Boolean,
) {
    /** Whether a `Content-Length` body has been fully written (`is_eof`). */
    val isEof: Boolean get() = kind == KIND_LENGTH && remainingLen == 0L

    /** Whether this is a chunked encoder (`is_chunked`). */
    val isChunked: Boolean get() = kind == KIND_CHUNKED

    /** Whether the body is delimited by closing the connection (`is_close_delimited`). */
    val isCloseDelimited: Boolean get() = kind == KIND_CLOSE

    /** For a `Content-Length` body, the bytes still to write; 0 for the other kinds. */
    val remaining: Long get() = if (kind == KIND_LENGTH) remainingLen else 0L

    /**
     * `into_chunked_with_trailing_fields` (`encode.rs:80`): a chunked encoder that sends the listed trailer fields
     * (the names from the `Trailer` header); other kinds are returned unchanged.
     */
    fun withTrailerFields(fields: List<HeaderName>): BodyEncoder =
        if (kind == KIND_CHUNKED) BodyEncoder(KIND_CHUNKED, 0, fields, isLast) else this

    /** `set_last` (`encode.rs:95`). */
    fun setLast(isLast: Boolean): BodyEncoder {
        this.isLast = isLast
        return this
    }

    /**
     * Writes the framing that goes before a data frame of [dataLen] bytes and returns how many of those bytes are to
     * be sent (fewer than [dataLen] only when a `Content-Length` body would be exceeded). The caller then sends the
     * data and calls [writeDataSuffix]. An empty frame writes nothing (a chunk of size 0 would end the body).
     */
    fun writeDataPrefix(dataLen: Int, out: Buffer): Int {
        require(dataLen >= 0) { "negative length" }
        if (dataLen == 0) return 0
        return when (kind) {
            KIND_CHUNKED -> {
                writeChunkSize(dataLen, out)
                dataLen
            }
            KIND_LENGTH -> {
                val rem = remainingLen
                if (dataLen > rem) {
                    remainingLen = 0
                    rem.toInt()
                } else {
                    remainingLen = rem - dataLen
                    dataLen
                }
            }
            else -> dataLen
        }
    }

    /** Writes the framing after a non-empty data frame (`CR LF` for chunked). */
    fun writeDataSuffix(out: Buffer) {
        if (kind == KIND_CHUNKED) writeAscii(CRLF, out)
    }

    /**
     * `Encoder::encode` (`encode.rs:128`): writes a data frame of `src[offset until offset + length]` with its
     * framing into [out]. Returns the number of data bytes written (truncated at the declared length).
     */
    fun encode(src: ByteArray, offset: Int, length: Int, out: Buffer): Int {
        require(offset >= 0 && length >= 0 && offset <= src.size - length) { "bad range" }
        if (length == 0) return 0
        if (kind == KIND_CHUNKED) {
            // One reservation for header, data and CRLF.
            val digits = hexDigits(length)
            out.reserve(digits + 2 + length + 2)
            val a = out.backingArray()
            var w = out.writerIndex()
            w = putHex(length, digits, a, w)
            a[w++] = CR; a[w++] = LF
            src.copyInto(a, w, offset, offset + length)
            w += length
            a[w++] = CR; a[w++] = LF
            out.commitWrite(w - out.writerIndex())
            return length
        }
        val n = writeDataPrefix(length, out)
        out.writeBytes(src, offset, n)
        return n
    }

    /** [encode] for a [Bytes] frame. */
    fun encode(data: Bytes, out: Buffer): Int {
        val n = writeDataPrefix(data.size, out)
        if (n == 0) return 0
        if (n == data.size) out.writeBytes(data) else out.writeBytes(data.slice(0, n))
        writeDataSuffix(out)
        return n
    }

    /** [encode] for a whole array. */
    fun encode(src: ByteArray, out: Buffer): Int = encode(src, 0, src.size, out)

    /**
     * `Encoder::encode_and_end` (`encode.rs:219`): writes the last data frame and ends the body; for chunked, the
     * chunk and the terminator are written together (`...CRLF 0 CRLF CRLF`). Returns whether the connection can be
     * kept alive: false when [isLast], for a close-delimited body, or when a `Content-Length` body is still short.
     */
    fun encodeAndEnd(src: ByteArray, offset: Int, length: Int, out: Buffer): Boolean {
        require(offset >= 0 && length >= 0 && offset <= src.size - length) { "bad range" }
        when (kind) {
            KIND_CHUNKED -> {
                if (length == 0) {
                    writeAscii(END_CHUNK, out)
                    return !isLast
                }
                val digits = hexDigits(length)
                out.reserve(digits + 2 + length + END_WITH_CRLF.size)
                val a = out.backingArray()
                var w = out.writerIndex()
                w = putHex(length, digits, a, w)
                a[w++] = CR; a[w++] = LF
                src.copyInto(a, w, offset, offset + length)
                w += length
                END_WITH_CRLF.copyInto(a, w)
                w += END_WITH_CRLF.size
                out.commitWrite(w - out.writerIndex())
                return !isLast
            }
            KIND_LENGTH -> {
                val rem = remainingLen
                return when {
                    length.toLong() == rem -> {
                        out.writeBytes(src, offset, length); remainingLen = 0; !isLast
                    }
                    length > rem -> {
                        out.writeBytes(src, offset, rem.toInt()); remainingLen = 0; !isLast
                    }
                    else -> {
                        out.writeBytes(src, offset, length); remainingLen = rem - length; false
                    }
                }
            }
            else -> {
                out.writeBytes(src, offset, length)
                return false
            }
        }
    }

    /**
     * `Encoder::end` (`encode.rs:116`): writes the end of the body (`0 CRLF CRLF` for chunked, nothing otherwise).
     * Returns false, writing nothing, when a `Content-Length` body still misses [remaining] bytes (hyper's `NotEof`,
     * reported to the user as `BodyWriteAborted`).
     */
    fun end(out: Buffer): Boolean = when (kind) {
        KIND_CHUNKED -> {
            writeAscii(END_CHUNK, out); true
        }
        KIND_LENGTH -> remainingLen == 0L
        else -> true
    }

    /**
     * `Encoder::encode_trailers` (`encode.rs:163`): writes the last chunk with the trailer section
     * (`0 CRLF fields CRLF`), keeping only the fields listed with [withTrailerFields] and dropping the fields that
     * must not appear in trailers. Returns false, writing nothing, when there is nothing to send: not chunked, no
     * `Trailer` list, or no field left. The body is then ended with [end].
     */
    fun encodeTrailers(trailers: HeaderMap<HeaderValue>, titleCase: Boolean, out: Buffer): Boolean {
        if (kind != KIND_CHUNKED) return false
        val allowed = trailerFields ?: return false
        var any = false
        trailers.forEach { name, _ -> if (!any && isAllowed(name, allowed)) any = true }
        if (!any) return false
        writeAscii(LAST_CHUNK, out)
        trailers.forEach { name, value ->
            if (isAllowed(name, allowed)) writeField(name, value, titleCase, out)
        }
        writeAscii(CRLF, out)
        return true
    }

    /**
     * Ends the body, with the trailers frame if there is one: [encodeTrailers], falling back to [end] when no trailer
     * is sent. Returns false when a `Content-Length` body ended early (`BodyWriteAborted`, [remaining] bytes missing).
     */
    fun encodeEnd(trailers: HeaderMap<HeaderValue>?, out: Buffer, titleCase: Boolean = false): Boolean {
        if (trailers != null && encodeTrailers(trailers, titleCase, out)) return true
        return end(out)
    }

    override fun toString(): String = when (kind) {
        KIND_CHUNKED -> "Chunked(${trailerFields ?: "none"}, isLast=$isLast)"
        KIND_LENGTH -> "Length($remainingLen, isLast=$isLast)"
        else -> "CloseDelimited(isLast=$isLast)"
    }

    companion object {
        private const val KIND_CHUNKED = 0
        private const val KIND_LENGTH = 1
        private const val KIND_CLOSE = 2

        /** `Encoder::chunked` (`encode.rs:67`). */
        fun chunked(): BodyEncoder = BodyEncoder(KIND_CHUNKED, 0, null, false)

        /** `Encoder::length` (`encode.rs:71`): a body of exactly [length] bytes. */
        fun length(length: Long): BodyEncoder {
            require(length >= 0) { "negative length: $length" }
            return BodyEncoder(KIND_LENGTH, length, null, false)
        }

        /** `Encoder::close_delimited` (`encode.rs:76`), server only: the body ends when the connection closes. */
        fun closeDelimited(): BodyEncoder = BodyEncoder(KIND_CLOSE, 0, null, false)

        /**
         * `is_valid_trailer_field` (`encode.rs:264`): the 12 fields never sent as trailers.
         */
        fun isValidTrailerField(name: HeaderName): Boolean = FORBIDDEN_TRAILERS.none { it == name }

        private val FORBIDDEN_TRAILERS: Array<HeaderName> = arrayOf(
            HeaderName.AUTHORIZATION,
            HeaderName.CACHE_CONTROL,
            HeaderName.CONTENT_ENCODING,
            HeaderName.CONTENT_LENGTH,
            HeaderName.CONTENT_RANGE,
            HeaderName.CONTENT_TYPE,
            HeaderName.HOST,
            HeaderName.MAX_FORWARDS,
            HeaderName.SET_COOKIE,
            HeaderName.TRAILER,
            HeaderName.TRANSFER_ENCODING,
            HeaderName.TE,
        )

        private const val CR: Byte = '\r'.code.toByte()
        private const val LF: Byte = '\n'.code.toByte()
        private val CRLF = "\r\n".encodeToByteArray()
        private val COLON_SP = ": ".encodeToByteArray()
        private val LAST_CHUNK = "0\r\n".encodeToByteArray()
        private val END_CHUNK = "0\r\n\r\n".encodeToByteArray()
        private val END_WITH_CRLF = "\r\n0\r\n\r\n".encodeToByteArray()
        private val HEX = "0123456789ABCDEF".encodeToByteArray()

        private fun isAllowed(name: HeaderName, allowed: List<HeaderName>): Boolean {
            var listed = false
            for (i in allowed.indices) if (allowed[i] == name) { listed = true; break }
            return listed && isValidTrailerField(name)
        }

        private fun writeAscii(bytes: ByteArray, out: Buffer) = out.writeBytes(bytes, 0, bytes.size)

        /** `write_headers` / `write_headers_title_case` (`role.rs:1599-1615`) for one field. */
        private fun writeField(name: HeaderName, value: HeaderValue, titleCase: Boolean, out: Buffer) {
            val nb = name.bytes
            if (titleCase) {
                // `title_case` (`role.rs:1585`): uppercase the first letter and every letter after '-'.
                out.reserve(nb.size)
                val a = out.backingArray()
                var w = out.writerIndex()
                var prev = '-'.code.toByte()
                for (c0 in nb) {
                    var c = c0
                    if (prev == '-'.code.toByte() && c >= 'a'.code.toByte() && c <= 'z'.code.toByte()) {
                        c = (c - 32).toByte()
                    }
                    a[w++] = c
                    prev = c
                }
                out.commitWrite(nb.size)
            } else {
                out.writeBytes(nb, 0, nb.size)
            }
            writeAscii(COLON_SP, out)
            out.writeBytes(value.array, value.offset, value.length)
            writeAscii(CRLF, out)
        }

        /** Number of uppercase hex digits of [n] > 0. */
        private fun hexDigits(n: Int): Int = (32 - n.countLeadingZeroBits() + 3) / 4

        private fun putHex(n: Int, digits: Int, a: ByteArray, at: Int): Int {
            var shift = (digits - 1) * 4
            var w = at
            while (shift >= 0) {
                a[w++] = HEX[(n ushr shift) and 0xF]
                shift -= 4
            }
            return w
        }

        /** The chunk header `{len:X} CRLF` (`encode.rs:348`), written in place. */
        private fun writeChunkSize(len: Int, out: Buffer) {
            val digits = hexDigits(len)
            out.reserve(digits + 2)
            val a = out.backingArray()
            var w = putHex(len, digits, a, out.writerIndex())
            a[w++] = CR; a[w] = LF
            out.commitWrite(digits + 2)
        }
    }
}
