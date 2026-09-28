package neton.http.h3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import neton.http.h3.proto.Dir
import neton.http.h3.proto.Side
import neton.http.h3.proto.StreamId
import neton.http.h3.quic.BidiStream
import neton.http.h3.quic.Connection
import neton.http.h3.quic.ConnectionErrorIncoming
import neton.http.h3.quic.OpenStreams
import neton.http.h3.quic.RecvStream
import neton.http.h3.quic.SendStream
import neton.http.h3.quic.StreamErrorIncoming
import neton.io.bytes.Bytes
import kotlin.time.Duration
import kotlin.time.TimeSource

// An in-memory QUIC double for the thin interface (SPEC §5, acceptance layer 2): two connected endpoints with
// bidirectional and unidirectional streams, FIN, RESET_STREAM (bytes received before it are still read, then the reset,
// as with quinn) and STOP_SENDING (unread bytes are discarded), bounded per-stream buffers (a writer
// suspends until the reader drains: flow-control-like backpressure), connection close with an application error code,
// and an optional idle timeout. Like QUIC, a stream becomes visible to the peer when its opener first sends on it
// (data, FIN or RESET_STREAM), and streams of one kind are accepted in ID order (opening stream N implicitly opens the
// lower ones). Everything runs on one reactor thread.

/** The connection was closed locally (quinn `LocallyClosed`, reported to h3 as [ConnectionErrorIncoming.Undefined]). */
class LocallyClosed(code: Long) : Exception("closed locally with ${Code(code)}")

/**
 * A pair of connected in-memory QUIC connections, (client, server). Each stream direction buffers at most
 * [streamCapacity] bytes. With an [idleTimeout], a watchdog launched in [scope] closes both ends with
 * [ConnectionErrorIncoming.Timeout] after that long without anything sent on any stream.
 */
fun memoryQuicPair(
    scope: CoroutineScope? = null,
    streamCapacity: Int = 64 * 1024,
    idleTimeout: Duration? = null,
): Pair<MemoryConnection, MemoryConnection> {
    val link = MemoryLink(streamCapacity)
    val client = MemoryConnection(link, Side.Client)
    val server = MemoryConnection(link, Side.Server)
    client.peer = server
    server.peer = client
    link.client = client
    link.server = server
    if (idleTimeout != null) {
        requireNotNull(scope) { "an idle timeout needs a scope for its watchdog" }
        scope.launch {
            val step = maxOf(idleTimeout / 5, Duration.parse("1ms"))
            while (!link.closed) {
                delay(step)
                if (link.lastActivity.elapsedNow() >= idleTimeout) link.timeout()
            }
        }
    }
    return client to server
}

/** What the two endpoints share: the streams and the closed state. */
class MemoryLink internal constructor(val streamCapacity: Int) {
    internal lateinit var client: MemoryConnection
    internal lateinit var server: MemoryConnection
    internal val pipes = ArrayList<Pipe>()
    internal var lastActivity = TimeSource.Monotonic.markNow()

    /** Whether the connection is closed (by either side or by the idle timeout). */
    var closed = false
        private set

    /** The side that closed the connection, and the code it used (null: open, or timed out). */
    var closedBy: Side? = null
        private set
    var closeCode: Long? = null
        private set

    internal fun touch() {
        lastActivity = TimeSource.Monotonic.markNow()
    }

    internal fun close(from: MemoryConnection, code: Long) {
        if (closed) return
        closed = true
        closedBy = from.side
        closeCode = code
        from.error = ConnectionErrorIncoming.Undefined(LocallyClosed(code))
        from.peer.error = ConnectionErrorIncoming.ApplicationClose(code)
        wakeAll()
    }

    internal fun timeout() {
        if (closed) return
        closed = true
        client.error = ConnectionErrorIncoming.Timeout()
        server.error = ConnectionErrorIncoming.Timeout()
        wakeAll()
    }

    private fun wakeAll() {
        for (p in pipes) p.changed.notifyAll()
        client.acceptWaiters.notifyAll()
        server.acceptWaiters.notifyAll()
    }
}

/** One direction of a stream: the bytes in flight and the state both ends see. */
internal class Pipe(val id: StreamId, val capacity: Int) {
    val chunks = ArrayDeque<Bytes>()
    var buffered = 0
    var fin = false
    var resetCode: Long? = null
    var stopCode: Long? = null

    /** The receiver read everything up to the FIN, or stopped reading. */
    var readEnd = false
    val changed = Notify()
}

/** One endpoint of an in-memory QUIC connection. */
class MemoryConnection internal constructor(val link: MemoryLink, val side: Side) : Connection {
    internal lateinit var peer: MemoryConnection
    internal var error: ConnectionErrorIncoming? = null
    internal val acceptWaiters = Notify()
    private val incomingBi = ArrayDeque<BidiStream>()
    private val incomingUni = ArrayDeque<RecvStream>()

    // Streams this endpoint opened, by direction: the peer's handle, delivered when first used (with the lower ones).
    private val openedBi = ArrayList<BidiStream>()
    private val openedUni = ArrayList<RecvStream>()
    private var announcedBi = 0
    private var announcedUni = 0

    /** The reason this endpoint's connection is closed, or null while open. */
    val closeReason: ConnectionErrorIncoming? get() = error

    private fun newPipe(id: StreamId): Pipe = Pipe(id, link.streamCapacity).also { link.pipes.add(it) }

    override suspend fun openBi(): BidiStream {
        error?.let { throw StreamErrorIncoming.ConnectionLost(it) }
        val index = openedBi.size
        val id = StreamId.of(index.toLong(), Dir.Bi, side)
        val out = newPipe(id)
        val back = newPipe(id)
        openedBi.add(MemoryBidi(MemorySend(back, peer) {}, MemoryRecv(out, peer)))
        return MemoryBidi(MemorySend(out, this) { announceBi(index) }, MemoryRecv(back, this))
    }

    override suspend fun openUni(): SendStream {
        error?.let { throw StreamErrorIncoming.ConnectionLost(it) }
        val index = openedUni.size
        val id = StreamId.of(index.toLong(), Dir.Uni, side)
        val pipe = newPipe(id)
        openedUni.add(MemoryRecv(pipe, peer))
        return MemorySend(pipe, this) { announceUni(index) }
    }

    private fun announceBi(index: Int) {
        if (index < announcedBi) return
        while (announcedBi <= index) peer.incomingBi.addLast(openedBi[announcedBi++])
        peer.acceptWaiters.notifyAll()
    }

    private fun announceUni(index: Int) {
        if (index < announcedUni) return
        while (announcedUni <= index) peer.incomingUni.addLast(openedUni[announcedUni++])
        peer.acceptWaiters.notifyAll()
    }

    override suspend fun acceptUni(): RecvStream {
        while (true) {
            incomingUni.removeFirstOrNull()?.let { return it }
            error?.let { throw it }
            acceptWaiters.await()
        }
    }

    override suspend fun acceptBi(): BidiStream {
        while (true) {
            incomingBi.removeFirstOrNull()?.let { return it }
            error?.let { throw it }
            acceptWaiters.await()
        }
    }

    override fun opener(): OpenStreams = this

    override fun close(code: Code, reason: ByteArray) = link.close(this, code.value)

    /** Closes with a raw error code (a misbehaving peer in tests). */
    fun closeWith(code: Long) = link.close(this, code)

    override fun toString(): String = "MemoryConnection($side)"
}

/** The sending end of a [Pipe]; [onUse] makes the stream visible to the peer. */
internal class MemorySend(private val pipe: Pipe, private val owner: MemoryConnection, private val onUse: () -> Unit) :
    SendStream {
    override val sendId: StreamId get() = pipe.id

    private fun checkWritable() {
        owner.error?.let { throw StreamErrorIncoming.ConnectionLost(it) }
        pipe.stopCode?.let { throw StreamErrorIncoming.StreamTerminated(it) }
        if (pipe.fin || pipe.resetCode != null) throw StreamErrorIncoming.Unknown(IllegalStateException("closed stream ${pipe.id}"))
    }

    override suspend fun write(data: Bytes) {
        onUse()
        var off = 0
        while (true) {
            checkWritable()
            if (off == data.size) return
            val room = pipe.capacity - pipe.buffered
            if (room > 0) {
                val n = minOf(room, data.size - off)
                pipe.chunks.addLast(data.slice(off, off + n))
                pipe.buffered += n
                off += n
                owner.link.touch()
                pipe.changed.notifyAll()
                continue
            }
            pipe.changed.await()
        }
    }

    override suspend fun finish() {
        onUse()
        owner.error?.let { throw StreamErrorIncoming.ConnectionLost(it) }
        if (pipe.fin || pipe.resetCode != null) throw StreamErrorIncoming.Unknown(IllegalStateException("closed stream ${pipe.id}"))
        pipe.fin = true
        owner.link.touch()
        pipe.changed.notifyAll()
    }

    override fun reset(errorCode: Long) {
        if (owner.error != null || pipe.resetCode != null || (pipe.fin && pipe.readEnd)) return
        onUse()
        // Like quinn, the receiver still gets what it had received before the reset, then the reset.
        pipe.resetCode = errorCode
        owner.link.touch()
        pipe.changed.notifyAll()
    }

    override suspend fun stopped(): Long? {
        while (true) {
            pipe.stopCode?.let { return it }
            if (pipe.resetCode != null || (pipe.fin && pipe.readEnd)) return null
            owner.error?.let { throw StreamErrorIncoming.ConnectionLost(it) }
            pipe.changed.await()
        }
    }

    override fun toString(): String = "MemorySend(${pipe.id})"
}

/** The receiving end of a [Pipe]. */
internal class MemoryRecv(private val pipe: Pipe, private val owner: MemoryConnection) : RecvStream {
    private var stoppedLocally = false

    override val recvId: StreamId get() = pipe.id

    override suspend fun read(): Bytes? {
        while (true) {
            if (stoppedLocally) throw StreamErrorIncoming.Unknown(IllegalStateException("closed stream ${pipe.id}"))
            pipe.chunks.removeFirstOrNull()?.let {
                pipe.buffered -= it.size
                pipe.changed.notifyAll()
                return it
            }
            pipe.resetCode?.let {
                pipe.readEnd = true
                throw StreamErrorIncoming.StreamTerminated(it)
            }
            if (pipe.fin) {
                if (!pipe.readEnd) {
                    pipe.readEnd = true
                    pipe.changed.notifyAll()
                }
                return null
            }
            owner.error?.let { throw StreamErrorIncoming.ConnectionLost(it) }
            pipe.changed.await()
        }
    }

    override fun stopSending(errorCode: Long) {
        if (stoppedLocally || pipe.readEnd || pipe.resetCode != null || owner.error != null) return
        stoppedLocally = true
        pipe.stopCode = errorCode
        pipe.readEnd = true
        pipe.chunks.clear()
        pipe.buffered = 0
        owner.link.touch()
        pipe.changed.notifyAll()
    }

    override fun toString(): String = "MemoryRecv(${pipe.id})"
}

/** Both ends of a bidirectional stream as one handle. */
internal class MemoryBidi(val send: MemorySend, val recv: MemoryRecv) : BidiStream, SendStream by send, RecvStream by recv {
    override fun split(): Pair<SendStream, RecvStream> = send to recv
    override fun toString(): String = "MemoryBidi($sendId)"
}
