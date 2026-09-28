package neton.http.h3.qpack

import neton.io.bytes.Buffer

/**
 * Encodes field sections without a dynamic table (RFC 9204 §4.5): the reference's `encode_stateless`, the only
 * encoding the reference connects (SPEC §5, first version). A field matching a static entry is an indexed line; a
 * field whose name is in the static table is a literal with a static name reference; any other a literal with a
 * literal name. Strings are always Huffman-encoded, as in the reference. The prefix is always zero (no dynamic
 * references), and nothing is ever written to the encoder stream.
 *
 * The typed encoding of HTTP messages (pseudo-headers and a `HeaderMap`) is [neton.http.h3.proto.Header.encode],
 * built on the representation writers here. Not thread-safe (scratch space is reused).
 */
class Encoder {
    private var nameScratch = ByteArray(64)
    private var valueScratch = ByteArray(256)

    /** Writes the section prefix of a section without dynamic references (two zero bytes). */
    fun beginSection(block: Buffer) {
        HeaderPrefix.ZERO.encode(block)
    }

    /** An indexed field line for static entry [index] (§4.5.2). */
    fun indexedStatic(index: Int, block: Buffer) {
        PrefixInt.encode(6, 0b11, index.toLong(), block)
    }

    /** A literal field line with the name of static entry [index] (§4.5.4) and `value[off, off + len)`. */
    fun literalWithStaticName(index: Int, value: ByteArray, off: Int, len: Int, block: Buffer) {
        PrefixInt.encode(4, 0b0101, index.toLong(), block)
        PrefixString.encode(8, 0, value, off, len, block)
    }

    /** A literal field line with a literal name (§4.5.6). */
    fun literal(name: ByteArray, nameOff: Int, nameLen: Int, value: ByteArray, valueOff: Int, valueLen: Int, block: Buffer) {
        PrefixString.encode(4, 0b0010, name, nameOff, nameLen, block)
        PrefixString.encode(8, 0, value, valueOff, valueLen, block)
    }

    /** One field line of raw [name] and [value] (the reference's per-field choice in `encode_stateless`). */
    fun field(name: ByteArray, value: ByteArray, block: Buffer) {
        val index = StaticTable.find(name, value)
        if (index != null) {
            indexedStatic(index, block)
            return
        }
        val nameIndex = StaticTable.findName(name)
        if (nameIndex != null) {
            literalWithStaticName(nameIndex, value, 0, value.size, block)
        } else {
            literal(name, 0, name.size, value, 0, value.size, block)
        }
    }

    /** A scratch array of at least [n] bytes for names (reused). */
    internal fun nameScratch(n: Int): ByteArray {
        if (nameScratch.size < n) nameScratch = ByteArray(maxOf(n, 2 * nameScratch.size))
        return nameScratch
    }

    /** A scratch array of at least [n] bytes for values (reused). */
    internal fun valueScratch(n: Int): ByteArray {
        if (valueScratch.size < n) valueScratch = ByteArray(maxOf(n, 2 * valueScratch.size))
        return valueScratch
    }

    /**
     * Encodes [fields] as a whole section into [block] (`encode_stateless`) and returns its decoded size (sum of
     * name + value + 32), for the caller to compare with the peer's SETTINGS_MAX_FIELD_SECTION_SIZE.
     */
    fun encodeStateless(block: Buffer, fields: Iterable<HeaderField>): Long {
        beginSection(block)
        var size = 0L
        for (f in fields) {
            field(f.name, f.value, block)
            size += f.memSize()
        }
        return size
    }
}
