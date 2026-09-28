@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package neton.http.header

import kotlin.native.getIntAt
import kotlin.native.getLongAt

internal actual fun ByteArray.loadLongLe(index: Int): Long = getLongAt(index)

internal actual fun ByteArray.loadIntLe(index: Int): Int = getIntAt(index)
