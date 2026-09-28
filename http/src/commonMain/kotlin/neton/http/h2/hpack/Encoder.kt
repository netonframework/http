package neton.http.h2.hpack

import neton.http.StatusCode
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import neton.http.Method as HttpMethod

/**
 * Encodes HPACK header blocks (`h2::hpack::Encoder`, `src/hpack/encoder.rs`). One encoder per connection: it owns
 * the dynamic table that the peer's decoder mirrors.
 *
 * ```
 * val encoder = Encoder()               // table size 4096
 * encoder.updateMaxSize(peerTableSize)  // on the peer's SETTINGS_HEADER_TABLE_SIZE
 * encoder.encode(listOf(Header.Method(Method.GET), Header.Path("/"), ...), dst)
 * // or, without building a list or Field objects:
 * encoder.beginBlock(dst); encoder.encodeHeader(Header.Status(StatusCode.OK), dst)
 * encoder.encodeField(HeaderName.CONTENT_TYPE, value, dst); encoder.encodeSameName(value2, dst)
 * ```
 *
 * Encoding choices, as in the reference:
 * - the table never grows beyond 4 KiB, whatever the peer advertises;
 * - queued size updates are emitted at the start of the next block, merged into one or two updates;
 * - every non-empty string is Huffman-coded;
 * - sensitive values ([HeaderValue.isSensitive]) are sent as "never indexed" literals and never inserted;
 * - the values of age, authorization, content-length, etag, if-modified-since, if-none-match, location, cookie,
 *   set-cookie and `:path` are never indexed (only their static names are used);
 * - entries larger than 3/4 of the table size are not indexed.
 *
 * ## Output buffer
 * Output is appended to a [Buffer] (`com.netonstream:io`): each string is written straight into the buffer's backing
 * array (the Huffman length is computed first, so the length prefix is written before the string and nothing is
 * shifted), which keeps encoding allocation-free apart from the table entries it inserts.
 *
 * Not thread-safe: use it from the connection's reactor.
 */
class Encoder(maxSize: Int = DEFAULT_MAX_ALLOWED_SIZE, capacity: Int = 0) {
    internal val table = EncoderTable(minOf(maxSize, DEFAULT_MAX_ALLOWED_SIZE), capacity)

    private var maxAllowedSize = DEFAULT_MAX_ALLOWED_SIZE

    /** The pending size update, if any (`size_update`). */
    internal var sizeUpdate: SizeUpdate? = null
        private set

    // The last named header of the current block (`last_index`), for [encodeSameName].
    private var lastKind = NO_LAST
    private var lastIdx = 0
    private var lastSlot = 0
    private var lastPseudo = 0
    private var lastName: HeaderName? = null

    /** Reusable buffer for the encoded block of one frame (`scratch`); see [takeScratch]. */
    private var scratch: Buffer? = null

    /** A pending dynamic table size update. */
    internal sealed class SizeUpdate {
        data class One(val value: Int) : SizeUpdate()
        data class Two(val min: Int, val max: Int) : SizeUpdate()
    }

    /** Test hook (`set_max_allowed_size`). */
    internal fun setMaxAllowedSize(max: Int) {
        maxAllowedSize = max
        if (table.maxSize > max) updateMaxSize(max)
    }

    /**
     * Takes the reusable buffer for one header block (`take_scratch`). Give it back with [returnScratch] once the
     * block has been copied into the write buffer; if it is kept (the CONTINUATION path still needs it), the next
     * call starts from a fresh buffer.
     */
    internal fun takeScratch(): Buffer {
        val b = scratch ?: Buffer()
        scratch = null
        return b
    }

    /** Returns a fully written block buffer for reuse (`return_scratch`). */
    internal fun returnScratch(buffer: Buffer) {
        scratch = buffer
    }

    /**
     * Queues a max size update (`update_max_size`): the next block starts with a dynamic table size update. The value
     * is capped at 4096. Two updates are queued when the size first shrinks and then grows again, so the peer sees
     * the eviction.
     */
    fun updateMaxSize(value: Int) {
        require(value >= 0) { "size must not be negative" }
        val v = minOf(value, maxAllowedSize)
        when (val su = sizeUpdate) {
            is SizeUpdate.One -> {
                sizeUpdate = if (v > su.value) {
                    if (su.value > table.maxSize) SizeUpdate.One(v) else SizeUpdate.Two(su.value, v)
                } else {
                    SizeUpdate.One(v)
                }
            }
            is SizeUpdate.Two -> {
                sizeUpdate = if (v < su.min) SizeUpdate.One(v) else SizeUpdate.Two(su.min, v)
            }
            null -> {
                // Don't bother writing an update if the value already matches the table.
                if (v != table.maxSize) sizeUpdate = SizeUpdate.One(v)
            }
        }
    }

    /**
     * Encodes a header block into [dst] (`encode`): [beginBlock], then every header in order. A [Header.Value]
     * reuses the name of the preceding named header.
     *
     * @throws IllegalStateException if a [Header.Value] comes first.
     */
    fun encode(headers: Iterable<Header>, dst: Buffer) {
        beginBlock(dst)
        for (h in headers) encodeHeader(h, dst)
    }

    /** Starts a header block: writes the queued table size updates (`encode_size_updates`). */
    fun beginBlock(dst: Buffer) {
        lastKind = NO_LAST
        lastName = null
        when (val su = sizeUpdate) {
            is SizeUpdate.One -> {
                table.resize(su.value)
                encodeInt(su.value, 5, 0x20, dst)
            }
            is SizeUpdate.Two -> {
                table.resize(su.min)
                table.resize(su.max)
                encodeInt(su.min, 5, 0x20, dst)
                encodeInt(su.max, 5, 0x20, dst)
            }
            null -> {}
        }
        sizeUpdate = null
    }

    /** Encodes one header of the block started with [beginBlock]. */
    fun encodeHeader(header: Header, dst: Buffer) {
        when (header) {
            is Header.Field -> encodeNamed(K_FIELD, header.name, header.value, header, dst)
            is Header.Value -> encodeSameName(header.value, dst)
            else -> encodeNamed(header.kind, null, header.valueObj, header, dst)
        }
    }

    /** Encodes a regular field without a [Header] object (one is only created if the field enters the table). */
    fun encodeField(name: HeaderName, value: HeaderValue, dst: Buffer) = encodeNamed(K_FIELD, name, value, null, dst)

    /**
     * Encodes another value for the name of the previous named header of this block (`encode_header_without_name`).
     *
     * @throws IllegalStateException if there is no previous named header in this block.
     */
    fun encodeSameName(value: HeaderValue, dst: Buffer) {
        check(lastKind != NO_LAST) { "encoding header without name, but no previous index to use for name" }
        if (lastKind == EncoderTable.NOT_INDEXED) {
            encodeNotIndexed2(lastPseudo, lastName, value, value.isSensitive, dst)
        } else {
            encodeNotIndexed(table.resolveIdx(lastKind, lastIdx, lastSlot), value, value.isSensitive, dst)
        }
    }

    private fun encodeNamed(kind: Int, name: HeaderName?, value: Any, header: Header?, dst: Buffer) {
        val sensitive = kind == K_FIELD && (value as HeaderValue).isSensitive
        val len = entryLen(kind, name, value)
        table.index(kind, name, value, header, len, sensitive)
        val idxKind = table.resKind
        val idx = table.resIdx
        when (idxKind) {
            EncoderTable.INDEXED -> encodeInt(idx, 7, 0x80, dst)
            EncoderTable.NAME -> encodeNotIndexed(idx, value, sensitive, dst)
            EncoderTable.INSERTED -> {
                check(!sensitive)
                dst.writeByte(0x40)
                encodeName(kind, name, dst)
                encodeValue(value, dst)
            }
            EncoderTable.INSERTED_VALUE -> {
                check(!sensitive)
                encodeInt(idx, 6, 0x40, dst)
                encodeValue(value, dst)
            }
            else -> encodeNotIndexed2(kind, name, value, sensitive, dst)
        }
        lastKind = idxKind
        lastIdx = idx
        lastSlot = table.resSlot
        lastPseudo = kind
        lastName = name
    }

    /** A literal without indexing (or never indexed) with an indexed name. */
    private fun encodeNotIndexed(nameIdx: Int, value: Any, sensitive: Boolean, dst: Buffer) {
        encodeInt(nameIdx, 4, if (sensitive) 0x10 else 0, dst)
        encodeValue(value, dst)
    }

    /** A literal without indexing (or never indexed) with a literal name. */
    private fun encodeNotIndexed2(kind: Int, name: HeaderName?, value: Any, sensitive: Boolean, dst: Buffer) {
        dst.writeByte(if (sensitive) 0x10 else 0)
        encodeName(kind, name, dst)
        encodeValue(value, dst)
    }

    private fun encodeName(kind: Int, name: HeaderName?, dst: Buffer) {
        if (kind == K_FIELD) {
            val n = name!!
            encodeBytes(n.bytes, 0, n.length, dst)
        } else {
            val p = PSEUDO_NAMES[kind]
            encodeBytes(p, 0, p.size, dst)
        }
    }

    private fun encodeValue(value: Any, dst: Buffer) {
        when (value) {
            is HeaderValue -> encodeBytes(value.array, value.offset, value.length, dst)
            is String -> encodeString(value, dst)
            is HttpMethod -> encodeString(value.asStr(), dst)
            is StatusCode -> encodeString(value.asStr(), dst)
            else -> throw IllegalArgumentException("value $value")
        }
    }

    companion object {
        /** The largest table the encoder uses, whatever the peer allows (`DEFAULT_MAX_ALLOWED_SIZE`). */
        const val DEFAULT_MAX_ALLOWED_SIZE: Int = 4 * 1024

        private const val NO_LAST = -1
    }
}

/** A string literal (`encode_str`): Huffman-coded when non-empty, with a 7-bit length prefix. */
private fun encodeBytes(src: ByteArray, off: Int, len: Int, dst: Buffer) {
    if (len == 0) {
        dst.writeByte(0)
        return
    }
    val huffLen = huffmanEncodedLength(src, off, len)
    dst.reserve(MAX_INT_LEN + huffLen)
    val a = dst.backingArray()
    val start = dst.writerIndex()
    var w = putInt(a, start, huffLen, 7, 0x80)
    w = huffmanEncode(src, off, len, a, w)
    dst.commitWrite(w - start)
}

private fun encodeString(s: String, dst: Buffer) {
    for (i in s.indices) {
        if (s[i].code >= 0x80) {
            // Non-ASCII: encode through the UTF-8 bytes (rare; pseudo-header values are ASCII in practice).
            val b = s.encodeToByteArray()
            encodeBytes(b, 0, b.size, dst)
            return
        }
    }
    if (s.isEmpty()) {
        dst.writeByte(0)
        return
    }
    val huffLen = huffmanEncodedLength(s)
    dst.reserve(MAX_INT_LEN + huffLen)
    val a = dst.backingArray()
    val start = dst.writerIndex()
    var w = putInt(a, start, huffLen, 7, 0x80)
    w = huffmanEncode(s, a, w)
    dst.commitWrite(w - start)
}

/** Longest encoding of an Int with any prefix: one prefix byte and five continuation bytes. */
private const val MAX_INT_LEN = 6

/** Encodes an integer with an N-bit prefix (`encode_int`, RFC 7541 §5.1). */
internal fun encodeInt(value: Int, prefixBits: Int, firstByte: Int, dst: Buffer) {
    dst.reserve(MAX_INT_LEN)
    val start = dst.writerIndex()
    val w = putInt(dst.backingArray(), start, value, prefixBits, firstByte)
    dst.commitWrite(w - start)
}

private fun putInt(a: ByteArray, at: Int, value: Int, prefixBits: Int, firstByte: Int): Int {
    val low = (1 shl prefixBits) - 1
    var w = at
    if (value < low) {
        a[w++] = (firstByte or value).toByte()
        return w
    }
    var v = value - low
    a[w++] = (firstByte or low).toByte()
    while (v >= 128) {
        a[w++] = (0x80 or (v and 0x7f)).toByte()
        v = v ushr 7
    }
    a[w++] = v.toByte()
    return w
}
