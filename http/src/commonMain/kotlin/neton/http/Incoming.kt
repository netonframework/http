package neton.http

/**
 * A body received on a connection (hyper `body::Incoming`, SPEC §3.10): a request body on the server, a response body
 * on the client. Reading it reads the connection directly, in the reader's coroutine; nothing is copied into an
 * intermediate buffer and no data is read before it is asked for (backpressure, as hyper's body channel).
 *
 * [close] drops the body (hyper drops an `Incoming`): an HTTP/2 body not read to its end releases its stream then
 * (the peer may be told to stop sending). An HTTP/1 response body closed before its end is drained from what is
 * already buffered, or else its connection is closed (it can carry no other request); the server handles an unread
 * request body when the exchange ends.
 */
class Incoming internal constructor(private val source: Source?, private val generation: Int, private val declaredLength: Long) : Body, AutoCloseable {

    /** What an [Incoming] reads from (the connection). */
    internal interface Source {
        suspend fun readBodyFrame(generation: Int): Frame?
        fun remaining(generation: Int): Long
        fun ended(generation: Int): Boolean
        fun close(generation: Int) {}
    }

    override suspend fun nextFrame(): Frame? = source?.readBodyFrame(generation)

    /** Drops the body: the rest is not read (idempotent). */
    override fun close() {
        source?.close(generation)
    }

    override val isEndStream: Boolean get() = source == null || source.ended(generation)

    /** hyper: the remaining length of a length-delimited body; unknown for chunked and close-delimited ones. */
    override val sizeHint: SizeHint
        get() {
            if (source == null) return SizeHint.withExact(0)
            if (declaredLength < 0) return SizeHint.DEFAULT
            val r = source.remaining(generation)
            return if (r >= 0) SizeHint.withExact(r) else SizeHint.DEFAULT
        }

    override val exactLength: Long
        get() = when {
            source == null -> 0
            declaredLength < 0 -> -1
            else -> source.remaining(generation).let { if (it >= 0) it else -1 }
        }

    companion object {
        /** An empty body (hyper `Incoming::empty`). */
        val EMPTY: Incoming = Incoming(null, 0, 0)
    }
}
