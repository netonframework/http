package neton.http.h3.qpack

/** The per-entry overhead of RFC 7541 §4.1, used for field section sizes (`ESTIMATED_OVERHEAD_BYTES`). */
const val ESTIMATED_OVERHEAD_BYTES: Int = 32

/**
 * A field line as raw bytes (`HeaderField`): the unit of the static table and of the byte-level encoder and decoder
 * API. Equality compares the bytes. The arrays are not copied; do not mutate them.
 */
class HeaderField(val name: ByteArray, val value: ByteArray) {
    constructor(name: String, value: String) : this(name.encodeToByteArray(), value.encodeToByteArray())

    /** Name length + value length + 32 (`mem_size`, RFC 7541 §4.1: lengths without Huffman coding). */
    fun memSize(): Long = name.size.toLong() + value.size + ESTIMATED_OVERHEAD_BYTES

    /** The same name with another value (`with_value`). */
    fun withValue(value: ByteArray): HeaderField = HeaderField(name, value)

    /** The same name with another value. */
    fun withValue(value: String): HeaderField = withValue(value.encodeToByteArray())

    override fun equals(other: Any?): Boolean =
        other is HeaderField && other.name.contentEquals(name) && other.value.contentEquals(value)

    override fun hashCode(): Int = name.contentHashCode() * 31 + value.contentHashCode()

    /** `"name": "value"` (the reference's `Display`). */
    override fun toString(): String = "\"${name.decodeToString()}\": \"${value.decodeToString()}\""
}
