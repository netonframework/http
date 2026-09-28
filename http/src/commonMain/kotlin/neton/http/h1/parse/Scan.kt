package neton.http.h1.parse

import kotlin.experimental.ExperimentalNativeApi

// Byte classes and scanners of the HTTP/1 head parser (httparse 1.10.1 `lib.rs` byte maps and `simd/swar.rs`).
//
// The reference picks a scanner at compile/run time: SSE4.2, AVX2, NEON, or the portable SWAR ("SIMD within a
// register") fallback. This port implements the SWAR scanner only: URI and header-value bytes are validated eight
// at a time with a single 64-bit load and a handful of word-wide arithmetic operations; header names use a plain
// byte loop exactly as the reference's SWAR backend does (`match_header_name_vectored` → `match_block`).
//
// The byte classes are expressed as range compares or constant bitmaps (no lookup tables), so they need no
// initialisation and are branch-light.

internal const val CR: Int = '\r'.code
internal const val LF: Int = '\n'.code
internal const val SP: Int = ' '.code
internal const val HTAB: Int = '\t'.code

// tchar bitmap: bit b of TOKEN_LO is set for tchar b in 0..63, bit (b - 64) of TOKEN_HI for b in 64..127.
// tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~" / DIGIT / ALPHA
private const val TOKEN_LO: Long =
    (1L shl '!'.code) or (1L shl '#'.code) or (1L shl '$'.code) or (1L shl '%'.code) or (1L shl '&'.code) or
        (1L shl '\''.code) or (1L shl '*'.code) or (1L shl '+'.code) or (1L shl '-'.code) or (1L shl '.'.code) or
        (0x3FFL shl '0'.code) // '0'..'9'
private const val TOKEN_HI: Long =
    (0x3FFFFFFL shl ('A'.code - 64)) or // 'A'..'Z'
        (0x3FFFFFFL shl ('a'.code - 64)) or // 'a'..'z'
        (1L shl ('^'.code - 64)) or (1L shl ('_'.code - 64)) or (1L shl ('`'.code - 64)) or
        (1L shl ('|'.code - 64)) or (1L shl ('~'.code - 64))

/** `lib.rs TOKEN_MAP` / `is_header_name_token`: [b] (0..255) is a tchar. */
internal fun isHeaderNameToken(b: Int): Boolean =
    if (b < 64) (TOKEN_LO ushr b) and 1L != 0L
    else if (b < 128) (TOKEN_HI ushr (b - 64)) and 1L != 0L
    else false

/** `lib.rs is_method_token`: the same set as [isHeaderNameToken], with the common upper-case letters first. */
internal fun isMethodToken(b: Int): Boolean = (b >= 'A'.code && b <= 'Z'.code) || isHeaderNameToken(b)

/** `lib.rs URI_MAP` / `is_uri_token`: `'!'..=0x7E | 0x80..=0xFF`. */
internal fun isUriToken(b: Int): Boolean = b >= 0x21 && b != 0x7F

/** `lib.rs HEADER_VALUE_MAP` / `is_header_value_token`: `'\t' | ' '..=0x7E | 0x80..=0xFF`. */
internal fun isHeaderValueToken(b: Int): Boolean = b == HTAB || (b >= 0x20 && b != 0x7F)

// ---- SWAR (swar.rs) -------------------------------------------------------------------------------------------

internal const val BLOCK_SIZE: Int = 8

private const val ONES: Long = 0x0101010101010101L
private const val HIGHS: Long = -0x7F7F7F7F7F7F7F80L // 0x8080808080808080
private const val DELS: Long = 0x7F7F7F7F7F7F7F7FL
private const val BANGS: Long = 0x2121212121212121L // '!' repeated
private const val SPACES: Long = 0x2020202020202020L // ' ' repeated

/**
 * Loads 8 bytes at [i] as one native (little-endian) word: byte `i` becomes the least significant byte. All
 * Kotlin/Native targets are little-endian, so this is the reference's `usize::from_ne_bytes` on a 64-bit target.
 * Kotlin/Native's `getLongAt` is a single unaligned load (with a bounds check).
 */
@OptIn(ExperimentalNativeApi::class)
internal fun loadWord(buf: ByteArray, i: Int): Long = buf.getLongAt(i)

/**
 * `swar.rs offsetnz`: index (in memory order) of the first non-zero byte of [block], or [BLOCK_SIZE] if all bytes
 * are zero.
 */
internal fun offsetNonZero(block: Long): Int =
    if (block == 0L) BLOCK_SIZE else block.countTrailingZeroBits() ushr 3

/**
 * `swar.rs match_uri_char_8_swar`: number of leading bytes of [x] (8 bytes, little-endian) that satisfy
 * `33 <= b != 127` (i.e. [isUriToken]); [BLOCK_SIZE] if all do.
 *
 * Borrows of the word-wide subtraction only propagate towards more significant (later) bytes and only out of a
 * byte that is itself flagged, so the first flagged byte is always exact.
 */
internal fun matchUriChar8(x: Long): Int {
    val lt = (x - BANGS) and x.inv() // high bit set where b < 0x21 (for b < 0x80)
    val xorDel = x xor DELS
    val eqDel = (xorDel - ONES) and xorDel.inv() // high bit set where b == 0x7F
    return offsetNonZero((lt or eqDel) and HIGHS)
}

/**
 * `swar.rs match_header_value_char_8_swar`: number of leading bytes of [x] that satisfy `32 <= b != 127`;
 * [BLOCK_SIZE] if all do. HTAB is not matched here and is picked up by the scalar step of [scanHeaderValue],
 * as in the reference.
 */
internal fun matchHeaderValueChar8(x: Long): Int {
    val lt = (x - SPACES) and x.inv()
    val xorDel = x xor DELS
    val eqDel = (xorDel - ONES) and xorDel.inv()
    return offsetNonZero((lt or eqDel) and HIGHS)
}

/**
 * `swar.rs match_uri_vectored`: advances from [pos] over URI bytes, returning the index of the first byte in
 * `[pos, end)` that is not a URI byte (or [end]).
 */
internal fun scanUri(buf: ByteArray, pos: Int, end: Int): Int {
    var p = pos
    while (true) {
        if (end - p >= BLOCK_SIZE) {
            val n = matchUriChar8(loadWord(buf, p))
            p += n
            if (n == BLOCK_SIZE) continue
        }
        if (p < end && isUriToken(buf[p].toInt() and 0xFF)) {
            p++
            continue
        }
        return p
    }
}

/**
 * `swar.rs match_header_value_vectored`: advances from [pos] over header-value bytes (including HTAB), returning
 * the index of the first byte in `[pos, end)` that is not one (or [end]).
 */
internal fun scanHeaderValue(buf: ByteArray, pos: Int, end: Int): Int {
    var p = pos
    while (true) {
        if (end - p >= BLOCK_SIZE) {
            val n = matchHeaderValueChar8(loadWord(buf, p))
            p += n
            if (n == BLOCK_SIZE) continue
        }
        if (p < end && isHeaderValueToken(buf[p].toInt() and 0xFF)) {
            p++
            continue
        }
        return p
    }
}

/**
 * `swar.rs match_header_name_vectored`: advances from [pos] over tchar bytes, returning the index of the first
 * non-tchar byte in `[pos, end)` (or [end]). The reference's SWAR backend matches names byte by byte (tchar is not a
 * range); here eight bytes are loaded at once and tested in the register, unrolled: a byte loop on K/N costs a
 * safepoint poll and a bounds check per byte. Same result.
 */
internal fun scanHeaderName(buf: ByteArray, pos: Int, end: Int): Int {
    var p = pos
    while (end - p >= BLOCK_SIZE) {
        val n = matchHeaderNameChar8(loadWord(buf, p))
        p += n
        if (n != BLOCK_SIZE) return p
    }
    while (p < end && isHeaderNameToken(buf[p].toInt() and 0xFF)) p++
    return p
}

/** Number of leading bytes of [x] (8 bytes, little-endian) that are tchars; [BLOCK_SIZE] if all are. */
internal fun matchHeaderNameChar8(x: Long): Int {
    if (!isHeaderNameToken((x and 0xFF).toInt())) return 0
    if (!isHeaderNameToken(((x ushr 8) and 0xFF).toInt())) return 1
    if (!isHeaderNameToken(((x ushr 16) and 0xFF).toInt())) return 2
    if (!isHeaderNameToken(((x ushr 24) and 0xFF).toInt())) return 3
    if (!isHeaderNameToken(((x ushr 32) and 0xFF).toInt())) return 4
    if (!isHeaderNameToken(((x ushr 40) and 0xFF).toInt())) return 5
    if (!isHeaderNameToken(((x ushr 48) and 0xFF).toInt())) return 6
    if (!isHeaderNameToken((x ushr 56).toInt())) return 7
    return BLOCK_SIZE
}
