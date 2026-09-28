package neton.http.h2.frame

import neton.io.bytes.Buffer

/** Frame types (`h2::frame::Kind`); every type byte above 9 is [Unknown]. The ordinal is the type byte. */
enum class Kind {
    Data,
    Headers,
    Priority,
    Reset,
    Settings,
    PushPromise,
    Ping,
    GoAway,
    WindowUpdate,
    Continuation,
    Unknown;

    companion object {
        private val BY_BYTE = entries.toTypedArray()

        /** The kind of type byte [byte] (0..255), `Kind::new`. */
        fun of(byte: Int): Kind = if (byte < 10) BY_BYTE[byte] else Unknown
    }
}

/**
 * A frame header without its length (`h2::frame::Head`, `src/frame/head.rs`): the kind, the flag byte and the
 * stream ID.
 *
 * ⚖️ The reference's `Head` is a plain struct on the stack; here it is an inline value packing the three fields into
 * one Long, so parsing and encoding frame heads allocate nothing.
 */
value class Head private constructor(private val bits: Long) {
    constructor(kind: Kind, flag: Int, streamId: StreamId) :
        this((kind.ordinal.toLong() shl 40) or ((flag.toLong() and 0xff) shl 32) or streamId.value.toLong())

    val kind: Kind get() = Kind.of((bits ushr 40).toInt())

    val flag: Int get() = (bits ushr 32).toInt() and 0xff

    val streamId: StreamId get() = StreamId(bits.toInt())

    /** The encoded size of a frame header (`encode_len`). */
    fun encodeLen(): Int = HEADER_LEN

    /** Appends the 9-byte frame header for a payload of [payloadLen] bytes (`Head::encode`). */
    fun encode(payloadLen: Int, dst: Buffer) {
        dst.reserve(HEADER_LEN)
        val a = dst.backingArray()
        val w = dst.writerIndex()
        a[w] = (payloadLen ushr 16).toByte()
        a[w + 1] = (payloadLen ushr 8).toByte()
        a[w + 2] = payloadLen.toByte()
        a[w + 3] = kind.ordinal.toByte()
        a[w + 4] = flag.toByte()
        val id = streamId.value
        a[w + 5] = (id ushr 24).toByte()
        a[w + 6] = (id ushr 16).toByte()
        a[w + 7] = (id ushr 8).toByte()
        a[w + 8] = id.toByte()
        dst.commitWrite(HEADER_LEN)
    }

    override fun toString(): String = "Head(kind=$kind, flag=0x${flag.toString(16)}, streamId=${streamId.value})"

    companion object {
        /**
         * Parses the frame header at `header[offset, offset + 9)` (`Head::parse`); the 3-byte length is not part of
         * the result (read it with [payloadLength]). The stream ID's reserved bit is ignored.
         */
        fun parse(header: ByteArray, offset: Int = 0): Head =
            Head(Kind.of(header[offset + 3].toInt() and 0xff), header[offset + 4].toInt(), StreamId.parse(header, offset + 5))

        /** The 24-bit payload length at the start of a frame header. */
        fun payloadLength(header: ByteArray, offset: Int = 0): Int =
            ((header[offset].toInt() and 0xff) shl 16) or ((header[offset + 1].toInt() and 0xff) shl 8) or
                (header[offset + 2].toInt() and 0xff)
    }
}
