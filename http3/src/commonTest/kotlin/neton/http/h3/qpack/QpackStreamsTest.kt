package neton.http.h3.qpack

import neton.http.h3.Code
import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun encoded(write: (Buffer) -> Unit): Buffer = Buffer().also(write)

private fun encoderStreamError(buf: Buffer): EncoderStreamException =
    assertFailsWith<EncoderStreamException> { EncoderStreamReceiver().receive(buf) }

private fun decoderStreamError(buf: Buffer): DecoderStreamException =
    assertFailsWith<DecoderStreamException> { DecoderStreamReceiver().receive(buf) }

// Tests of `src/qpack/stream.rs` (all 7), and the encoder / decoder stream tests of `src/qpack/decoder.rs` (4 of 9
// on the encoder stream) and `src/qpack/encoder.rs` (5 on the decoder stream), adapted where the reference's dynamic
// table gives another outcome. Then the capacity-0 rules of SPEC §5 / RFC 9204 §4.3–4.4.
class QpackStreamsTest {
    // ---- stream.rs ----

    @Test
    fun insert_with_name_ref() {
        val instruction = InsertWithNameRef.newStatic(0, "value")
        assertEquals(instruction, InsertWithNameRef.decode(encoded { instruction.encode(it) }))
    }

    @Test
    fun insert_without_name_ref() {
        val instruction = InsertWithoutNameRef("name", "value")
        assertEquals(instruction, InsertWithoutNameRef.decode(encoded { instruction.encode(it) }))
    }

    @Test
    fun insert_duplicate() {
        val instruction = Duplicate(42)
        assertEquals(instruction, Duplicate.decode(encoded { instruction.encode(it) }))
    }

    @Test
    fun dynamic_table_size_update() {
        val instruction = DynamicTableSizeUpdate(42)
        assertEquals(instruction, DynamicTableSizeUpdate.decode(encoded { instruction.encode(it) }))
    }

    @Test
    fun insert_count_increment() {
        val instruction = InsertCountIncrement(42)
        assertEquals(instruction, InsertCountIncrement.decode(encoded { instruction.encode(it) }))
    }

    @Test
    fun header_ack() {
        val instruction = HeaderAck(42)
        assertEquals(instruction, HeaderAck.decode(encoded { instruction.encode(it) }))
    }

    @Test
    fun stream_cancel() {
        val instruction = StreamCancel(42)
        assertEquals(instruction, StreamCancel.decode(encoded { instruction.encode(it) }))
    }

    // ---- decoder.rs: the encoder stream ----

    @Test
    fun test_insert_field_with_wrong_name_index_from_static_table() {
        val e = encoderStreamError(encoded { InsertWithNameRef.newStatic(3000, "").encode(it) })
        assertEquals(EncoderStreamError.InvalidStaticIndex(3000), e.error)
        assertEquals(Code.QPACK_ENCODER_STREAM_ERROR, e.code)
    }

    @Test
    fun test_insert_field_with_wrong_name_index_from_dynamic_table() {
        // The reference also checks that nothing was written to the decoder stream: this receiver writes nothing.
        val e = encoderStreamError(encoded { InsertWithNameRef.newDynamic(3000, "").encode(it) })
        assertEquals(EncoderStreamError.BadRelativeIndex(3000), e.error)
    }

    @Test
    fun enc_recv_buf_too_short() {
        val recv = EncoderStreamReceiver()
        val buf = Buffer()
        recv.receive(buf)
        // ⚖️ `0b1000_0000` is an Insert With Name Reference to dynamic index 0 whose value has not arrived: the
        // reference waits for the value; here the insertion is refused as soon as its name reference is known.
        buf.writeByte(0b1000_0000.toByte())
        assertEquals(EncoderStreamError.BadRelativeIndex(0), assertFailsWith<EncoderStreamException> { recv.receive(buf) }.error)
        // A name reference cut inside its integer is waited for.
        val cut = encoded { InsertWithNameRef.newDynamic(3000, "x").encode(it) }
        val first = Buffer().also { it.writeBytes(cut.peekAll(), 0, 1) }
        EncoderStreamReceiver().receive(first)
        assertEquals(1, first.readableBytes)
    }

    @Test
    fun enc_recv_accepts_truncated_messages() {
        // ⚖️ Adapted: the reference inserts the first complete instruction; with a capacity of 0 any insertion is
        // refused, as soon as the name length is known. Cut inside the name length's integer, the instruction waits
        // (nothing consumed); once the name length is complete, it is refused, the value not being needed.
        val longName = "keyfoobarbaz".repeat(4)
        val bytes = encoded { InsertWithoutNameRef(longName, "value").encode(it) }.readAll()
        assertTrue(bytes[0].toInt() and 0x1f == 0x1f, "the name length takes more than the first byte")
        val recv = EncoderStreamReceiver()
        val part = Buffer().also { it.writeBytes(bytes, 0, 1) }
        recv.receive(part)
        assertEquals(1, part.readableBytes)
        part.writeBytes(bytes, 1, 1)
        val e = assertFailsWith<EncoderStreamException> { recv.receive(part) }
        assertEquals(EncoderStreamError.InsertionExceedsCapacity(0), e.error)
        // The instruction codec itself accepts truncated input: null, nothing consumed.
        val truncated = Buffer().also { it.writeBytes(bytes, 0, bytes.size - 1) }
        assertNull(InsertWithoutNameRef.decode(truncated))
        assertEquals(bytes.size - 1, truncated.readableBytes)
        truncated.writeBytes(bytes, bytes.size - 1, 1)
        assertEquals(InsertWithoutNameRef(longName, "value"), InsertWithoutNameRef.decode(truncated))
        assertTrue(truncated.isEmpty)
    }

    // ---- encoder.rs: the decoder stream ----

    @Test
    fun decoder_block_ack() {
        val buf = encoded { HeaderAck(2).encode(it) }
        assertEquals(HeaderAck(2), HeaderAck.decode(Buffer().also { b -> b.writeBytes(buf.peekAll()) }))
        // ⚖️ The reference acknowledges its tracked block once, then fails with UnknownStreamId; this encoder never
        // sends a section with a non-zero Required Insert Count, so any Section Acknowledgment is an error.
        val e = decoderStreamError(buf)
        assertEquals(DecoderStreamError.UnexpectedSectionAck(2), e.error)
        assertEquals(Code.QPACK_DECODER_STREAM_ERROR, e.code)
    }

    @Test
    fun decoder_stream_cacnceled() {
        val buf = encoded { StreamCancel(2).encode(it) }
        assertEquals(StreamCancel(2), StreamCancel.decode(Buffer().also { b -> b.writeBytes(buf.peekAll()) }))
        val recv = DecoderStreamReceiver()
        recv.receive(buf)
        assertEquals(1L, recv.streamCancellations)
        assertTrue(buf.isEmpty)
    }

    @Test
    fun decoder_accept_truncated() {
        val bytes = encoded { StreamCancel(2321).encode(it) }.readAll()
        // Truncated prefix integer.
        val cut = Buffer().also { it.writeBytes(bytes, 0, 2) }
        assertNull(StreamCancel.decode(cut))
        val recv = DecoderStreamReceiver()
        recv.receive(cut)
        assertEquals(2, cut.readableBytes)
        assertEquals(0L, recv.streamCancellations)
        assertEquals(StreamCancel(2321), StreamCancel.decode(Buffer().also { it.writeBytes(bytes) }))
        cut.writeBytes(bytes, 2, bytes.size - 2)
        recv.receive(cut)
        assertEquals(1L, recv.streamCancellations)
    }

    @Test
    fun decoder_unknown_stream() {
        // The reference: a Section Acknowledgment for a stream with no tracked block is an error. Here every one is.
        assertEquals(DecoderStreamError.UnexpectedSectionAck(4), decoderStreamError(encoded { HeaderAck(4).encode(it) }).error)
    }

    @Test
    fun insert_count() {
        val buf = encoded { InsertCountIncrement(4).encode(it) }
        assertEquals(InsertCountIncrement(4), InsertCountIncrement.decode(Buffer().also { b -> b.writeBytes(buf.peekAll()) }))
        // ⚖️ The reference's encoder accepts it (its table ignores increments past its insertions); nothing was
        // inserted here, so it raises the Known Received Count past the insertions sent (RFC 9204 §4.4.3).
        assertEquals(DecoderStreamError.IncrementBeyondInsertions(4, 0), decoderStreamError(buf).error)
    }

    // ---- Capacity 0 (SPEC §5, the h3spec cases the reference skips) ----

    @Test
    fun setCapacityZeroIsAcceptedAndAboveZeroIsAnError() {
        val recv = EncoderStreamReceiver()
        val buf = encoded { DynamicTableSizeUpdate(0).encode(it); DynamicTableSizeUpdate(0).encode(it) }
        recv.receive(buf)
        assertTrue(buf.isEmpty)
        assertEquals(0L, recv.capacity)
        for (c in listOf(1L, 25L, 4096L, Long.MAX_VALUE)) {
            val e = encoderStreamError(encoded { DynamicTableSizeUpdate(c).encode(it) })
            assertEquals(EncoderStreamError.CapacityExceedsMaximum(c, 0), e.error)
            assertEquals(Code.QPACK_ENCODER_STREAM_ERROR, e.code)
        }
    }

    @Test
    fun insertionExceedingTheZeroCapacityIsAnEncoderStreamError() {
        // The h3spec case: an insertion beyond the advertised capacity (0).
        for (write in listOf<(Buffer) -> Unit>(
            { InsertWithNameRef.newStatic(1, "serial value").encode(it) },
            { InsertWithoutNameRef("key", "value").encode(it) },
            { InsertWithNameRef.newStatic(0, "").encode(it) },
        )) {
            val e = encoderStreamError(encoded(write))
            assertEquals(EncoderStreamError.InsertionExceedsCapacity(0), e.error)
            assertEquals(Code.QPACK_ENCODER_STREAM_ERROR, e.code)
        }
        // Duplicate refers to an entry of the empty table.
        assertEquals(EncoderStreamError.BadRelativeIndex(1), encoderStreamError(encoded { Duplicate(1).encode(it) }).error)
    }

    @Test
    fun insertionAfterValidInstructionsIsStillRefused() {
        val recv = EncoderStreamReceiver()
        val buf = encoded { DynamicTableSizeUpdate(0).encode(it); InsertWithoutNameRef("a", "b").encode(it) }
        assertFailsWith<EncoderStreamException> { recv.receive(buf) }
    }

    @Test
    fun hugeDeclaredStringIsRefusedWithoutBufferingIt() {
        // An Insert With Literal Name declaring a 1 GiB name: refused from its first two bytes.
        val buf = Buffer()
        PrefixInt.encode(5, 0b010, 1L shl 30, buf)
        assertTrue(buf.readableBytes < 8)
        assertEquals(EncoderStreamError.InsertionExceedsCapacity(0), encoderStreamError(buf).error)
    }

    @Test
    fun insertCountIncrementOfZeroIsADecoderStreamError() {
        // The h3spec case: Insert Count Increment of 0.
        val e = decoderStreamError(encoded { InsertCountIncrement(0).encode(it) })
        assertEquals(DecoderStreamError.ZeroIncrement, e.error)
        assertEquals(Code.QPACK_DECODER_STREAM_ERROR, e.code)
    }

    @Test
    fun streamCancellationsInPiecesAreAccepted() {
        val bytes = encoded { b -> for (id in listOf(0L, 4L, 1000L, 1L shl 40)) StreamCancel(id).encode(b) }.readAll()
        val recv = DecoderStreamReceiver()
        val buf = Buffer()
        for (b in bytes) {
            buf.writeByte(b)
            recv.receive(buf)
        }
        assertEquals(4L, recv.streamCancellations)
        assertTrue(buf.isEmpty)
    }

    @Test
    fun integerOverflowOnTheStreams() {
        val tooLong = ByteArray(12) { 0xff.toByte() }
        assertEquals(EncoderStreamError.InvalidInteger, encoderStreamError(Buffer().also { it.writeBytes(tooLong) }).error)
        assertEquals(DecoderStreamError.InvalidInteger, decoderStreamError(Buffer().also { it.writeBytes(tooLong) }).error)
    }
}
