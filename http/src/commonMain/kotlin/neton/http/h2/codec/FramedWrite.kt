package neton.http.h2.codec

import neton.http.h2.frame.Continuation
import neton.http.h2.frame.DEFAULT_MAX_FRAME_SIZE
import neton.http.h2.frame.Data
import neton.http.h2.frame.Frame
import neton.http.h2.frame.GoAway
import neton.http.h2.frame.HEADER_LEN
import neton.http.h2.frame.Headers
import neton.http.h2.frame.MAX_MAX_FRAME_SIZE
import neton.http.h2.frame.Ping
import neton.http.h2.frame.Priority
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.Reset
import neton.http.h2.frame.Settings
import neton.http.h2.frame.WindowUpdate
import neton.http.h2.hpack.Encoder
import neton.http.h2.proto.IoErrorKind
import neton.http.h2.proto.ProtoError
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes

/**
 * Encodes HTTP/2 frames for writing (`h2::codec::FramedWrite`, `src/codec/framed_write.rs`). Sans-I/O: frames go
 * in through [buffer], bytes come out of [writeBuffer] and [queuedPayload]; the connection writes them to its
 * `IoStream` and drives the loop the reference's `flush` runs:
 *
 * ```
 * while (true) {
 *     while (!fw.isEmpty) {
 *         // write fw.writeBuffer, then fw.queuedPayload (one writev), and report what was written:
 *         fw.advance(n)
 *     }
 *     if (!fw.unsetFrame()) break   // true: a CONTINUATION was encoded and must be written too
 * }
 * ```
 *
 * - One reusable write buffer, initially 16 KiB. A frame may be buffered only when [hasCapacity].
 * - A DATA payload of at least the chain threshold (256 bytes when the stream supports vectored writes, else 1024)
 *   is not copied: it is queued behind the buffer and written with it ([queuedPayload]). When fewer than threshold
 *   bytes are buffered, the first bytes of the payload are copied in so that tiny writes are avoided, as in the
 *   reference. Smaller payloads are copied into the buffer.
 * - HEADERS and PUSH_PROMISE blocks larger than the peer's max frame size are split into CONTINUATION frames, one
 *   frame at a time.
 *
 * ⚖️ Sans-I/O: the reference's `FramedWrite` owns the transport and flushes it itself (`poll_write_buf`, zero-length
 * write = `WriteZero`); here the connection writes and reports the count through [advance], which raises the same
 * `WriteZero`. [buffer] returns the `UserError` (or null) instead of a `Result`.
 *
 * Not thread-safe: use it from the connection's reactor.
 */
class FramedWrite(vectoredIo: Boolean = true) {
    private val hpack = Encoder()

    /** The encoded bytes to write next; [advance] consumes them. */
    val writeBuffer: Buffer = Buffer(DEFAULT_BUFFER_CAPACITY)

    // `next`: a chained DATA frame (with the part of its payload not yet copied or written), or the rest of a header
    // block.
    private var nextData: Data? = null
    private var queued: Bytes? = null
    private var nextContinuation: Continuation? = null

    private var lastDataFrame: Data? = null

    /** The peer's max frame size (`set_max_frame_size`), at most 16,777,215. */
    var maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE
        set(value) {
            require(value <= MAX_MAX_FRAME_SIZE) { "max frame size too large: $value" }
            field = value
        }

    /** DATA payloads at least this big are chained instead of copied. */
    val chainThreshold: Int = if (vectoredIo) CHAIN_THRESHOLD else CHAIN_THRESHOLD_WITHOUT_VECTORED_IO

    /** Room the buffer must have to accept a frame. */
    private val minBufferCapacity = chainThreshold + HEADER_LEN

    /** Sets the peer's SETTINGS_HEADER_TABLE_SIZE for the HPACK encoder (`set_header_table_size`). */
    fun setHeaderTableSize(value: Int) {
        hpack.updateMaxSize(value)
    }

    /**
     * Whether a frame can be buffered without writing first (`has_capacity`): nothing is queued and the buffer has
     * room for a frame header plus the chain threshold.
     */
    fun hasCapacity(): Boolean =
        nextData == null && nextContinuation == null &&
            writeBuffer.capacity - writeBuffer.writerIndex() >= minBufferCapacity

    /**
     * Encodes [frame] (`buffer`). Returns null, or [UserError.PayloadTooBig] for a DATA payload larger than the max
     * frame size. Requires [hasCapacity].
     * @throws NotImplementedError for [Priority]: sending PRIORITY is not implemented in the reference either.
     */
    fun buffer(frame: Frame): UserError? {
        check(hasCapacity()) { "buffer() without capacity" }
        when (frame) {
            is Data -> {
                val payload = frame.payload
                val len = payload.size
                if (len > maxFrameSize) return UserError.PayloadTooBig
                if (len >= chainThreshold) {
                    frame.head().encode(len, writeBuffer)
                    // ⚖️ The reference compares the buffer's whole length here and its unwritten part below; they
                    // differ only after a partial write, and the unwritten part is what matters.
                    var extra = 0
                    if (writeBuffer.readableBytes < chainThreshold) {
                        extra = chainThreshold - writeBuffer.readableBytes
                        copyPrefix(payload, extra)
                    }
                    nextData = frame
                    queued = if (extra == 0) payload else payload.slice(extra)
                } else {
                    frame.encodeChunk(writeBuffer)
                    lastDataFrame = frame
                }
            }
            is Headers -> nextContinuation = frame.encode(hpack, writeBuffer, maxFrameSize + HEADER_LEN)
            is PushPromise -> nextContinuation = frame.encode(hpack, writeBuffer, maxFrameSize + HEADER_LEN)
            is Settings -> frame.encode(writeBuffer)
            is GoAway -> frame.encode(writeBuffer)
            is Ping -> frame.encode(writeBuffer)
            is WindowUpdate -> frame.encode(writeBuffer)
            is Reset -> frame.encode(writeBuffer)
            // ⛔ `unimplemented!()` in the reference (RFC 9113 deprecates the priority scheme).
            is Priority -> throw NotImplementedError("sending PRIORITY frames is not implemented")
        }
        return null
    }

    /** Copies the first [n] payload bytes into the buffer (the reference's `put(payload.take(n))`). */
    private fun copyPrefix(payload: Bytes, n: Int) {
        writeBuffer.reserve(n)
        val a = writeBuffer.backingArray()
        val w = writeBuffer.writerIndex()
        for (i in 0 until n) a[w + i] = payload[i]
        writeBuffer.commitWrite(n)
    }

    /**
     * The DATA payload to write right after [writeBuffer] (the part not already written or copied into it), or null.
     * The slice shares the frame's bytes.
     */
    val queuedPayload: Bytes? get() = queued

    /** Whether everything encoded so far has been written (`is_empty`). */
    val isEmpty: Boolean
        get() {
            val q = queued
            return if (q != null) q.isEmpty else writeBuffer.isEmpty
        }

    /**
     * Records that [n] bytes were written: first from [writeBuffer], then from [queuedPayload].
     * @throws ProtoError [ProtoError.Io] with [IoErrorKind.WriteZero] when [n] is 0 although bytes remain: no progress
     * is possible and retrying would busy-loop.
     */
    fun advance(n: Int) {
        require(n >= 0) { "negative count" }
        if (n == 0 && !isEmpty) throw ProtoError.Io(IoErrorKind.WriteZero)
        val fromBuffer = minOf(n, writeBuffer.readableBytes)
        writeBuffer.consume(fromBuffer)
        val rest = n - fromBuffer
        if (rest > 0) {
            val q = checkNotNull(queued) { "advanced past the buffered bytes" }
            require(rest <= q.size) { "advanced past the queued payload" }
            queued = if (rest == q.size) Bytes.EMPTY else q.slice(rest)
        }
    }

    /**
     * Once [isEmpty]: resets the buffer and moves on (`unset_frame`). A fully written chained DATA frame becomes the
     * [takeLastDataFrame]; the next CONTINUATION of a header block is encoded. Returns true when there is a new frame
     * to write (the reference's `ControlFlow::Continue`).
     */
    fun unsetFrame(): Boolean {
        writeBuffer.clear()
        val d = nextData
        if (d != null) {
            nextData = null
            queued = null
            lastDataFrame = d
            return false
        }
        val c = nextContinuation ?: return false
        nextContinuation = c.encode(writeBuffer, maxFrameSize + HEADER_LEN)
        return true
    }

    /** The last DATA frame that was fully encoded or written, once (`take_last_data_frame`). */
    fun takeLastDataFrame(): Data? {
        val d = lastDataFrame
        lastDataFrame = null
        return d
    }

    companion object {
        /** Initial write buffer size: the minimum MAX_FRAME_SIZE, so a HEADERS frame that big always fits. */
        const val DEFAULT_BUFFER_CAPACITY: Int = 16 * 1024

        /** Chain DATA payloads at least this big when vectored I/O is available. */
        const val CHAIN_THRESHOLD: Int = 256

        /** Chain DATA payloads at least this big without vectored I/O (fewer, larger writes). */
        const val CHAIN_THRESHOLD_WITHOUT_VECTORED_IO: Int = 1024
    }
}
