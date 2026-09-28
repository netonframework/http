package neton.http.h2.hpack

import neton.http.StatusCode
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.Method as HttpMethod

/** Why a [Decoder.decode] call needs more input (`h2::hpack::NeedMore`). */
enum class NeedMore {
    UnexpectedEndOfStream,
    IntegerUnderflow,
    StringUnderflow,
}

/**
 * Errors that can be encountered while decoding an HPACK header block (`h2::hpack::DecoderError`). The reference's
 * nested `NeedMore(..)` variants are flattened into the three `NeedMore*` constants; [needMore] recovers the inner
 * kind. As in the reference, invalid names, values, methods and (literal-named) status codes all report
 * [InvalidUtf8].
 */
enum class DecoderError(
    /** The [NeedMore] kind for the `NeedMore*` constants, null otherwise. */
    val needMore: NeedMore?,
) {
    InvalidRepresentation(null),
    InvalidIntegerPrefix(null),
    InvalidTableIndex(null),
    InvalidHuffmanCode(null),
    InvalidUtf8(null),
    InvalidStatusCode(null),
    InvalidPseudoheader(null),
    InvalidMaxDynamicSize(null),
    IntegerOverflow(null),
    NeedMoreUnexpectedEndOfStream(NeedMore.UnexpectedEndOfStream),
    NeedMoreIntegerUnderflow(NeedMore.IntegerUnderflow),
    NeedMoreStringUnderflow(NeedMore.StringUnderflow),
}

// Internal error codes: -(ordinal + 1), so every helper can return "a non-negative result or an error" in one Int.
private val ERRORS = DecoderError.entries
private fun code(e: DecoderError): Int = -(e.ordinal + 1)
private val E_INVALID_REPRESENTATION = code(DecoderError.InvalidRepresentation)
private val E_INVALID_TABLE_INDEX = code(DecoderError.InvalidTableIndex)
private val E_INVALID_HUFFMAN = code(DecoderError.InvalidHuffmanCode)
private val E_INVALID_UTF8 = code(DecoderError.InvalidUtf8)
private val E_INVALID_STATUS = code(DecoderError.InvalidStatusCode)
private val E_INVALID_PSEUDO = code(DecoderError.InvalidPseudoheader)
private val E_INVALID_MAX_DYNAMIC_SIZE = code(DecoderError.InvalidMaxDynamicSize)
private val E_INTEGER_OVERFLOW = code(DecoderError.IntegerOverflow)
private val E_UNEXPECTED_END = code(DecoderError.NeedMoreUnexpectedEndOfStream)
private val E_INTEGER_UNDERFLOW = code(DecoderError.NeedMoreIntegerUnderflow)
private val E_STRING_UNDERFLOW = code(DecoderError.NeedMoreStringUnderflow)

/**
 * Decodes HPACK header blocks (`h2::hpack::Decoder`, `src/hpack/decoder.rs`). One decoder per connection: it owns
 * the dynamic table that mirrors the peer's encoder.
 *
 * ```
 * val decoder = Decoder()                       // table size 4096
 * decoder.queueSizeUpdate(localHeaderTableSize) // when our SETTINGS_HEADER_TABLE_SIZE is acknowledged
 * val err = decoder.decode(block, 0, block.size) { header -> handle(header); true }
 * if (err != null) ... else check(decoder.consumed == block.size)
 * ```
 *
 * ## Resuming a partial block
 * Like the reference, which removes each fully decoded representation from its input buffer, [decode] reports in
 * [consumed] how many bytes of complete representations it decoded, also when it returns an error. On a `NeedMore*`
 * error the caller keeps the bytes from [consumed] on, appends the next CONTINUATION payload and calls [decode]
 * again.
 *
 * ## Allocation
 * The decoder does not allocate per header beyond the header itself: static-table and dynamic-table hits return
 * shared [Header] instances; a literal allocates its value (one [HeaderValue] and its byte array, or a [String] for a
 * pseudo-header), its name only when it is not a standard name ([HeaderName.fromLowercase] returns the shared
 * constants), and one [Header] object. Integers are decoded without boxing, Huffman strings are decoded through a
 * byte-at-a-time state table into a scratch array owned by the decoder, and the dynamic table is a ring buffer.
 *
 * Not thread-safe: use it from the connection's reactor.
 */
class Decoder(size: Int = DEFAULT_TABLE_SIZE) {
    init {
        require(size >= 0) { "size must not be negative" }
    }

    /** Largest size update queued since the last block (`max_size_update`), or -1 for none. */
    private var maxSizeUpdate = -1

    /** Largest dynamic table size the peer may switch to in the current block (`last_max_update`). */
    private var lastMaxUpdate = size

    private val table = DecoderTable(size)

    /** Scratch for Huffman-decoded strings (the reference's `buffer`). */
    private var scratch = ByteArray(4096)

    /** Bytes of complete representations decoded by the last [decode] call. */
    var consumed: Int = 0
        private set

    // Cursor over the current input.
    private var src: ByteArray = EMPTY
    private var pos = 0
    private var end = 0

    // Result of the last decodeString: bytes are in `strArr[strOff, strOff + strLen)`.
    private var strInScratch = false
    private var strOff = 0
    private var strLen = 0
    private var scratchPos = 0

    /**
     * Queues a potential size update (`queue_size_update`): called when a new SETTINGS_HEADER_TABLE_SIZE of ours is
     * acknowledged. Only the largest value queued before the next block counts as the limit for size updates in it.
     */
    fun queueSizeUpdate(size: Int) {
        require(size >= 0) { "size must not be negative" }
        maxSizeUpdate = if (maxSizeUpdate >= 0) maxOf(maxSizeUpdate, size) else size
    }

    /**
     * Decodes the header block fragment `src[offset, offset + length)`, handing every header to [sink] in order.
     *
     * Returns null when every representation was decoded (or [sink] returned false), otherwise the error. [consumed]
     * is then the number of bytes of fully decoded representations (see the class documentation).
     *
     * Rules, as in the reference: dynamic table size updates are only accepted before the first header of the
     * call and may not exceed the largest size queued with [queueSizeUpdate]; indices must address the static table
     * or the dynamic table; integers take at most five bytes; Huffman strings must be padded with at most seven bits
     * of the EOS prefix.
     */
    fun decode(src: ByteArray, offset: Int, length: Int, sink: HeaderSink): DecoderError? {
        if (offset < 0 || length < 0 || offset > src.size - length) {
            throw IndexOutOfBoundsException("offset=$offset length=$length size=${src.size}")
        }
        this.src = src
        pos = offset
        end = offset + length
        consumed = 0
        try {
            val r = decodeBlock(offset, sink)
            return if (r < 0) ERRORS[-r - 1] else null
        } finally {
            this.src = EMPTY
        }
    }

    /** Convenience overload decoding the whole array. */
    fun decode(src: ByteArray, sink: HeaderSink): DecoderError? = decode(src, 0, src.size, sink)

    /** Current dynamic table size in bytes (the reference's `table.size()`). */
    internal val tableSize: Int get() = table.size

    /** Number of dynamic table entries. */
    internal val tableLen: Int get() = table.count

    private fun decodeBlock(start: Int, sink: HeaderSink): Int {
        var canResize = true
        if (maxSizeUpdate >= 0) {
            lastMaxUpdate = maxSizeUpdate
            maxSizeUpdate = -1
        }
        while (pos < end) {
            // At this point we are always at the beginning of the next representation; its type is given by the
            // first byte.
            val ty = src[pos].toInt() and 0xff
            scratchPos = 0
            val header: Header
            when {
                ty and 0x80 != 0 -> {
                    // Indexed header field.
                    canResize = false
                    val index = decodeInt(7)
                    if (index < 0) return index
                    header = table.get(index) ?: return E_INVALID_TABLE_INDEX
                }
                ty and 0x40 != 0 -> {
                    // Literal with incremental indexing.
                    canResize = false
                    header = decodeLiteral(6) ?: return literalError
                    table.insert(header, literalSize)
                }
                ty and 0xf0 == 0 || ty and 0xf0 == 0x10 -> {
                    // Literal without indexing / never indexed. The reference does not track the never-indexed flag
                    // either (TODO in decoder.rs).
                    canResize = false
                    header = decodeLiteral(4) ?: return literalError
                }
                ty and 0xe0 == 0x20 -> {
                    // Dynamic table size update.
                    if (!canResize) return E_INVALID_MAX_DYNAMIC_SIZE
                    val newSize = decodeInt(5)
                    if (newSize < 0) return newSize
                    if (newSize > lastMaxUpdate) return E_INVALID_MAX_DYNAMIC_SIZE
                    table.setMaxSize(newSize)
                    consumed = pos - start
                    continue
                }
                else -> return E_INVALID_REPRESENTATION
            }
            consumed = pos - start
            if (!sink.onHeader(header)) break
        }
        return 0
    }

    // Out-parameters of decodeLiteral (avoid a result object).
    private var literalError = 0
    private var literalSize = 0

    /** Decodes a literal representation (`decode_literal`); on failure returns null and sets [literalError]. */
    private fun decodeLiteral(prefix: Int): Header? {
        // The table index of the name, or 0 for a literal name.
        val tableIdx = decodeInt(prefix)
        if (tableIdx < 0) { literalError = tableIdx; return null }
        if (tableIdx == 0) {
            var r = decodeString()
            if (r < 0) { literalError = r; return null }
            val nameInScratch = strInScratch
            val nameOff = strOff
            val nameLen = strLen
            r = decodeString()
            if (r < 0) { literalError = r; return null }
            // Resolve arrays only now: decoding the value may have grown the scratch array.
            val nameArr = if (nameInScratch) scratch else src
            val valueArr = if (strInScratch) scratch else src
            return newHeader(nameArr, nameOff, nameLen, valueArr, strOff, strLen)
        }
        val entry = table.get(tableIdx)
        if (entry == null) { literalError = E_INVALID_TABLE_INDEX; return null }
        val r = decodeString()
        if (r < 0) { literalError = r; return null }
        return intoEntry(entry, if (strInScratch) scratch else src, strOff, strLen)
    }

    /** `Header::new`: a literal name and value. */
    private fun newHeader(n: ByteArray, nOff: Int, nLen: Int, v: ByteArray, vOff: Int, vLen: Int): Header? {
        if (nLen == 0) { literalError = E_UNEXPECTED_END; return null }
        if (n[nOff] == ':'.code.toByte()) {
            val kind = pseudoKind(n, nOff, nLen)
            if (kind < 0) { literalError = E_INVALID_PSEUDO; return null }
            literalSize = 32 + nLen + vLen
            return pseudo(kind, v, vOff, vLen, E_INVALID_UTF8)
        }
        // HTTP/2 requires lowercase header names.
        val name = HeaderName.tryFromLowercase(n, nOff, nLen)
        val value = if (name == null) null else HeaderValue.tryFromBytes(v, vOff, vLen)
        if (value == null) { literalError = E_INVALID_UTF8; return null }
        literalSize = 32 + nLen + vLen
        return Header.Field(name!!, value)
    }

    /** `Name::into_entry`: a value for the name of a table entry. */
    private fun intoEntry(entry: Header, v: ByteArray, vOff: Int, vLen: Int): Header? {
        if (entry is Header.Field) {
            val value = HeaderValue.tryFromBytes(v, vOff, vLen)
            if (value == null) { literalError = E_INVALID_UTF8; return null }
            literalSize = 32 + entry.name.length + vLen
            return Header.Field(entry.name, value)
        }
        val kind = entry.kind
        literalSize = 32 + PSEUDO_NAMES[kind].size + vLen
        // The reference reports an invalid status here as InvalidStatusCode (and as InvalidUtf8 for a literal name).
        return pseudo(kind, v, vOff, vLen, E_INVALID_STATUS)
    }

    private fun pseudo(kind: Int, v: ByteArray, vOff: Int, vLen: Int, statusError: Int): Header? {
        when (kind) {
            K_METHOD -> {
                val m = HttpMethod.tryFromBytes(v, vOff, vLen)
                if (m == null) { literalError = E_INVALID_UTF8; return null }
                return Header.Method(m)
            }
            K_STATUS -> {
                val s = StatusCode.tryFromBytes(v, vOff, vLen)
                if (s == null) { literalError = statusError; return null }
                return Header.Status(s)
            }
        }
        if (!isValidUtf8(v, vOff, vLen)) { literalError = E_INVALID_UTF8; return null }
        val s = v.decodeToString(vOff, vOff + vLen)
        return when (kind) {
            K_AUTHORITY -> Header.Authority(s)
            K_SCHEME -> Header.Scheme(s)
            K_PATH -> Header.Path(s)
            else -> Header.Protocol(s)
        }
    }

    /**
     * Decodes a string literal at the cursor (`try_decode_string`). Returns 0 and sets [strInScratch], [strOff],
     * [strLen], or an error code.
     */
    private fun decodeString(): Int {
        if (pos >= end) return E_UNEXPECTED_END
        val huff = src[pos].toInt() and 0x80 != 0
        val len = decodeInt(7)
        if (len < 0) return len
        if (len > end - pos) return E_STRING_UNDERFLOW
        if (huff) {
            // Every code is at least five bits, so the output is shorter than twice the input.
            val need = scratchPos + 2 * len
            if (need > scratch.size) scratch = scratch.copyOf(maxOf(need, scratch.size * 2))
            val n = huffmanDecode(src, pos, len, scratch, scratchPos)
            if (n < 0) return E_INVALID_HUFFMAN
            strInScratch = true
            strOff = scratchPos
            strLen = n
            scratchPos += n
        } else {
            strInScratch = false
            strOff = pos
            strLen = len
        }
        pos += len
        return 0
    }

    /**
     * Decodes an integer with an N-bit prefix at the cursor (`decode_int`, RFC 7541 §5.1). At most five bytes, so
     * the value always fits an Int. Returns the value or an error code.
     */
    private fun decodeInt(prefixSize: Int): Int {
        if (pos >= end) return E_INTEGER_UNDERFLOW
        val mask = (1 shl prefixSize) - 1
        var ret = src[pos++].toInt() and mask
        if (ret < mask) return ret
        // The continuation bytes carry seven bits each, least significant group first.
        var bytes = 1
        var shift = 0
        while (pos < end) {
            val b = src[pos++].toInt() and 0xff
            bytes++
            ret += (b and 0x7f) shl shift
            shift += 7
            if (b and 0x80 == 0) return ret
            if (bytes == MAX_INT_BYTES) return E_INTEGER_OVERFLOW
        }
        return E_INTEGER_UNDERFLOW
    }

    override fun toString(): String = "Decoder(size=${table.size}, maxSize=${table.maxSize}, entries=${table.count})"

    companion object {
        /** The HPACK default table size, SETTINGS_HEADER_TABLE_SIZE's initial value (4096). */
        const val DEFAULT_TABLE_SIZE: Int = 4096

        /** The reference's `MAX_BYTES`: an integer takes at most five bytes, so its value stays below 2^28 + 255. */
        private const val MAX_INT_BYTES = 5

        private val EMPTY = ByteArray(0)

        /** Kind of a pseudo-header name, or -1 for an unknown one (`Header::new`). */
        private fun pseudoKind(n: ByteArray, off: Int, len: Int): Int {
            for (k in K_AUTHORITY..K_STATUS) {
                val p = PSEUDO_NAMES[k]
                if (p.size != len) continue
                var eq = true
                for (i in 1 until len) if (p[i] != n[off + i]) { eq = false; break }
                if (eq) return k
            }
            return -1
        }
    }
}

/**
 * The decoder's dynamic table (`decoder::Table`): a ring buffer of entries, newest first, with RFC 7541 §4.1 size
 * accounting. Entry sizes are stored alongside so eviction never recomputes them.
 */
internal class DecoderTable(maxSize: Int) {
    var maxSize: Int = maxSize
        private set
    var size: Int = 0
        private set
    var count: Int = 0
        private set

    private var entries = arrayOfNulls<Header>(8)
    private var sizes = IntArray(8)

    /** Physical slot of the newest entry. */
    private var head = 0

    /**
     * The entry at HPACK [index] (`Table::get`): 1..61 are static, then the dynamic entries newest first. Null for
     * an invalid index.
     */
    fun get(index: Int): Header? {
        if (index == 0) return null
        if (index <= STATIC_TABLE_LEN) return STATIC_HEADERS[index]
        val i = index - (STATIC_TABLE_LEN + 1)
        if (i >= count) return null
        return entries[(head + i) and (entries.size - 1)]
    }

    /** Inserts [entry] of size [len] as the newest entry, evicting as needed (`Table::insert`). */
    fun insert(entry: Header, len: Int) {
        // Evict until the entry fits, or the table is empty.
        while (count > 0 && size.toLong() + len > maxSize) evictOldest()
        if (size.toLong() + len > maxSize) return // Larger than the whole table: the table is left empty.
        if (count == entries.size) grow()
        head = (head - 1) and (entries.size - 1)
        entries[head] = entry
        sizes[head] = len
        count++
        size += len
    }

    /** `Table::set_max_size`: shrink to fit the new limit. */
    fun setMaxSize(newMax: Int) {
        maxSize = newMax
        while (size > maxSize) evictOldest()
    }

    private fun evictOldest() {
        val slot = (head + count - 1) and (entries.size - 1)
        size -= sizes[slot]
        entries[slot] = null
        count--
    }

    private fun grow() {
        val cap = entries.size
        val newEntries = arrayOfNulls<Header>(cap * 2)
        val newSizes = IntArray(cap * 2)
        for (i in 0 until count) {
            val p = (head + i) and (cap - 1)
            newEntries[i] = entries[p]
            newSizes[i] = sizes[p]
        }
        entries = newEntries
        sizes = newSizes
        head = 0
    }
}
