package neton.http.h2.codec

import neton.http.h2.frame.DEFAULT_MAX_FRAME_SIZE
import neton.http.h2.frame.Data
import neton.http.h2.frame.Frame
import neton.io.bytes.Buffer

/**
 * The HTTP/2 frame codec of one connection (`h2::codec::Codec`, `src/codec/mod.rs`): a [FramedRead] and a
 * [FramedWrite] with the reference's accessors. Sans-I/O: the connection layer moves the bytes between its `IoStream`
 * and [decode] / [writer].
 *
 * ⚖️ The reference's `Codec` wraps the transport (and implements `Stream` / `Sink`); this one holds no I/O.
 *
 * @param maxRecvFrameSize the local SETTINGS_MAX_FRAME_SIZE (`with_max_recv_frame_size`).
 * @param vectoredIo whether the stream supports vectored writes (the reference's `is_write_vectored`), which sets
 * the DATA chain threshold.
 */
class Codec(maxRecvFrameSize: Int = DEFAULT_MAX_FRAME_SIZE, vectoredIo: Boolean = true) {
    val reader: FramedRead = FramedRead(maxRecvFrameSize)
    val writer: FramedWrite = FramedWrite(vectoredIo)

    /** Updates the max received frame size; takes effect from the next frame header (`set_max_recv_frame_size`). */
    fun setMaxRecvFrameSize(value: Int) {
        reader.maxFrameSize = value
    }

    /** The largest frame accepted from the wire (`max_recv_frame_size`). */
    val maxRecvFrameSize: Int get() = reader.maxFrameSize

    /** The largest frame that may be sent to the peer (`max_send_frame_size`). */
    val maxSendFrameSize: Int get() = writer.maxFrameSize

    /** Sets the peer's max frame size (`set_max_send_frame_size`). */
    fun setMaxSendFrameSize(value: Int) {
        writer.maxFrameSize = value
    }

    /** Sets the peer's header table size (`set_send_header_table_size`). */
    fun setSendHeaderTableSize(value: Int) = writer.setHeaderTableSize(value)

    /** Sets the decoder's header table size (`set_recv_header_table_size`). */
    fun setRecvHeaderTableSize(value: Int) = reader.setHeaderTableSize(value)

    /** Sets the max header list size that can be received (`set_max_recv_header_list_size`). */
    fun setMaxRecvHeaderListSize(value: Int) {
        reader.maxHeaderListSize = value
    }

    /** The DATA frame last fully written, once (`take_last_data_frame`). */
    fun takeLastDataFrame(): Data? = writer.takeLastDataFrame()

    /** Whether a frame can be buffered without writing first (`has_send_capacity`). */
    fun hasSendCapacity(): Boolean = writer.hasCapacity()

    /** Buffers a frame (`buffer`); see [FramedWrite.buffer]. */
    fun buffer(frame: Frame): UserError? = writer.buffer(frame)

    /** The next received frame; see [FramedRead.decode]. */
    fun decode(buf: Buffer): Frame? = reader.decode(buf)

    /** The next received frame at end of input; see [FramedRead.decodeEof]. */
    fun decodeEof(buf: Buffer): Frame? = reader.decodeEof(buf)
}
