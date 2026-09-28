package neton.http.header

/** Maximum length of a header name (`http::header::MAX_HEADER_NAME_LEN`): names are 1..=65535 bytes. */
internal const val MAX_HEADER_NAME_LEN: Int = (1 shl 16) - 1

/**
 * Represents an HTTP header field name (`http::header::HeaderName`).
 *
 * Header names are case-insensitive; a `HeaderName` always holds the lowercase form, so hashing and comparison never
 * fold case. The standard names are shared constants (`HeaderName.CONTENT_TYPE`, ...), and every constructor returns
 * the same constant instance for a standard name in any case, without allocating. A custom name stores its lowercase
 * bytes once, in an array owned by the instance.
 *
 * ## Hash map contract
 * - [hash] (also returned by [hashCode]) is computed once at construction from the lowercase bytes. It is stable for
 *   the process lifetime and is mixed (FNV-1a followed by a murmur3 finalizer), so its low bits are suitable for a
 *   power-of-two table mask.
 * - [equals] is `===` for standard names, otherwise a hash check followed by a byte comparison of the lowercase bytes.
 * - A lookup key that is not yet a `HeaderName` (raw bytes or a string, any case) can be matched without allocating:
 *   [hashOf] returns the hash a `HeaderName` built from that input would have, and [equalsIgnoreCase] compares it with
 *   an existing name. This replaces the reference's borrowed `HdrName` lookup type.
 * - The lowercase bytes are read with [length], [byteAt], [copyInto] or [toByteArray]; [asStr] returns the lowercase
 *   string (cached).
 *
 * Instances are immutable and safe to share between threads.
 */
class HeaderName internal constructor(
    /** Lowercase name bytes; owned by this instance and never mutated. */
    internal val bytes: ByteArray,
    /** Precomputed hash of [bytes]; see the class documentation. */
    val hash: Int,
    /** Index into the standard header table, or -1 for a custom name. */
    internal val standardIndex: Int,
    str: String?,
) {
    // Lazily created for custom names (benign race: the result is always the same immutable string).
    private var str: String? = str

    /** Length of the name in bytes. */
    val length: Int get() = bytes.size

    /** Returns the name as a lowercase string (`as_str`). */
    fun asStr(): String = str ?: bytes.decodeToString().also { str = it }

    /** Returns the lowercase byte at [index]. */
    fun byteAt(index: Int): Byte = bytes[index]

    /** Copies the lowercase bytes into [destination] at [destinationOffset] and returns [destination]. */
    fun copyInto(destination: ByteArray, destinationOffset: Int = 0): ByteArray =
        bytes.copyInto(destination, destinationOffset)

    /** Returns a new array holding the lowercase bytes. */
    fun toByteArray(): ByteArray = bytes.copyOf()

    /**
     * Case-insensitive comparison with a string (the reference's `PartialEq<str> for HeaderName`).
     * A string containing characters that are not valid in a header name never matches. Does not allocate.
     */
    fun equalsIgnoreCase(other: String): Boolean {
        val b = bytes
        if (other.length != b.size) return false
        for (i in b.indices) {
            if (b[i] != HEADER_CHARS[charToByte(other[i].code)]) return false
        }
        return true
    }

    /**
     * Case-insensitive comparison with `length` bytes of [src] starting at [offset]. Bytes that are not valid in a
     * header name never match. Does not allocate.
     */
    fun equalsIgnoreCase(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): Boolean {
        checkRange(src.size, offset, length)
        val b = bytes
        if (length != b.size) return false
        for (i in b.indices) {
            if (b[i] != HEADER_CHARS[src[offset + i].toInt() and 0xff]) return false
        }
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HeaderName) return false
        // Standard names are unique instances, so two distinct standard instances are never equal.
        if (standardIndex >= 0 || other.standardIndex >= 0) return false
        return hash == other.hash && bytes.contentEquals(other.bytes)
    }

    /** Returns [hash]. */
    override fun hashCode(): Int = hash

    /** Returns the lowercase name (the reference's `Display`). */
    override fun toString(): String = asStr()

    /**
     * Constructors and the standard header constants (inherited from [StandardHeaderNames], so they read as
     * `HeaderName.CONTENT_TYPE`).
     */
    companion object : StandardHeaderNames() {
        /**
         * Converts bytes to a header name, folding uppercase to lowercase (`from_bytes`).
         * Only tchar bytes are allowed and the length must be 1..=65535.
         * A standard name is returned as its shared constant without allocating.
         *
         * @throws InvalidHeaderName if the input is not a valid header name.
         */
        fun fromBytes(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): HeaderName =
            tryFromBytes(src, offset, length) ?: throw InvalidHeaderName()

        /** Like [fromBytes] but returns null for an invalid name. */
        fun tryFromBytes(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): HeaderName? {
            checkRange(src.size, offset, length)
            return parse(length, HEADER_CHARS) { src[offset + it].toInt() and 0xff }
        }

        /**
         * Converts bytes that must already be lowercase to a header name (`from_lowercase`); used when decoding
         * HTTP/2 and HTTP/3, which require lowercase names. Uppercase letters are rejected.
         *
         * @throws InvalidHeaderName if the input is not a valid lowercase header name.
         */
        fun fromLowercase(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): HeaderName =
            tryFromLowercase(src, offset, length) ?: throw InvalidHeaderName()

        /** Like [fromLowercase] but returns null for an invalid name. */
        fun tryFromLowercase(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): HeaderName? {
            checkRange(src.size, offset, length)
            return parse(length, HEADER_CHARS_H2) { src[offset + it].toInt() and 0xff }
        }

        /**
         * Parses a string as a header name, folding uppercase to lowercase (`FromStr`, same rules as [fromBytes]).
         *
         * @throws InvalidHeaderName if the input is not a valid header name.
         */
        fun fromStr(src: String): HeaderName = tryFromStr(src) ?: throw InvalidHeaderName()

        /** Like [fromStr] but returns null for an invalid name. */
        fun tryFromStr(src: String): HeaderName? = parse(src.length, HEADER_CHARS) { charToByte(src[it].code) }

        /**
         * Converts a constant string to a header name (`from_static`). The string must be lowercase
         * (lowercase letters, digits and token symbols). Intended for literals: an invalid input is a programming
         * error, reported by throwing (the reference panics).
         *
         * @throws InvalidHeaderName if the input is not a valid lowercase header name.
         */
        fun fromStatic(src: String): HeaderName =
            parse(src.length, HEADER_CHARS_H2) { charToByte(src[it].code) } ?: throw InvalidHeaderName()

        /**
         * Returns the [hash] that a header name parsed from these bytes (any case) would have, without allocating.
         * For hash map lookups by raw bytes, together with [equalsIgnoreCase]. The result for invalid input is
         * unspecified (such input never equals a valid name).
         */
        fun hashOf(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): Int {
            checkRange(src.size, offset, length)
            var h = HASH_SEED
            for (i in 0 until length) h = hashStep(h, HEADER_CHARS[src[offset + i].toInt() and 0xff].toInt())
            return hashFinish(h)
        }

        /** String form of [hashOf]. */
        fun hashOf(src: String): Int {
            var h = HASH_SEED
            for (i in src.indices) h = hashStep(h, HEADER_CHARS[charToByte(src[i].code)].toInt())
            return hashFinish(h)
        }

        /**
         * Validates and maps `len` input bytes through [table] (a zero entry means invalid), then returns the shared
         * standard constant or a new custom name. Allocates only for a custom name (its lowercase array).
         */
        private inline fun parse(len: Int, table: ByteArray, byteAt: (Int) -> Int): HeaderName? {
            if (len == 0 || len > MAX_HEADER_NAME_LEN) return null
            // Pass 1: validate and hash the mapped bytes. Avoid an early return so the loop stays branch-light.
            var bad = 0
            var h = HASH_SEED
            for (i in 0 until len) {
                val c = table[byteAt(i)].toInt()
                bad = bad or ((c - 1) ushr 31) // 1 when c == 0
                h = hashStep(h, c)
            }
            if (bad != 0) return null
            val hash = hashFinish(h)
            // Pass 2: standard lookup by hash, confirmed by comparing mapped bytes.
            if (len <= standardMaxLength) {
                var slot = hash and STANDARD_SLOT_MASK
                while (true) {
                    val idx = standardSlots[slot].toInt() - 1
                    if (idx < 0) break
                    val candidate = standardAll[idx]
                    if (candidate.hash == hash && candidate.bytes.size == len) {
                        val cb = candidate.bytes
                        var same = true
                        for (i in 0 until len) {
                            if (cb[i] != table[byteAt(i)]) { same = false; break }
                        }
                        if (same) return candidate
                    }
                    slot = (slot + 1) and STANDARD_SLOT_MASK
                }
            }
            // Pass 3: custom name, store the mapped (lowercase) bytes once.
            val out = ByteArray(len)
            for (i in 0 until len) out[i] = table[byteAt(i)]
            return HeaderName(out, hash, -1, null)
        }
    }
}

// ===== character tables =====

/**
 * Valid header name characters (`HEADER_CHARS`): every tchar maps to itself, uppercase letters map to lowercase,
 * everything else (including all bytes >= 128) maps to 0.
 */
internal val HEADER_CHARS: ByteArray = buildHeaderChars(foldUppercase = true, allowQuote = false)

/**
 * Valid header name characters for HTTP/2 and HTTP/3 (`HEADER_CHARS_H2`): like [HEADER_CHARS] but uppercase letters
 * map to 0. As in the reference table, `"` is also accepted here.
 */
internal val HEADER_CHARS_H2: ByteArray = buildHeaderChars(foldUppercase = false, allowQuote = true)

private fun buildHeaderChars(foldUppercase: Boolean, allowQuote: Boolean): ByteArray {
    val t = ByteArray(256)
    for (c in "!#$%&'*+-.^_`|~") t[c.code] = c.code.toByte()
    for (c in '0'..'9') t[c.code] = c.code.toByte()
    for (c in 'a'..'z') t[c.code] = c.code.toByte()
    if (foldUppercase) for (c in 'A'..'Z') t[c.code] = (c.code + 32).toByte()
    if (allowQuote) t['"'.code] = '"'.code.toByte()
    return t
}

/** Maps a UTF-16 code unit to a table index; anything non-ASCII maps to 128, which every table rejects. */
internal fun charToByte(code: Int): Int = if (code < 128) code else 128

internal fun checkRange(size: Int, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > size - length) {
        throw IndexOutOfBoundsException("offset $offset, length $length, size $size")
    }
}

// ===== hashing =====

internal const val HASH_SEED: Int = -0x7ee3623b // FNV-1a 32-bit offset basis 0x811c9dc5

@Suppress("NOTHING_TO_INLINE")
internal inline fun hashStep(h: Int, b: Int): Int = (h xor b) * 16777619

/** murmur3 fmix32, so that the low bits depend on every input byte. */
internal fun hashFinish(h0: Int): Int {
    var h = h0
    h = h xor (h ushr 16)
    h *= -0x7a143595 // 0x85ebca6b
    h = h xor (h ushr 13)
    h *= -0x3d4d51cb // 0xc2b2ae35
    h = h xor (h ushr 16)
    return h
}

/** Hash of an already lowercase ASCII name (used to build the standard constants). */
internal fun hashLowercase(name: String): Int {
    var h = HASH_SEED
    for (c in name) h = hashStep(h, c.code)
    return hashFinish(h)
}
