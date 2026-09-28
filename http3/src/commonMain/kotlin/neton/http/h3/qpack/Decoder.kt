package neton.http.h3.qpack

import neton.http.h3.Code
import neton.http.h3.H3Exception
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes

/** Default `maxFieldSectionSize`: 64 KiB of decoded field section (SPEC §5, "the three header limits"). */
const val DEFAULT_MAX_FIELD_SECTION_SIZE: Long = 64 * 1024

/** Default `maxFieldCount`: 100 field lines, as the HTTP/1.1 header count (SPEC §5). */
const val DEFAULT_MAX_FIELD_COUNT: Int = 100

/**
 * Receives the field lines of a section as they are decoded. The name and value are ranges of arrays that are valid
 * only during the call (they may be the static table, the frame payload or the decoder's scratch space).
 */
fun interface FieldSink {
    fun onField(name: ByteArray, nameOff: Int, nameLen: Int, value: ByteArray, valueOff: Int, valueLen: Int)
}

/**
 * Decodes encoded field sections (RFC 9204 §4.5) without a dynamic table: the reference's `decode_stateless`, the only
 * decoding the reference connects (SPEC §5, first version: static table and literals, dynamic table capacity 0).
 *
 * Every reference to the dynamic table is QPACK_DECOMPRESSION_FAILED ([DecoderError.MissingRefs]). ⚖️ So is a
 * non-zero Required Insert Count, even in a section without dynamic references (RFC 9204 §4.5.1.1: with a maximum
 * table capacity of 0 the encoded count must be 0); the reference ignores the prefix.
 *
 * ⚖️ Limits, checked field line by field line as decoding proceeds, so decoding stops at the first line past a limit
 * and nothing past it is decoded or handed to the sink (the reference checks only the size, the same way):
 * - [maxFieldSectionSize]: the sum of name + value + 32 over the lines ([DecoderError.HeaderTooLong]);
 * - [maxFieldCount]: the number of lines, checked before a line is decoded ([DecoderError.TooManyFields]).
 *
 * Decoding allocates nothing per field line: names and values reach the sink as ranges of the input, of the static
 * table, or of scratch arrays reused across calls (Huffman strings). Not thread-safe.
 */
class Decoder(
    maxFieldSectionSize: Long = DEFAULT_MAX_FIELD_SECTION_SIZE,
    maxFieldCount: Int = DEFAULT_MAX_FIELD_COUNT,
) {
    /** The largest decoded field section accepted (sum of name + value + 32 per line). */
    var maxFieldSectionSize: Long = maxFieldSectionSize
        set(value) { require(value >= 0); field = value }

    /** The most field lines accepted in one section. */
    var maxFieldCount: Int = maxFieldCount
        set(value) { require(value >= 0); field = value }

    init {
        require(maxFieldSectionSize >= 0 && maxFieldCount >= 0)
    }

    private val reader = QpackReader()

    /** The decoded size of the last section decoded (`Decoded::mem_size`), up to where decoding stopped. */
    var memSize: Long = 0
        private set

    /** The number of field lines of the last section decoded. */
    var fieldCount: Int = 0
        private set

    /**
     * Decodes the section `src[off, off + len)`, passing each field line to [sink] in order.
     * @throws DecoderException on a decoding error or a limit exceeded.
     */
    fun decode(src: ByteArray, off: Int, len: Int, sink: FieldSink) {
        val r = reader
        r.reset(src, off, off + len)
        memSize = 0
        fieldCount = 0

        // Encoded field section prefix (§4.5.1).
        intOrFail(r, 8)
        val encodedInsertCount = r.value
        intOrFail(r, 7)
        if (encodedInsertCount != 0L) fail(DecoderError.MissingRefs(encodedInsertCount))

        while (r.remaining > 0) {
            if (fieldCount >= maxFieldCount) fail(DecoderError.TooManyFields(fieldCount + 1))
            val first = r.peek()
            val nameArr: ByteArray
            val nameOff: Int
            val nameLen: Int
            val valueArr: ByteArray
            val valueOff: Int
            val valueLen: Int
            when (HeaderBlockField.decode(first)) {
                HeaderBlockField.Indexed -> {
                    intOrFail(r, 6)
                    if (r.flags != 0b11) fail(DecoderError.MissingRefs(0))
                    val f = staticField(r.value)
                    nameArr = f.name; nameOff = 0; nameLen = f.name.size
                    valueArr = f.value; valueOff = 0; valueLen = f.value.size
                }
                HeaderBlockField.LiteralWithNameRef -> {
                    intOrFail(r, 4)
                    if (r.flags and 0b0101 != 0b0101) fail(DecoderError.MissingRefs(0))
                    val f = staticField(r.value)
                    nameArr = f.name; nameOff = 0; nameLen = f.name.size
                    stringOrFail(r, 8, 1)
                    valueArr = r.strArray[1]; valueOff = r.strOff[1]; valueLen = r.strLen[1]
                }
                HeaderBlockField.Literal -> {
                    stringOrFail(r, 4, 0)
                    nameArr = r.strArray[0]; nameOff = r.strOff[0]; nameLen = r.strLen[0]
                    stringOrFail(r, 8, 1)
                    valueArr = r.strArray[1]; valueOff = r.strOff[1]; valueLen = r.strLen[1]
                }
                HeaderBlockField.IndexedWithPostBase, HeaderBlockField.LiteralWithPostBaseNameRef ->
                    fail(DecoderError.MissingRefs(0))
                HeaderBlockField.Unknown -> fail(DecoderError.UnknownPrefix(first))
            }
            fieldCount++
            memSize += nameLen.toLong() + valueLen + ESTIMATED_OVERHEAD_BYTES
            // Cancel decoding if the section is too big.
            if (memSize > maxFieldSectionSize) fail(DecoderError.HeaderTooLong(memSize))
            sink.onField(nameArr, nameOff, nameLen, valueArr, valueOff, valueLen)
        }
    }

    /** [decode] of a HEADERS frame payload. */
    fun decode(block: Bytes, sink: FieldSink) {
        val b = Buffer.wrap(block)
        decode(b.backingArray(), b.readerIndex(), b.readableBytes, sink)
    }

    private fun staticField(index: Long): HeaderField =
        StaticTable.getOrNull(index) ?: fail(DecoderError.InvalidStaticIndex(index))

    private fun intOrFail(r: QpackReader, size: Int) {
        when (r.readInt(size)) {
            PrefixInt.OK -> Unit
            PrefixInt.UNEXPECTED_END -> fail(DecoderError.UnexpectedEnd)
            else -> fail(DecoderError.InvalidInteger(PrefixIntError.Overflow))
        }
    }

    private fun stringOrFail(r: QpackReader, size: Int, slot: Int) {
        when (r.readString(size, slot)) {
            PrefixInt.OK -> Unit
            PrefixInt.UNEXPECTED_END -> fail(DecoderError.UnexpectedEnd)
            PrefixInt.OVERFLOW -> fail(DecoderError.InvalidString(PrefixStringError.Integer))
            else -> fail(DecoderError.InvalidString(PrefixStringError.HuffmanDecoding))
        }
    }

    companion object {
        /**
         * The reference's `decode_stateless(buf, max_size)`: decodes the whole of [buf] into a list of fields.
         * @throws DecoderException
         */
        fun decodeStateless(buf: Buffer, maxSize: Long, maxFieldCount: Int = Int.MAX_VALUE): Decoded {
            val fields = ArrayList<HeaderField>()
            val d = Decoder(maxSize, maxFieldCount)
            d.decode(buf.backingArray(), buf.readerIndex(), buf.readableBytes) { n, no, nl, v, vo, vl ->
                fields.add(HeaderField(n.copyOfRange(no, no + nl), v.copyOfRange(vo, vo + vl)))
            }
            buf.skip(buf.readableBytes)
            return Decoded(fields, d.memSize)
        }
    }
}

/** A decoded section (`Decoded`): [fields] and their [memSize]. `dyn_ref` is always false without a dynamic table. */
data class Decoded(val fields: List<HeaderField>, val memSize: Long)

private fun fail(error: DecoderError): Nothing = throw DecoderException(error)

/**
 * Field section decoding errors (`DecoderError`). All are QPACK_DECOMPRESSION_FAILED except the two limits
 * ([isLimit]): those mean the message is too large, not that the peer's QPACK is broken; the connection layer answers
 * them with 431 (server) or a request error (client), as the reference does for `HeaderTooLong`, and [code] is only
 * for when it resets the stream instead.
 */
sealed class DecoderError(val code: Code) {
    data class InvalidInteger(val error: PrefixIntError) : DecoderError(Code.QPACK_DECOMPRESSION_FAILED)
    data class InvalidString(val error: PrefixStringError) : DecoderError(Code.QPACK_DECOMPRESSION_FAILED)
    data class InvalidStaticIndex(val index: Long) : DecoderError(Code.QPACK_DECOMPRESSION_FAILED)
    data class UnknownPrefix(val prefix: Int) : DecoderError(Code.QPACK_DECOMPRESSION_FAILED)

    /**
     * A reference to the dynamic table, which is always empty here: [count] is the encoded Required Insert Count for a
     * non-zero one in the prefix, 0 for a dynamic representation (as in the reference's stateless decoder).
     */
    data class MissingRefs(val count: Long) : DecoderError(Code.QPACK_DECOMPRESSION_FAILED)
    object UnexpectedEnd : DecoderError(Code.QPACK_DECOMPRESSION_FAILED) {
        override fun toString() = "UnexpectedEnd"
    }

    /** The decoded section passed `maxFieldSectionSize`; [size] is the size up to the line that passed it. */
    data class HeaderTooLong(val size: Long) : DecoderError(Code.H3_EXCESSIVE_LOAD)

    /** ⚖️ The section has more than `maxFieldCount` lines; [count] is the count including the refused line. */
    data class TooManyFields(val count: Int) : DecoderError(Code.H3_EXCESSIVE_LOAD)

    /** Whether this is a size or count limit rather than a decoding failure. */
    val isLimit: Boolean get() = this is HeaderTooLong || this is TooManyFields
}

/** A [DecoderError] thrown. */
class DecoderException(val error: DecoderError) : H3Exception(error.code, error.toString())
