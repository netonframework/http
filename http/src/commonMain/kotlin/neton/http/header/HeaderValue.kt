package neton.http.header

/**
 * Represents an HTTP header field value (`http::header::HeaderValue`).
 *
 * Header values are usually visible ASCII, but HTTP allows opaque bytes (obs-text, 128..255) as well, so a value is a
 * byte sequence: every byte satisfies `b >= 32 && b != 127 || b == '\t'`. [toStr] returns a string only when the value
 * is visible ASCII or tab.
 *
 * ## Ownership
 * A value is a view (`array`, `offset`, `length`) over bytes that are never mutated through it.
 * [fromBytes] and [fromStr] copy the input once; [fromMaybeShared] wraps the caller's array without copying (the
 * equivalent of passing `Bytes` to the reference), so the caller must not mutate that range afterwards; [fromName]
 * shares the name's immutable lowercase bytes.
 *
 * Equality, [hashCode] and ordering use the bytes only (unsigned lexicographic order), never [isSensitive].
 */
class HeaderValue private constructor(
    internal val array: ByteArray,
    internal val offset: Int,
    /** Length of the value in bytes (`len`). */
    val length: Int,
) : Comparable<HeaderValue> {

    /**
     * Whether the value represents sensitive data (passwords, tokens, ...), `set_sensitive` / `is_sensitive`.
     * Caches can avoid storing sensitive values and HPACK/QPACK encoders can choose not to index them; [toString]
     * masks them. Not part of equality or ordering. The flag belongs to this instance; instances are shared by
     * reference, so set it before handing the value out.
     */
    var isSensitive: Boolean = false

    /** Returns true if the value has a length of zero bytes. */
    fun isEmpty(): Boolean = length == 0

    /** Returns the byte at [index]. */
    fun byteAt(index: Int): Byte {
        if (index < 0 || index >= length) throw IndexOutOfBoundsException("index $index, length $length")
        return array[offset + index]
    }

    /** Copies the bytes into [destination] at [destinationOffset] and returns [destination]. */
    fun copyInto(destination: ByteArray, destinationOffset: Int = 0): ByteArray =
        array.copyInto(destination, destinationOffset, offset, offset + length)

    /** Returns a new array holding the bytes (`as_bytes`, copied). */
    fun asBytes(): ByteArray = array.copyOfRange(offset, offset + length)

    /**
     * Returns the value as a string if it only contains visible ASCII characters or tab (`to_str`).
     *
     * @throws ToStrError if the value contains other bytes.
     */
    fun toStr(): String = tryToStr() ?: throw ToStrError()

    /** Like [toStr] but returns null if the value contains bytes other than visible ASCII or tab. */
    fun tryToStr(): String? {
        var bad = false
        for (i in offset until offset + length) bad = bad or !isVisibleAscii(array[i].toInt() and 0xff)
        if (bad) return null
        return array.decodeToString(offset, offset + length)
    }

    /** Byte comparison with the UTF-8 encoding of [other] (the reference's `PartialEq<str>`). */
    fun contentEquals(other: String): Boolean = compareTo(other) == 0

    /** Byte comparison with `length` bytes of [other] from [otherOffset] (the reference's `PartialEq<[u8]>`). */
    fun contentEquals(other: ByteArray, otherOffset: Int = 0, otherLength: Int = other.size - otherOffset): Boolean {
        checkRange(other.size, otherOffset, otherLength)
        return otherLength == length && bytesEqual(array, offset, other, otherOffset, length)
    }

    /** Unsigned lexicographic comparison of the bytes. */
    override fun compareTo(other: HeaderValue): Int =
        compareBytes(array, offset, length, other.array, other.offset, other.length)

    /** Unsigned lexicographic comparison with the UTF-8 encoding of [other] (the reference's `PartialOrd<str>`). */
    operator fun compareTo(other: String): Int {
        // Fast path for ASCII strings, where each char is one UTF-8 byte; no allocation.
        val n = minOf(length, other.length)
        for (i in 0 until n) {
            val c = other[i].code
            if (c >= 0x80) {
                val enc = other.encodeToByteArray()
                return compareBytes(array, offset, length, enc, 0, enc.size)
            }
            val a = array[offset + i].toInt() and 0xff
            if (a != c) return a - c
        }
        for (i in n until other.length) {
            if (other[i].code >= 0x80) {
                val enc = other.encodeToByteArray()
                return compareBytes(array, offset, length, enc, 0, enc.size)
            }
        }
        return length - other.length
    }

    /** Unsigned lexicographic comparison with the bytes of [other] (the reference's `PartialOrd<[u8]>`). */
    operator fun compareTo(other: ByteArray): Int = compareBytes(array, offset, length, other, 0, other.size)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HeaderValue) return false
        return other.length == length && contentEquals(other.array, other.offset, other.length)
    }

    override fun hashCode(): Int {
        var h = 1
        for (i in offset until offset + length) h = 31 * h + array[i]
        return h
    }

    /**
     * The reference's `Debug` form: `Sensitive` for a sensitive value, otherwise the value in double quotes with `"`
     * escaped as `\"` and every byte that is not visible ASCII written as `\x` plus lowercase hex.
     */
    override fun toString(): String {
        if (isSensitive) return "Sensitive"
        val sb = StringBuilder(length + 2)
        sb.append('"')
        for (i in offset until offset + length) {
            val b = array[i].toInt() and 0xff
            when {
                b == '"'.code -> sb.append("\\\"")
                !isVisibleAscii(b) -> sb.append("\\x").append(b.toString(16))
                else -> sb.append(b.toChar())
            }
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {
        /**
         * Converts a constant string to a value (`from_static`). Stricter than [fromStr]: only visible ASCII
         * (32..126) and tab are allowed. Intended for literals: an invalid input is a programming error, reported by
         * throwing (the reference panics).
         *
         * @throws InvalidHeaderValue if the string contains other characters.
         */
        fun fromStatic(src: String): HeaderValue {
            val out = ByteArray(src.length)
            for (i in src.indices) {
                val c = src[i].code
                if (c >= 0x80 || !isVisibleAscii(c)) throw InvalidHeaderValue()
                out[i] = c.toByte()
            }
            return HeaderValue(out, 0, out.size)
        }

        /**
         * Converts a string to a value (`from_str`), validating its UTF-8 bytes with the value byte rule (so
         * non-ASCII characters are accepted as obs-text bytes). Copies once.
         *
         * @throws InvalidHeaderValue if the string contains control characters or DEL.
         */
        fun fromStr(src: String): HeaderValue = tryFromStr(src) ?: throw InvalidHeaderValue()

        /** Like [fromStr] but returns null for an invalid value. */
        fun tryFromStr(src: String): HeaderValue? {
            val bytes = src.encodeToByteArray()
            return tryFromMaybeShared(bytes, 0, bytes.size)
        }

        /** Converts a header name to a value (`from_name`); infallible, and shares the name's bytes. */
        fun fromName(name: HeaderName): HeaderValue = HeaderValue(name.bytes, 0, name.bytes.size)

        /**
         * Converts bytes to a value (`from_bytes`); every byte must satisfy `b >= 32 && b != 127 || b == '\t'`.
         * Copies the range once.
         *
         * @throws InvalidHeaderValue if a byte is not allowed.
         */
        fun fromBytes(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): HeaderValue =
            tryFromBytes(src, offset, length) ?: throw InvalidHeaderValue()

        /** Like [fromBytes] but returns null for an invalid value. */
        fun tryFromBytes(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): HeaderValue? {
            checkRange(src.size, offset, length)
            if (!allValid(src, offset, length)) return null
            return HeaderValue(src.copyOfRange(offset, offset + length), 0, length)
        }

        /**
         * Converts bytes to a value without copying (`from_maybe_shared`): the value keeps a reference to [src], so
         * the caller must not mutate that range afterwards. Validated like [fromBytes].
         *
         * @throws InvalidHeaderValue if a byte is not allowed.
         */
        fun fromMaybeShared(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): HeaderValue =
            tryFromMaybeShared(src, offset, length) ?: throw InvalidHeaderValue()

        /** Like [fromMaybeShared] but returns null for an invalid value. */
        fun tryFromMaybeShared(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): HeaderValue? {
            checkRange(src.size, offset, length)
            if (!allValid(src, offset, length)) return null
            return HeaderValue(src, offset, length)
        }

        /**
         * Wraps bytes without copying and without validating (`from_maybe_shared_unchecked`). The caller guarantees
         * the bytes satisfy the value byte rule and are not mutated afterwards; for already validated input such as
         * a parser's output.
         */
        fun fromMaybeSharedUnchecked(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): HeaderValue {
            checkRange(src.size, offset, length)
            return HeaderValue(src, offset, length)
        }

        /** Decimal representation of [num] (`From<i16>`). */
        fun from(num: Short): HeaderValue = fromLong(num.toLong())

        /** Decimal representation of [num] (`From<i32>`). */
        fun from(num: Int): HeaderValue = fromLong(num.toLong())

        /** Decimal representation of [num] (`From<i64>` / `From<isize>`). */
        fun from(num: Long): HeaderValue = fromLong(num)

        /** Decimal representation of [num] (`From<u16>`). */
        fun from(num: UShort): HeaderValue = fromULong(num.toULong(), false)

        /** Decimal representation of [num] (`From<u32>`). */
        fun from(num: UInt): HeaderValue = fromULong(num.toULong(), false)

        /** Decimal representation of [num] (`From<u64>` / `From<usize>`). */
        fun from(num: ULong): HeaderValue = fromULong(num, false)

        private fun fromLong(num: Long): HeaderValue =
            if (num < 0) fromULong((-(num + 1)).toULong() + 1u, true) else fromULong(num.toULong(), false)

        /** Writes the digits straight into an exactly sized array; no intermediate string. */
        private fun fromULong(magnitude: ULong, negative: Boolean): HeaderValue {
            var digits = 1
            var t = magnitude
            while (t >= 10u) { t /= 10u; digits++ }
            val len = if (negative) digits + 1 else digits
            val out = ByteArray(len)
            if (negative) out[0] = '-'.code.toByte()
            var v = magnitude
            for (i in len - 1 downTo len - digits) {
                out[i] = ('0'.code + (v % 10u).toInt()).toByte()
                v /= 10u
            }
            return HeaderValue(out, 0, len)
        }

        private fun allValid(src: ByteArray, offset: Int, length: Int): Boolean {
            // No early return, so the loop stays branch-light.
            var bad = false
            for (i in offset until offset + length) bad = bad or !isValid(src[i].toInt() and 0xff)
            return !bad
        }
    }
}

/** Visible ASCII or tab: the `to_str` / `from_static` rule. */
@Suppress("NOTHING_TO_INLINE")
private inline fun isVisibleAscii(b: Int): Boolean = b in 32..126 || b == '\t'.code

/** The value byte rule: `b >= 32 && b != 127 || b == '\t'` (obs-text allowed). */
@Suppress("NOTHING_TO_INLINE")
private inline fun isValid(b: Int): Boolean = b >= 32 && b != 127 || b == '\t'.code

private fun compareBytes(a: ByteArray, aOff: Int, aLen: Int, b: ByteArray, bOff: Int, bLen: Int): Int {
    val n = minOf(aLen, bLen)
    for (i in 0 until n) {
        val x = a[aOff + i].toInt() and 0xff
        val y = b[bOff + i].toInt() and 0xff
        if (x != y) return x - y
    }
    return aLen - bLen
}
