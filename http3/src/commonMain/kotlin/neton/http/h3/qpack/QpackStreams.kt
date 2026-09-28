package neton.http.h3.qpack

import neton.http.h3.Code
import neton.http.h3.H3Exception
import neton.io.bytes.Buffer

/**
 * The receiving side of the peer's QPACK encoder stream, for a local decoder that advertised a maximum dynamic table
 * capacity of 0 (SETTINGS_QPACK_MAX_TABLE_CAPACITY, the first version's choice, SPEC §5).
 *
 * The stream must still be opened and read, and its instructions checked (SPEC §5, RFC 9204 §4.3). With a capacity
 * of 0 the only valid instruction is Set Dynamic Table Capacity to 0; everything else is QPACK_ENCODER_STREAM_ERROR:
 * - Set Dynamic Table Capacity above the maximum (§4.3.1): [EncoderStreamError.CapacityExceedsMaximum];
 * - Insert with Name Reference: a static index past the table ([EncoderStreamError.InvalidStaticIndex]) or any
 *   dynamic index (the table is empty, [EncoderStreamError.BadRelativeIndex]); otherwise the insertion exceeds the
 *   capacity, since an entry takes at least 32 bytes (§3.2.2, [EncoderStreamError.InsertionExceedsCapacity]);
 * - Insert with Literal Name: exceeds the capacity;
 * - Duplicate: the table is empty ([EncoderStreamError.BadRelativeIndex]).
 *
 * ⚖️ An insertion is rejected as soon as its name reference (or its name length) has arrived, without waiting for the
 * rest of the instruction: its outcome is already decided, and waiting would let the peer make the stream buffer a
 * string of any declared length. The reference (whose dynamic table is not connected) parses whole instructions.
 *
 * Sans-I/O: [receive] consumes the complete instructions in the buffer and leaves an incomplete one there. Not
 * thread-safe.
 */
class EncoderStreamReceiver {
    /** The maximum capacity advertised (SETTINGS_QPACK_MAX_TABLE_CAPACITY): 0. */
    val maxTableCapacity: Long = 0

    /** The dynamic table capacity the peer's encoder set (§4.3.1): always 0 here. */
    var capacity: Long = 0
        private set

    private val reader = QpackReader()

    /**
     * Processes the instructions in [buf] (`on_encoder_recv`).
     * @throws EncoderStreamException on the first invalid instruction (a connection error).
     */
    fun receive(buf: Buffer) {
        val r = reader
        while (!buf.isEmpty) {
            r.reset(buf.backingArray(), buf.readerIndex(), buf.writerIndex())
            val first = r.peek()
            when (EncoderInstruction.decode(first)) {
                EncoderInstruction.DynamicTableSizeUpdate -> {
                    if (!readInt(r, 5)) return
                    if (r.value.toULong() > maxTableCapacity.toULong()) {
                        fail(EncoderStreamError.CapacityExceedsMaximum(r.value, maxTableCapacity))
                    }
                    capacity = r.value
                }
                EncoderInstruction.InsertWithNameRef -> {
                    if (!readInt(r, 6)) return
                    val index = r.value
                    if (r.flags and 0b01 == 0b01) {
                        if (StaticTable.getOrNull(index) == null) fail(EncoderStreamError.InvalidStaticIndex(index))
                    } else {
                        fail(EncoderStreamError.BadRelativeIndex(index))
                    }
                    fail(EncoderStreamError.InsertionExceedsCapacity(capacity))
                }
                EncoderInstruction.InsertWithoutNameRef -> {
                    // The name length (5-bit prefix below the Huffman flag).
                    if (!readInt(r, 5)) return
                    fail(EncoderStreamError.InsertionExceedsCapacity(capacity))
                }
                EncoderInstruction.Duplicate -> {
                    if (!readInt(r, 5)) return
                    fail(EncoderStreamError.BadRelativeIndex(r.value))
                }
                EncoderInstruction.Unknown -> fail(EncoderStreamError.UnknownPrefix(first))
            }
            buf.skip(r.pos - buf.readerIndex())
        }
    }

    private fun readInt(r: QpackReader, size: Int): Boolean = when (r.readInt(size)) {
        PrefixInt.OK -> true
        PrefixInt.UNEXPECTED_END -> false
        else -> fail(EncoderStreamError.InvalidInteger)
    }

    private fun fail(error: EncoderStreamError): Nothing = throw EncoderStreamException(error)
}

/** Invalid encoder stream instructions; all are QPACK_ENCODER_STREAM_ERROR. */
sealed class EncoderStreamError {
    data class CapacityExceedsMaximum(val capacity: Long, val maximum: Long) : EncoderStreamError()
    data class InsertionExceedsCapacity(val capacity: Long) : EncoderStreamError()
    data class InvalidStaticIndex(val index: Long) : EncoderStreamError()
    data class BadRelativeIndex(val index: Long) : EncoderStreamError()
    data class UnknownPrefix(val prefix: Int) : EncoderStreamError()
    object InvalidInteger : EncoderStreamError() {
        override fun toString() = "InvalidInteger"
    }
}

/** An [EncoderStreamError] thrown: a connection error of type QPACK_ENCODER_STREAM_ERROR. */
class EncoderStreamException(val error: EncoderStreamError) :
    H3Exception(Code.QPACK_ENCODER_STREAM_ERROR, error.toString())

/**
 * The receiving side of the peer's QPACK decoder stream, for a local encoder that never uses the dynamic table
 * (SPEC §5): it never sends a field section with a non-zero Required Insert Count nor inserts anything.
 *
 * Checks (RFC 9204 §4.4), each a QPACK_DECODER_STREAM_ERROR:
 * - Insert Count Increment of 0 (§4.4.3): [DecoderStreamError.ZeroIncrement];
 * - any other Insert Count Increment raises the Known Received Count beyond the 0 insertions sent (§4.4.3):
 *   [DecoderStreamError.IncrementBeyondInsertions];
 * - Section Acknowledgment: no section with a non-zero Required Insert Count was sent (§4.4.1):
 *   [DecoderStreamError.UnexpectedSectionAck].
 *
 * Stream Cancellation is valid and needs nothing. ⚖️ The reference, whose encoder does not connect this stream,
 * tracks acknowledgements in its dynamic table; here there is nothing to acknowledge.
 *
 * Sans-I/O like [EncoderStreamReceiver]. Not thread-safe.
 */
class DecoderStreamReceiver {
    /** Stream Cancellation instructions received. */
    var streamCancellations: Long = 0
        private set

    private val reader = QpackReader()

    /**
     * Processes the instructions in [buf] (`on_decoder_recv`).
     * @throws DecoderStreamException on the first invalid instruction (a connection error).
     */
    fun receive(buf: Buffer) {
        val r = reader
        while (!buf.isEmpty) {
            r.reset(buf.backingArray(), buf.readerIndex(), buf.writerIndex())
            val first = r.peek()
            when (DecoderInstruction.decode(first)) {
                DecoderInstruction.InsertCountIncrement -> {
                    if (!readInt(r, 6)) return
                    if (r.value == 0L) fail(DecoderStreamError.ZeroIncrement)
                    fail(DecoderStreamError.IncrementBeyondInsertions(r.value, 0))
                }
                DecoderInstruction.HeaderAck -> {
                    if (!readInt(r, 7)) return
                    fail(DecoderStreamError.UnexpectedSectionAck(r.value))
                }
                DecoderInstruction.StreamCancel -> {
                    if (!readInt(r, 6)) return
                    streamCancellations++
                }
                DecoderInstruction.Unknown -> fail(DecoderStreamError.UnknownPrefix(first))
            }
            buf.skip(r.pos - buf.readerIndex())
        }
    }

    private fun readInt(r: QpackReader, size: Int): Boolean = when (r.readInt(size)) {
        PrefixInt.OK -> true
        PrefixInt.UNEXPECTED_END -> false
        else -> fail(DecoderStreamError.InvalidInteger)
    }

    private fun fail(error: DecoderStreamError): Nothing = throw DecoderStreamException(error)
}

/** Invalid decoder stream instructions; all are QPACK_DECODER_STREAM_ERROR. */
sealed class DecoderStreamError {
    object ZeroIncrement : DecoderStreamError() {
        override fun toString() = "ZeroIncrement"
    }

    data class IncrementBeyondInsertions(val increment: Long, val insertions: Long) : DecoderStreamError()
    data class UnexpectedSectionAck(val streamId: Long) : DecoderStreamError()
    data class UnknownPrefix(val prefix: Int) : DecoderStreamError()
    object InvalidInteger : DecoderStreamError() {
        override fun toString() = "InvalidInteger"
    }
}

/** A [DecoderStreamError] thrown: a connection error of type QPACK_DECODER_STREAM_ERROR. */
class DecoderStreamException(val error: DecoderStreamError) :
    H3Exception(Code.QPACK_DECODER_STREAM_ERROR, error.toString())
