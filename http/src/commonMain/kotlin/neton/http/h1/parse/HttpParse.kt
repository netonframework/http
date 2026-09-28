package neton.http.h1.parse

import neton.http.h1.parse.ParseStatus.PARTIAL

// HTTP/1 head parser, ported from httparse 1.10.1 `src/lib.rs`.
//
// The reference walks a `Bytes` cursor and returns `Result<Status<T>, Error>`. Here every step is a function
// `(buf, pos, end) -> Int` that returns the new cursor position (>= 0) or a negative status: PARTIAL or an
// HttpParseError code. Parsed parts are written as offsets straight into the caller's ParsedRequest /
// ParsedResponse / HeaderSlots, so a parse allocates nothing and never throws.
//
// Deviation (SPEC §3.1, §3.9): a bare LF line ending is accepted only with `allowBareLf`; otherwise it is the
// error the reference would report for any other invalid byte at that position.

private const val ERR_HEADER_NAME = -2
private const val ERR_HEADER_VALUE = -3
private const val ERR_NEW_LINE = -4
private const val ERR_STATUS = -5
private const val ERR_TOKEN = -6
private const val ERR_TOO_MANY_HEADERS = -7
private const val ERR_VERSION = -8

// "HTTP/1.0" and "HTTP/1.1" as little-endian words (what `loadWord` returns for those 8 bytes).
private const val H10: Long = 0x302E312F50545448L
private const val H11: Long = 0x312E312F50545448L

private fun ub(buf: ByteArray, i: Int): Int = buf[i].toInt() and 0xFF

/** `lib.rs Request::parse_with_config_and_uninit_headers`. Returns consumed bytes or a negative status. */
internal fun parseRequestHead(req: ParsedRequest, buf: ByteArray, start: Int, end: Int, config: ParserConfig): Int {
    var p = skipEmptyLines(buf, start, end, config.allowBareLf)
    if (p < 0) return p

    // parse_method, with the `GET ` / `POST ` fast paths.
    val methodStart = p
    if (end - p >= 4 && ub(buf, p) == 'G'.code && ub(buf, p + 1) == 'E'.code && ub(buf, p + 2) == 'T'.code &&
        ub(buf, p + 3) == SP
    ) {
        req.setMethod(p, p + 3)
        p += 4
    } else if (end - p >= 5 && ub(buf, p) == 'P'.code && ub(buf, p + 1) == 'O'.code &&
        ub(buf, p + 2) == 'S'.code && ub(buf, p + 3) == 'T'.code && ub(buf, p + 4) == SP
    ) {
        req.setMethod(p, p + 4)
        p += 5
    } else {
        p = parseToken(buf, p, end)
        if (p < 0) return p
        req.setMethod(methodStart, p - 1)
    }

    if (config.allowMultipleSpacesInRequestLineDelimiters) {
        p = skipSpaces(buf, p, end)
        if (p < 0) return p
    }

    // parse_uri
    val uriStart = p
    val uriEnd = scanUri(buf, p, end)
    p = uriEnd
    if (p >= end) return PARTIAL
    if (ub(buf, p) != SP) return ERR_TOKEN
    p++
    if (uriEnd == uriStart) return ERR_TOKEN // URI must have at least one char
    if (!isValidUtf8(buf, uriStart, uriEnd)) return ERR_TOKEN
    req.setPath(uriStart, uriEnd)

    if (config.allowMultipleSpacesInRequestLineDelimiters) {
        p = skipSpaces(buf, p, end)
        if (p < 0) return p
    }

    val v = parseVersion(buf, p, end)
    if (v < 0) return v
    req.setVersion(v)
    p += 8

    // newline!
    if (p >= end) return PARTIAL
    when (ub(buf, p++)) {
        CR -> {
            if (p >= end) return PARTIAL
            if (ub(buf, p++) != LF) return ERR_NEW_LINE
        }
        LF -> if (!config.allowBareLf) return ERR_NEW_LINE
        else -> return ERR_NEW_LINE
    }

    p = parseHeadersIter(
        buf, p, end, req.headers,
        allowSpacesAfterHeaderName = false,
        allowObsoleteMultilineHeaders = false,
        allowSpaceBeforeFirstHeaderName = config.allowSpaceBeforeFirstHeaderName,
        ignoreInvalidHeaders = config.ignoreInvalidHeadersInRequests,
        allowBareLf = config.allowBareLf,
    )
    return if (p < 0) p else p - start
}

/** `lib.rs Response::parse_with_config_and_uninit_headers`. Returns consumed bytes or a negative status. */
internal fun parseResponseHead(res: ParsedResponse, buf: ByteArray, start: Int, end: Int, config: ParserConfig): Int {
    var p = skipEmptyLines(buf, start, end, config.allowBareLf)
    if (p < 0) return p

    val v = parseVersion(buf, p, end)
    if (v < 0) return v
    res.setVersion(v)
    p += 8

    // space!(bytes or Error::Version)
    if (p >= end) return PARTIAL
    if (ub(buf, p++) != SP) return ERR_VERSION

    if (config.allowMultipleSpacesInResponseStatusDelimiters) {
        p = skipSpaces(buf, p, end)
        if (p < 0) return p
    }

    // parse_code: exactly three digits.
    var code = 0
    for (k in 0 until 3) {
        if (p >= end) return PARTIAL
        val d = ub(buf, p++) - '0'.code
        if (d < 0 || d > 9) return ERR_STATUS
        code = code * 10 + d
    }
    res.setCode(code)

    // A SP means parse a reason-phrase, a newline means go to headers, anything else is a malformed status.
    if (p >= end) return PARTIAL
    when (ub(buf, p++)) {
        SP -> {
            if (config.allowMultipleSpacesInResponseStatusDelimiters) {
                p = skipSpaces(buf, p, end)
                if (p < 0) return p
            }
            p = parseReason(res, buf, p, end, config.allowBareLf)
            if (p < 0) return p
        }
        CR -> {
            if (p >= end) return PARTIAL
            if (ub(buf, p++) != LF) return ERR_STATUS
            res.setReason(p, p)
        }
        LF -> {
            if (!config.allowBareLf) return ERR_STATUS
            res.setReason(p, p)
        }
        else -> return ERR_STATUS
    }

    p = parseHeadersIter(
        buf, p, end, res.headers,
        allowSpacesAfterHeaderName = config.allowSpacesAfterHeaderNameInResponses,
        allowObsoleteMultilineHeaders = config.allowObsoleteMultilineHeadersInResponses,
        allowSpaceBeforeFirstHeaderName = config.allowSpaceBeforeFirstHeaderName,
        ignoreInvalidHeaders = config.ignoreInvalidHeadersInResponses,
        allowBareLf = config.allowBareLf,
    )
    return if (p < 0) p else p - start
}

/**
 * Parses a block of header lines ending with an empty line, such as chunked trailers (httparse
 * `lib.rs parse_headers`), from `bytes[offset until offset + length]` into [headers].
 *
 * Returns the number of bytes consumed from [offset] when complete (and sets [HeaderSlots.count]),
 * [ParseStatus.PARTIAL], or a negative [HttpParseError.code]. The reference parses with every header switch off;
 * [allowBareLf] restores its bare-`LF` acceptance. Allocation-free.
 */
fun parseHeaders(
    bytes: ByteArray,
    offset: Int = 0,
    length: Int = bytes.size - offset,
    headers: HeaderSlots,
    allowBareLf: Boolean = false,
): Int {
    checkRange(bytes, offset, length)
    val p = parseHeadersIter(
        bytes, offset, offset + length, headers,
        allowSpacesAfterHeaderName = false,
        allowObsoleteMultilineHeaders = false,
        allowSpaceBeforeFirstHeaderName = false,
        ignoreInvalidHeaders = false,
        allowBareLf = allowBareLf,
    )
    return if (p < 0) p else p - offset
}

/** `lib.rs skip_empty_lines`. */
private fun skipEmptyLines(buf: ByteArray, start: Int, end: Int, allowBareLf: Boolean): Int {
    var p = start
    while (true) {
        if (p >= end) return PARTIAL
        when (ub(buf, p)) {
            CR -> {
                p++
                if (p >= end) return PARTIAL
                if (ub(buf, p++) != LF) return ERR_NEW_LINE
            }
            LF -> {
                if (!allowBareLf) return ERR_NEW_LINE
                p++
            }
            else -> return p
        }
    }
}

/** `lib.rs skip_spaces`: skips `SP` only; `Partial` if the input ends. */
private fun skipSpaces(buf: ByteArray, start: Int, end: Int): Int {
    var p = start
    while (true) {
        if (p >= end) return PARTIAL
        if (ub(buf, p) != SP) return p
        p++
    }
}

/**
 * `lib.rs parse_version`: returns the minor version (0 or 1) with the cursor to be advanced by 8, or a negative
 * status. With fewer than 8 bytes the available prefix of `HTTP/1.` is checked: a mismatch is an error, otherwise
 * `Partial`.
 */
private fun parseVersion(buf: ByteArray, p: Int, end: Int): Int {
    if (end - p >= 8) {
        val block = loadWord(buf, p)
        return when (block) {
            H10 -> 0
            H11 -> 1
            else -> ERR_VERSION
        }
    }
    val prefix = "HTTP/1."
    var i = 0
    while (i < prefix.length) {
        if (p + i >= end) return PARTIAL
        if (ub(buf, p + i) != prefix[i].code) return ERR_VERSION
        i++
    }
    return PARTIAL
}

/** `lib.rs parse_token`: tchar+ followed by `SP`; returns the position after the space. */
private fun parseToken(buf: ByteArray, start: Int, end: Int): Int {
    var p = start
    if (p >= end) return PARTIAL
    // The first char must be a token char; a space would mean an empty token.
    if (!isMethodToken(ub(buf, p++))) return ERR_TOKEN
    while (true) {
        if (p >= end) return PARTIAL
        val b = ub(buf, p++)
        if (b == SP) return p
        if (!isMethodToken(b)) return ERR_TOKEN
    }
}

/**
 * `lib.rs parse_reason`: `*( HTAB / SP / VCHAR / obs-text )` up to the line end. A reason containing obs-text is
 * reported as empty.
 */
private fun parseReason(res: ParsedResponse, buf: ByteArray, start: Int, end: Int, allowBareLf: Boolean): Int {
    var p = start
    var seenObsText = false
    while (true) {
        if (p >= end) return PARTIAL
        val b = ub(buf, p++)
        if (b == CR) {
            if (p >= end) return PARTIAL
            if (ub(buf, p++) != LF) return ERR_STATUS
            if (seenObsText) res.setReason(start, start) else res.setReason(start, p - 2)
            return p
        } else if (b == LF) {
            if (!allowBareLf) return ERR_STATUS
            if (seenObsText) res.setReason(start, start) else res.setReason(start, p - 1)
            return p
        } else if (!(b == HTAB || b == SP || (b in 0x21..0x7E) || b >= 0x80)) {
            return ERR_STATUS
        } else if (b >= 0x80) {
            seenObsText = true
        }
    }
}

/**
 * `handle_invalid_char!` of `lib.rs parse_headers_iter_uninit`: with [ignore] off returns [err]; otherwise skips
 * the rest of the current line ([b] is the offending byte, already consumed, at `p - 1`) and returns the position
 * of the next line. A `NUL` or a lone `CR` (and a bare `LF` unless [allowBareLf]) is still [err].
 */
private fun skipInvalidLine(buf: ByteArray, start: Int, end: Int, first: Int, err: Int, ignore: Boolean, allowBareLf: Boolean): Int {
    if (!ignore) return err
    var p = start
    var b = first
    while (true) {
        if (b == CR) {
            if (p >= end) return PARTIAL
            if (ub(buf, p++) != LF) return err
            return p
        }
        if (b == LF) {
            return if (allowBareLf) p else err
        }
        if (b == 0) return err
        if (p >= end) return PARTIAL
        b = ub(buf, p++)
    }
}

/**
 * `lib.rs parse_headers_iter_uninit`: parses header lines into [slots] up to and including the empty line that
 * ends the head. Returns the position after that line (and sets `slots.count`) or a negative status (leaving
 * `slots.count` as it was).
 */
internal fun parseHeadersIter(
    buf: ByteArray,
    start: Int,
    end: Int,
    slots: HeaderSlots,
    allowSpacesAfterHeaderName: Boolean,
    allowObsoleteMultilineHeaders: Boolean,
    allowSpaceBeforeFirstHeaderName: Boolean,
    ignoreInvalidHeaders: Boolean,
    allowBareLf: Boolean,
): Int {
    var p = start
    var n = 0
    val capacity = slots.capacity

    headers@ while (true) {
        // A newline here means the head is over.
        if (p >= end) return PARTIAL
        var b = ub(buf, p++)
        if (b == CR) {
            if (p >= end) return PARTIAL
            if (ub(buf, p++) != LF) return ERR_NEW_LINE
            break@headers
        }
        if (b == LF) {
            if (!allowBareLf) return ERR_NEW_LINE
            break@headers
        }
        if (!isHeaderNameToken(b)) {
            if (allowSpaceBeforeFirstHeaderName && n == 0 && (b == SP || b == HTAB)) {
                // Advance past the whitespace and try parsing the header again.
                while (p < end && (ub(buf, p) == SP || ub(buf, p) == HTAB)) p++
                continue@headers
            }
            p = skipInvalidLine(buf, p, end, b, ERR_HEADER_NAME, ignoreInvalidHeaders, allowBareLf)
            if (p < 0) return p
            continue@headers
        }

        // Header name, up to the colon.
        val nameStart = p - 1
        p = scanHeaderName(buf, p, end)
        if (p >= end) return PARTIAL
        val nameEnd = p
        b = ub(buf, p++)
        if (b != ':'.code) {
            var colon = false
            if (allowSpacesAfterHeaderName) {
                while (b == SP || b == HTAB) {
                    if (p >= end) return PARTIAL
                    b = ub(buf, p++)
                    if (b == ':'.code) {
                        colon = true
                        break
                    }
                }
            }
            if (!colon) {
                p = skipInvalidLine(buf, p, end, b, ERR_HEADER_NAME, ignoreInvalidHeaders, allowBareLf)
                if (p < 0) return p
                continue@headers
            }
        }

        // Header value.
        var valueStart = 0
        var valueEnd = 0
        var committed = p // start of the reference's current `Bytes` slice
        value@ while (true) {
            // Eat whitespace between the colon and the value.
            whitespace@ while (true) {
                if (p >= end) return PARTIAL
                b = ub(buf, p++)
                if (b == SP || b == HTAB) {
                    committed = p
                    continue@whitespace
                }
                if (isHeaderValueToken(b)) break@whitespace

                if (b == CR) {
                    if (p >= end) return PARTIAL
                    if (ub(buf, p++) != LF) return ERR_HEADER_VALUE
                } else if (b == LF) {
                    if (!allowBareLf) return ERR_HEADER_VALUE
                } else {
                    p = skipInvalidLine(buf, p, end, b, ERR_HEADER_VALUE, ignoreInvalidHeaders, allowBareLf)
                    if (p < 0) return p
                    continue@headers
                }

                if (allowObsoleteMultilineHeaders) {
                    // The next byte may be a space, in which case this header uses obsolete line folding.
                    if (p >= end) return PARTIAL
                    val next = ub(buf, p)
                    if (next == SP || next == HTAB) continue@whitespace
                }
                // An empty value.
                valueStart = committed
                valueEnd = committed
                break@value
            }

            valueStart = p - 1
            lines@ while (true) {
                // Parse the value till EOL.
                p = scanHeaderValue(buf, p, end)
                if (p >= end) return PARTIAL
                b = ub(buf, p++)
                val skip: Int
                if (b == CR) {
                    if (p >= end) return PARTIAL
                    if (ub(buf, p++) != LF) return ERR_HEADER_VALUE
                    skip = 2
                } else if (b == LF) {
                    if (!allowBareLf) return ERR_HEADER_VALUE
                    skip = 1
                } else {
                    p = skipInvalidLine(buf, p, end, b, ERR_HEADER_VALUE, ignoreInvalidHeaders, allowBareLf)
                    if (p < 0) return p
                    continue@headers
                }
                if (allowObsoleteMultilineHeaders) {
                    if (p >= end) return PARTIAL
                    val next = ub(buf, p)
                    if (next == SP || next == HTAB) continue@lines
                }
                valueEnd = p - skip
                break@value
            }
        }

        if (n >= capacity) return ERR_TOO_MANY_HEADERS

        // Trim trailing whitespace (obs-fold values may end with folded blank lines).
        while (valueEnd > valueStart) {
            val c = ub(buf, valueEnd - 1)
            if (c != SP && c != HTAB && c != CR && c != LF) break
            valueEnd--
        }
        slots.set(n, nameStart, nameEnd, valueStart, valueEnd)
        n++
    }
    slots.count = n
    return p
}

/**
 * `core::str::from_utf8` on `buf[start until end]`, allocation-free: well-formed UTF-8 per RFC 3629 (no
 * overlong forms, no surrogates, nothing above U+10FFFF).
 */
internal fun isValidUtf8(buf: ByteArray, start: Int, end: Int): Boolean {
    var i = start
    while (i < end) {
        val b0 = ub(buf, i)
        if (b0 < 0x80) {
            i++
            continue
        }
        when {
            b0 in 0xC2..0xDF -> {
                if (end - i < 2 || !isCont(ub(buf, i + 1))) return false
                i += 2
            }
            b0 in 0xE0..0xEF -> {
                if (end - i < 3) return false
                val b1 = ub(buf, i + 1)
                val ok1 = when (b0) {
                    0xE0 -> b1 in 0xA0..0xBF
                    0xED -> b1 in 0x80..0x9F
                    else -> isCont(b1)
                }
                if (!ok1 || !isCont(ub(buf, i + 2))) return false
                i += 3
            }
            b0 in 0xF0..0xF4 -> {
                if (end - i < 4) return false
                val b1 = ub(buf, i + 1)
                val ok1 = when (b0) {
                    0xF0 -> b1 in 0x90..0xBF
                    0xF4 -> b1 in 0x80..0x8F
                    else -> isCont(b1)
                }
                if (!ok1 || !isCont(ub(buf, i + 2)) || !isCont(ub(buf, i + 3))) return false
                i += 4
            }
            else -> return false
        }
    }
    return true
}

private fun isCont(b: Int): Boolean = b and 0xC0 == 0x80

/**
 * Receives the size parsed by [parseChunkSize] (so that parsing allocates nothing).
 */
class ChunkSize {
    /** The chunk size (up to 16 hex digits, so the full unsigned 64-bit range). */
    var size: ULong = 0uL
        internal set
}

/**
 * Parses a chunk-size line (httparse `lib.rs parse_chunk_size`) from `bytes[offset until offset + length]`.
 *
 * Returns the number of bytes consumed from [offset] (the chunk data starts at `offset + result`) and stores the
 * size in [out]; [ParseStatus.PARTIAL]; or [ParseStatus.INVALID_CHUNK_SIZE]. At most 16 hex digits are accepted.
 * Chunk extensions after `;` are skipped without validation; the line must end with `CR LF`.
 *
 * hyper does not use this function (its chunked decoder has its own state machine, SPEC §3.4); it is ported for
 * parity with the reference.
 */
fun parseChunkSize(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, out: ChunkSize): Int {
    checkRange(bytes, offset, length)
    val end = offset + length
    var p = offset
    var size = 0uL
    var inChunkSize = true
    var inExt = false
    var count = 0
    while (true) {
        if (p >= end) return PARTIAL
        val b = ub(bytes, p++)
        val digit = when (b) {
            in '0'.code..'9'.code -> b - '0'.code
            in 'a'.code..'f'.code -> b + 10 - 'a'.code
            in 'A'.code..'F'.code -> b + 10 - 'A'.code
            else -> -1
        }
        if (digit >= 0 && inChunkSize) {
            if (count > 15) return ParseStatus.INVALID_CHUNK_SIZE
            count++
            size = size * 16uL + digit.toULong()
            continue
        }
        when {
            b == CR -> {
                if (p >= end) return PARTIAL
                if (ub(bytes, p++) != LF) return ParseStatus.INVALID_CHUNK_SIZE
                out.size = size
                return p - offset
            }
            // If we weren't in the extension yet, the ";" signals its start.
            b == ';'.code && !inExt -> {
                inExt = true
                inChunkSize = false
            }
            // Linear white space is ignored between the chunk size and the extension separator.
            (b == HTAB || b == SP) && !inExt && !inChunkSize -> {}
            // LWS can follow the chunk size, but no more digits can come.
            (b == HTAB || b == SP) && inChunkSize -> inChunkSize = false
            // Any octet is allowed once in the extension; they are all ignored.
            inExt -> {}
            else -> return ParseStatus.INVALID_CHUNK_SIZE
        }
    }
}
