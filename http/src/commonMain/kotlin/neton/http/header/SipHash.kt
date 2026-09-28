package neton.http.header

/*
 * SipHash-1-3 (Aumasson and Bernstein, "SipHash: a fast short-input PRF", with c = 1 compression round and d = 3
 * finalization rounds), the keyed hash behind Rust's `RandomState`. HeaderMap switches to it with random keys once
 * it detects a hash-flooding attack (the Red danger state).
 *
 * The functions are inline so the byte source can be a lambda without allocating: HeaderMap hashes both the stored
 * lowercase bytes of a HeaderName and a lookup string mapped through HEADER_CHARS on the fly.
 */

/**
 * SipHash-1-3 of the `length` bytes returned by [byteAt] (each in 0..255), keyed by ([k0], [k1]).
 * The result equals Rust's `SipHasher13::new_with_keys(k0, k1)` after `write` of the same bytes and `finish`.
 */
internal inline fun sipHash13(k0: Long, k1: Long, length: Int, byteAt: (Int) -> Int): Long {
    var v0 = k0 xor 0x736f6d6570736575L
    var v1 = k1 xor 0x646f72616e646f6dL
    var v2 = k0 xor 0x6c7967656e657261L
    var v3 = k1 xor 0x7465646279746573L
    val fullBlocks = length ushr 3
    // One iteration per 8-byte block plus one for the final block (remaining bytes and the length byte).
    for (block in 0..fullBlocks) {
        val base = block shl 3
        var m: Long
        if (block < fullBlocks) {
            m = 0L
            for (j in 0 until 8) m = m or (byteAt(base + j).toLong() shl (8 * j))
        } else {
            m = (length.toLong() and 0xff) shl 56
            for (j in 0 until (length and 7)) m = m or (byteAt(base + j).toLong() shl (8 * j))
        }
        v3 = v3 xor m
        // c = 1 compression round
        v0 += v1; v1 = v1.rotateLeft(13); v1 = v1 xor v0; v0 = v0.rotateLeft(32)
        v2 += v3; v3 = v3.rotateLeft(16); v3 = v3 xor v2
        v0 += v3; v3 = v3.rotateLeft(21); v3 = v3 xor v0
        v2 += v1; v1 = v1.rotateLeft(17); v1 = v1 xor v2; v2 = v2.rotateLeft(32)
        v0 = v0 xor m
    }
    v2 = v2 xor 0xffL
    // d = 3 finalization rounds
    for (r in 0 until 3) {
        v0 += v1; v1 = v1.rotateLeft(13); v1 = v1 xor v0; v0 = v0.rotateLeft(32)
        v2 += v3; v3 = v3.rotateLeft(16); v3 = v3 xor v2
        v0 += v3; v3 = v3.rotateLeft(21); v3 = v3 xor v0
        v2 += v1; v1 = v1.rotateLeft(17); v1 = v1 xor v2; v2 = v2.rotateLeft(32)
    }
    return v0 xor v1 xor v2 xor v3
}

/** SipHash-1-3 of `length` bytes of [src] starting at [offset]. */
internal fun sipHash13(k0: Long, k1: Long, src: ByteArray, offset: Int = 0, length: Int = src.size - offset): Long =
    sipHash13(k0, k1, length) { src[offset + it].toInt() and 0xff }
