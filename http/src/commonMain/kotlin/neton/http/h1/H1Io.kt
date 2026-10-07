package neton.http.h1

import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoStream

/**
 * The buffered I/O of one HTTP/1 connection (hyper `proto/h1/io.rs` `Buffered`, SPEC §3.7): the read buffer the
 * heads and bodies are parsed from, and the write buffer that collects heads and body frames until a flush.
 *
 * Writing follows hyper's two strategies:
 * - **queue** (the default; hyper picks it when the stream writes vectored): heads and body framing go into small
 *   buffers, body data is queued by reference, and a flush is one [IoStream.writev] of up to 16 data buffers;
 * - **flatten**: everything is copied into one buffer (always used with [flushPipeline], as hyper does).
 *
 * Read sizing is the stream's own (neton-io adapts the read size like hyper's `ReadStrategy`, neton-io SPEC §19.4);
 * [maxBufSize] bounds the read buffer while a head is incomplete (beyond → 431).
 */
internal class H1Io(val stream: IoStream, val maxBufSize: Int = DEFAULT_MAX_BUFFER_SIZE) {
    init { require(maxBufSize >= MINIMUM_MAX_BUFFER_SIZE) { "The max_buf_size cannot be smaller than $MINIMUM_MAX_BUFFER_SIZE." } }

    val readBuf = Buffer(INIT_BUFFER_SIZE, pooled = true)

    /** Read-side EOF has been seen. */
    var readEof = false

    /** hyper `flush_pipeline`: while the read buffer holds a pipelined request, flushes are skipped. */
    var flushPipeline = false
        set(value) { field = value; if (value) queueStrategy = false }

    var queueStrategy = true

    /** The first segment: status line / request line and headers, and everything in the flatten strategy. */
    @PublishedApi internal val head = Buffer(INIT_BUFFER_SIZE, pooled = true)
    @PublishedApi internal val segments = Array(2 * MAX_BUF_LIST_BUFFERS + 2) { head }
    @PublishedApi internal var segmentCount = 0
    private var framing = arrayOfNulls<Buffer>(4)
    private var framingUsed = 0
    private var wrappers = arrayOfNulls<Buffer>(4)
    private var queuedBuffers = 0
    private var queuedBytes = 0L

    /** One writer at a time: the connection and, for `100 Continue`, a request body read elsewhere. */
    val writeLock = WriteGate()

    /**
     * Server only: called when a flush's write does not complete at once after the response has put more than
     * [WATCH_WRITES_AFTER] bytes on the wire. The server then watches the read side, so a client that goes away cancels
     * the parked write (it runs in [writeContext]) instead of leaving it parked (hyper polls the read side while a write
     * is pending). Smaller responses fit in the socket buffers and cannot be held up by backpressure on their own; they
     * keep the plain write (on io_uring every write is pending until its completion is reaped, so "pending" alone says
     * nothing). null: plain writes always.
     */
    var onWritePending: (() -> Unit)? = null

    /** Bytes the current response has flushed (the server resets it per exchange). */
    var messageBytes = 0L

    // The writes as calls that report whether they completed at once; nothing is allocated when they do.
    @PublishedApi internal val headCall = InlineCall<H1Io, Int>(H1Io::writeHead)
    @PublishedApi internal val segmentsCall = InlineCall<H1Io, Long>(H1Io::writeSegments)

    /** The context a watched write runs in (the exchange's, so cancelling the exchange cancels the write). */
    var writeContext: kotlin.coroutines.CoroutineContext
        get() = headCall.context
        set(value) { headCall.context = value; segmentsCall.context = value }

    // Tail calls only: no continuation of their own.
    @PublishedApi internal suspend fun writeHead(): Int = stream.write(head)
    @PublishedApi internal suspend fun writeSegments(): Long = stream.writev(segments, segmentCount)

    /** The watched write: as [flush]'s, but cancellable through [writeContext] and reported to [onWritePending]. */
    @PublishedApi internal suspend fun watchedWrite(pending: () -> Unit) {
        if (segmentCount == 0) {
            if (head.readableBytes > 0 && headCall.start(this) === kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
                pending(); headCall.await()
            }
        } else if (segmentsCall.start(this) === kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
            pending(); segmentsCall.await()
        }
    }

    /** Bytes waiting to be written. */
    val buffered: Long get() = head.readableBytes + queuedBytes + framingBytes()

    private fun framingBytes(): Long {
        var n = 0L
        for (i in 0 until framingUsed) n += framing[i]!!.readableBytes
        return n
    }

    /** hyper `can_headers_buf`: a new head may be written (nothing queued behind the head buffer). */
    val canHeadersBuf: Boolean get() = segmentCount == 0

    /** The buffer a head is written into. */
    fun headersBuf(): Buffer = head

    /** The buffer the framing before the next data (or the end of a body) goes into. */
    fun framingBuf(): Buffer {
        if (!queueStrategy || segmentCount == 0) return head
        val last = segments[segmentCount - 1]
        if (last === head || isFraming(last)) return last
        if (framingUsed == framing.size) framing = framing.copyOf(framingUsed * 2)
        val f = framing[framingUsed] ?: Buffer(64).also { framing[framingUsed] = it }
        framingUsed++
        segments[segmentCount++] = f
        return f
    }

    private fun isFraming(b: Buffer): Boolean {
        for (i in 0 until framingUsed) if (framing[i] === b) return true
        return false
    }

    /** Queue body [data] (by reference with the queue strategy, else copied). */
    fun bufferData(data: Bytes) {
        if (data.size == 0) return
        if (!queueStrategy) { head.writeBytes(data); return }
        // ⚖️ Small data is copied behind the framing instead of queued by reference (hyper queues everything): a
        // vectored write of a separate small slice cost more than the copy (hello world: 14.2k vs 13.0k instructions
        // per request). Large data stays zero-copy. The bytes on the wire are the same.
        if (data.size < QUEUE_MIN_BYTES) { framingBuf().writeBytes(data); return }
        if (segmentCount == 0) segments[segmentCount++] = head
        if (queuedBuffers == wrappers.size) wrappers = wrappers.copyOf(queuedBuffers * 2)
        val w = wrappers[queuedBuffers] ?: Buffer(16).also { wrappers[queuedBuffers] = it }
        w.borrow(data)
        segments[segmentCount++] = w
        queuedBuffers++
        queuedBytes += data.size
    }

    /** hyper `can_buffer`: more body may be buffered before a flush. */
    val canBuffer: Boolean
        get() = flushPipeline || if (queueStrategy) queuedBuffers < MAX_BUF_LIST_BUFFERS && buffered < maxBufSize else buffered < maxBufSize

    /**
     * hyper `poll_flush`: write everything buffered, then flush the stream. With [flushPipeline] and a pipelined
     * request already in the read buffer, nothing is written yet (the responses are coalesced).
     */
    // Inline: one continuation less per response (every suspend function call allocates one on Kotlin/Native).
    @Suppress("NOTHING_TO_INLINE")
    suspend inline fun flush() {
        if (flushPipeline && readBuf.readableBytes > 0) return
        val pending = onWritePending
        if (pending != null) {
            messageBytes += head.readableBytes + queuedBytes     // chunk framing (a few bytes) left out: no loop per flush
            if (messageBytes > WATCH_WRITES_AFTER) {
                watchedWrite(pending)
                resetWrite()
                stream.flush()
                return
            }
        }
        if (segmentCount == 0) {
            if (head.readableBytes > 0) stream.write(head)
        } else {
            stream.writev(segments, segmentCount)
        }
        resetWrite()
        stream.flush()
    }

    @PublishedApi internal fun resetWrite() {
        head.clear()
        for (i in 0 until segmentCount) segments[i] = head
        segmentCount = 0
        for (i in 0 until queuedBuffers) wrappers[i]!!.borrow(Bytes.EMPTY)      // do not keep the sent data alive
        for (i in 0 until framingUsed) framing[i]!!.clear()
        framingUsed = 0
        queuedBuffers = 0
        queuedBytes = 0
    }

    /** Drop whatever is buffered (the connection is failing). */
    fun discardWrites() = resetWrite()

    /**
     * Read more into [readBuf] (hyper `poll_read_from_io`). Returns the count, or 0 at EOF (hyper's convention);
     * [readEof] is set then.
     */
    @Suppress("NOTHING_TO_INLINE")
    suspend inline fun readFromIo(): Int {
        val n = stream.read(readBuf)
        if (n < 0) { readEof = true; return 0 }
        return n
    }

    /** hyper `consume_leading_lines`: skip CR / LF before a head (after a parse error, to classify it). */
    fun consumeLeadingLines() {
        var i = 0
        val n = readBuf.readableBytes
        while (i < n) {
            val b = readBuf.getByte(i)
            if (b == '\r'.code.toByte() || b == '\n'.code.toByte()) i++ else break
        }
        readBuf.skip(i)
    }

    companion object {
        const val INIT_BUFFER_SIZE = 8192
        const val MINIMUM_MAX_BUFFER_SIZE = INIT_BUFFER_SIZE
        const val DEFAULT_MAX_BUFFER_SIZE = 8192 + 4096 * 100

        /** A response beyond this many bytes may be held up by backpressure: its writes are watched (onWritePending). */
        const val WATCH_WRITES_AFTER = 64L * 1024
        const val MAX_BUF_LIST_BUFFERS = 16
        /** Body data smaller than this is copied into the write buffer; larger data is queued by reference. */
        const val QUEUE_MIN_BYTES = 1024
    }
}
