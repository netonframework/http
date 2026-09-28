@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package neton.http.header

import kotlin.native.getIntAt
import kotlin.native.getLongAt

// Little-endian loads of several bytes at once (bounds-checked), for comparisons a word at a time: a byte loop costs a
// safepoint poll and two bounds checks per byte on K/N.

/** The 8 bytes at [index] as a little-endian Long. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun ByteArray.loadLongLe(index: Int): Long = getLongAt(index)

/** The 4 bytes at [index] as a little-endian Int. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun ByteArray.loadIntLe(index: Int): Int = getIntAt(index)

/**
 * How many chunks [chunkAt] splits a run of [len] bytes into: 8-byte words when [len] >= 8 (the last one overlapping
 * the one before when [len] is not a multiple of 8), two overlapping 4-byte words when [len] is 4..7, else single bytes.
 * Every chunk lies inside the run, so no load reaches past it.
 */
internal fun chunkCount(len: Int): Int = if (len >= 8) (len + 7) ushr 3 else if (len >= 4) 2 else len

/** Chunk [k] of `bytes[offset, offset + len)`, zero-extended to a Long (see [chunkCount]). */
@Suppress("NOTHING_TO_INLINE")
internal inline fun chunkAt(bytes: ByteArray, offset: Int, len: Int, k: Int, count: Int): Long = when {
    len >= 8 -> bytes.loadLongLe(offset + if (k == count - 1) len - 8 else k shl 3)
    len >= 4 -> bytes.loadIntLe(offset + if (k == 0) 0 else len - 4).toLong() and 0xffffffffL
    else -> bytes[offset + k].toLong() and 0xff
}
