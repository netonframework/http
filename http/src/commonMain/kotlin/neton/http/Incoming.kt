package neton.http

/**
 * A body received on a connection (hyper `body::Incoming`, SPEC §3.10): a request body on the server, a response body
 * on the client. Reading it reads the connection directly, in the reader's coroutine; nothing is copied into an
 * intermediate buffer and no data is read before it is asked for (backpressure, as hyper's body channel).
 */
class Incoming internal constructor(private val source: Source?, private val generation: Int, private val exactLength: Long) : Body {

    /** What an [Incoming] reads from (the connection). */
    internal interface Source {
        suspend fun readBodyFrame(generation: Int): Frame?
        fun remaining(generation: Int): Long
        fun ended(generation: Int): Boolean
    }

    override suspend fun nextFrame(): Frame? = source?.readBodyFrame(generation)

    override val isEndStream: Boolean get() = source == null || source.ended(generation)

    /** hyper: the remaining length of a length-delimited body; unknown for chunked and close-delimited ones. */
    override val sizeHint: SizeHint
        get() {
            if (source == null) return SizeHint.withExact(0)
            if (exactLength < 0) return SizeHint.DEFAULT
            val r = source.remaining(generation)
            return if (r >= 0) SizeHint.withExact(r) else SizeHint.DEFAULT
        }

    companion object {
        /** An empty body (hyper `Incoming::empty`). */
        val EMPTY: Incoming = Incoming(null, 0, 0)
    }
}
