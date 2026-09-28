package neton.http.h2.frame

/**
 * A stream identifier (`h2::frame::StreamId`, `src/frame/stream_id.rs`; RFC 9113 §5.1.1): an unsigned 31-bit
 * integer. Streams initiated by a client use odd identifiers, those initiated by a server even ones; stream 0 is the
 * connection itself.
 *
 * An inline value: passing one around does not allocate (unless it is made nullable or stored as `Any`).
 */
value class StreamId(val value: Int) : Comparable<StreamId> {
    init {
        // `From<u32>`: "invalid stream ID -- MSB is set".
        require(value >= 0) { "invalid stream ID -- MSB is set" }
    }

    /** Whether this stream was initiated by the client (odd, non-zero). */
    val isClientInitiated: Boolean get() = value != 0 && value % 2 == 1

    /** Whether this stream was initiated by the server (even, non-zero). */
    val isServerInitiated: Boolean get() = value != 0 && value % 2 == 0

    /** Whether this is stream 0. */
    val isZero: Boolean get() = value == 0

    /**
     * The next stream ID initiated by the same peer (`next_id`).
     *
     * ⚖️ Throws where the reference returns `Err(StreamIdOverflow)` (a nullable result would box the value).
     * @throws StreamIdOverflow if it would exceed [MAX].
     */
    fun nextId(): StreamId {
        if (value > MAX.value - 2) throw StreamIdOverflow()
        return StreamId(value + 2)
    }

    override fun compareTo(other: StreamId): Int = value.compareTo(other.value)

    override fun toString(): String = "StreamId($value)"

    companion object {
        /** Stream 0. */
        val ZERO: StreamId = StreamId(0)

        /** The largest stream ID, 2^31 - 1. */
        val MAX: StreamId = StreamId(Int.MAX_VALUE)

        /** Mask of the reserved most significant bit. */
        internal const val MASK: Int = 1 shl 31

        /**
         * Parses the 4 big-endian bytes at [offset] (`StreamId::parse`). The reserved most significant bit is cleared
         * (it MUST be ignored when received); read it with [parseFlag].
         */
        fun parse(buf: ByteArray, offset: Int): StreamId = StreamId(unpackOctets4(buf, offset) and MASK.inv())

        /** The reserved bit of the 4 bytes at [offset] (the `bool` of `StreamId::parse`). */
        fun parseFlag(buf: ByteArray, offset: Int): Boolean = unpackOctets4(buf, offset) and MASK != 0
    }
}

/** The stream ID space is exhausted (`StreamIdOverflow`); a new connection is needed. */
class StreamIdOverflow : Exception("stream ID overflowed")
