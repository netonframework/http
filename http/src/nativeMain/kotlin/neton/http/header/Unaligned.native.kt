@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package neton.http.header

import kotlin.native.getIntAt
import kotlin.native.getLongAt

// One unaligned load each (bounds-checked): a byte loop costs a safepoint poll and two bounds checks per byte on K/N.

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun ByteArray.loadLongLe(index: Int): Long = getLongAt(index)

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun ByteArray.loadIntLe(index: Int): Int = getIntAt(index)
