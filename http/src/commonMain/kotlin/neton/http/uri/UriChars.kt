package neton.http.uri

// Shared byte tables and text helpers of the URI parser (http 1.5.0 `uri/mod.rs`, `scheme.rs`, `path.rs`).
//
// The parser works on a Kotlin `String` whose chars below 128 are looked up in these tables; any char at or
// above 128 stands for a UTF-8 multi-byte sequence in the reference (a "high" byte). Byte input is validated
// as UTF-8 and decoded once, then parsed the same way (see `parseUtf8Input`).

/** Longest accepted URI / path-and-query in UTF-8 bytes (`u16::MAX - 1`; `u16::MAX` is the reference's "none"). */
internal const val MAX_LEN: Int = 65534

/** Longest accepted scheme in bytes. */
internal const val MAX_SCHEME_LEN: Int = 64

/** Marker for "no query" in [PathAndQuery]. */
internal const val NONE: Int = -1

private fun charTable(valid: String): ByteArray {
    val t = ByteArray(256)
    for (c in valid) t[c.code] = c.code.toByte()
    return t
}

/**
 * URI_CHARS: 0 for invalid characters, otherwise the character itself. Every entry above 127 is invalid.
 * Same set as the reference table (`mod.rs:154`).
 */
internal val URI_CHARS: ByteArray =
    charTable("!#$&'()*+,-./0123456789:;=?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]_abcdefghijklmnopqrstuvwxyz~")

/** scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ), plus ':' and '~' exactly as the reference table (`scheme.rs:216`). */
internal val SCHEME_CHARS: ByteArray =
    charTable("+-.0123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz~")

internal const val CLASS_VALID: Byte = 0
internal const val CLASS_QUERY: Byte = 1
internal const val CLASS_FRAGMENT: Byte = 2
internal const val CLASS_HIGH: Byte = 3
internal const val CLASS_INVALID: Byte = 4

/** Path byte classes for the ASCII range (`path.rs` `build_path_map`); everything >= 0x80 is CLASS_HIGH. */
internal val PATH_MAP: ByteArray = ByteArray(128) { i ->
    when {
        i == '?'.code -> CLASS_QUERY
        i == '#'.code -> CLASS_FRAGMENT
        i == 0x21 || i in 0x24..0x3B || i == 0x3D || i in 0x40..0x5F || i in 0x61..0x7A || i == 0x7C || i == 0x7E ->
            CLASS_VALID
        // Should be percent-encoded, but accepted for parity with clients that send them as-is.
        i == '"'.code || i == '{'.code || i == '}'.code -> CLASS_VALID
        else -> CLASS_INVALID
    }
}

/** Query byte classes for the ASCII range (`path.rs` `build_query_map`); everything >= 0x80 is CLASS_HIGH. */
internal val QUERY_MAP: ByteArray = ByteArray(128) { i ->
    when {
        i == '#'.code -> CLASS_FRAGMENT
        i == 0x21 || i in 0x24..0x3B || i == 0x3D || i in 0x3F..0x7E -> CLASS_VALID
        else -> CLASS_INVALID
    }
}

/** ASCII case-insensitive comparison of `a[aFrom, aFrom+len)` with `b[bFrom, bFrom+len)` (no Unicode folding). */
internal fun asciiRegionEqualsIgnoreCase(a: String, aFrom: Int, b: String, bFrom: Int, len: Int): Boolean {
    for (k in 0 until len) {
        val x = a[aFrom + k]
        val y = b[bFrom + k]
        if (x != y && asciiLower(x) != asciiLower(y)) return false
    }
    return true
}

internal fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

/** Case-sensitive comparison of two regions. */
internal fun regionEquals(a: String, aFrom: Int, b: String, bFrom: Int, len: Int): Boolean {
    for (k in 0 until len) if (a[aFrom + k] != b[bFrom + k]) return false
    return true
}

/**
 * Compares two regions in UTF-8 byte order (the order of Rust's `str` comparison), which is code point order.
 * UTF-16 code unit order differs only between surrogates and U+E000..U+FFFF, which is corrected here.
 */
internal fun compareUtf8Order(a: String, aFrom: Int, aTo: Int, b: String, bFrom: Int, bTo: Int): Int {
    val n = minOf(aTo - aFrom, bTo - bFrom)
    for (k in 0 until n) {
        val x = a[aFrom + k]
        val y = b[bFrom + k]
        if (x != y) return codePointOrderKey(x) - codePointOrderKey(y)
    }
    return (aTo - aFrom) - (bTo - bFrom)
}

private fun codePointOrderKey(c: Char): Int {
    val v = c.code
    return when {
        v >= 0xE000 -> v - 0x800
        v >= 0xD800 -> v + 0x2000
        else -> v
    }
}

/** Whether the UTF-8 encoding of `s[from, to)` is longer than [limit] bytes. Cheap for short strings. */
internal fun utf8LengthExceeds(s: String, from: Int, to: Int, limit: Int): Boolean {
    val n = to - from
    if (n > limit) return true
    if (n.toLong() * 3 <= limit) return false
    var total = 0
    var i = from
    while (i < to) {
        val c = s[i].code
        total += when {
            c < 0x80 -> 1
            c < 0x800 -> 2
            c in 0xD800..0xDBFF && i + 1 < to && s[i + 1].code in 0xDC00..0xDFFF -> { i++; 4 }
            else -> 3
        }
        if (total > limit) return true
        i++
    }
    return false
}

/** Whether `s[from, to)` is well-formed UTF-16 (no unpaired surrogate), i.e. encodable as valid UTF-8. */
internal fun isWellFormedUtf16(s: String, from: Int, to: Int): Boolean {
    var i = from
    while (i < to) {
        val c = s[i].code
        if (c in 0xD800..0xDBFF) {
            if (i + 1 >= to || s[i + 1].code !in 0xDC00..0xDFFF) return false
            i += 2
            continue
        }
        if (c in 0xDC00..0xDFFF) return false
        i++
    }
    return true
}

/**
 * Index of the first byte of `b[from, to)` that does not start a valid UTF-8 sequence (Rust `str::from_utf8`
 * rules: no overlongs, no surrogates, nothing above U+10FFFF), or -1 when the whole range is valid.
 */
internal fun firstInvalidUtf8(b: ByteArray, from: Int, to: Int): Int {
    var i = from
    while (i < to) {
        val x = b[i].toInt() and 0xFF
        if (x < 0x80) { i++; continue }
        val w = when (x) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> return i
        }
        if (i + w > to) return i
        val b1 = b[i + 1].toInt() and 0xFF
        val ok1 = when (x) {
            0xE0 -> b1 in 0xA0..0xBF
            0xED -> b1 in 0x80..0x9F
            0xF0 -> b1 in 0x90..0xBF
            0xF4 -> b1 in 0x80..0x8F
            else -> b1 in 0x80..0xBF
        }
        if (!ok1) return i
        for (k in 2 until w) if ((b[i + k].toInt() and 0xC0) != 0x80) return i
        i += w
    }
    return -1
}

/**
 * Parses byte input through a String parser with the reference's UTF-8 semantics.
 *
 * Valid UTF-8 is decoded once and parsed. Otherwise the input is decoded lossily (invalid sequences become
 * U+FFFD, which every scheme/authority rule rejects exactly where the raw byte would be rejected), parsed, and on
 * success the kept path-and-query bytes (from [pqStartOf] up to the first `#`) are checked: invalid UTF-8 there is
 * `InvalidUriChar`, invalid UTF-8 inside the dropped fragment is accepted, as in the reference.
 *
 * [pqStartOf] returns the char index where the parsed path-and-query starts (equal to the byte index, as everything
 * before it is validated ASCII), or -1 when the result has no path-and-query taken from the input.
 */
internal inline fun parseUtf8Input(
    bytes: ByteArray,
    offset: Int,
    length: Int,
    parse: (String) -> Any,
    pqStartOf: (Any) -> Int,
): Any {
    val end = offset + length
    if (firstInvalidUtf8(bytes, offset, end) < 0) return parse(bytes.decodeToString(offset, end))
    val r = parse(bytes.decodeToString(offset, end))
    if (r is InvalidUri.ErrorKind) return r
    val pqStart = pqStartOf(r)
    if (pqStart < 0) return InvalidUri.ErrorKind.InvalidUriChar
    var keep = offset + pqStart
    while (keep < end && bytes[keep] != '#'.code.toByte()) keep++
    return if (firstInvalidUtf8(bytes, offset + pqStart, keep) >= 0) InvalidUri.ErrorKind.InvalidUriChar else r
}

// Inline check, message built out of line: the string template's temporaries made every call zero a frame.
@Suppress("NOTHING_TO_INLINE")
internal inline fun checkRange(size: Int, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > size - length) rangeError(offset, length, size)
}

internal fun rangeError(offset: Int, length: Int, size: Int): Nothing =
    throw IndexOutOfBoundsException("offset=$offset length=$length size=$size")
