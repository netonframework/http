package neton.http.header

// Little-endian loads built from bytes (bytecode 1.8 has no VarHandle; HotSpot's C2 merges such byte loads).

internal actual fun ByteArray.loadLongLe(index: Int): Long =
    (this[index].toLong() and 0xff) or
        ((this[index + 1].toLong() and 0xff) shl 8) or
        ((this[index + 2].toLong() and 0xff) shl 16) or
        ((this[index + 3].toLong() and 0xff) shl 24) or
        ((this[index + 4].toLong() and 0xff) shl 32) or
        ((this[index + 5].toLong() and 0xff) shl 40) or
        ((this[index + 6].toLong() and 0xff) shl 48) or
        ((this[index + 7].toLong() and 0xff) shl 56)

internal actual fun ByteArray.loadIntLe(index: Int): Int =
    (this[index].toInt() and 0xff) or
        ((this[index + 1].toInt() and 0xff) shl 8) or
        ((this[index + 2].toInt() and 0xff) shl 16) or
        ((this[index + 3].toInt() and 0xff) shl 24)
