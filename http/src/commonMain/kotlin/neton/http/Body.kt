package neton.http

import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes

/** One frame of a message body (`http_body::Frame`): data, or the trailers that end it. */
sealed class Frame {
    class Data(val bytes: Bytes) : Frame()
    class Trailers(val headers: HeaderMap<HeaderValue>) : Frame()
}

/** Bounds on a body's remaining length (`http_body::SizeHint`): [lower] ≤ length ≤ [upper] (null = unbounded). */
class SizeHint(val lower: Long = 0, val upper: Long? = null) {
    init { require(lower >= 0 && (upper == null || upper >= lower)) }

    /** The exact remaining length, when known. */
    val exact: Long? get() = if (upper != null && upper == lower) lower else null

    companion object {
        val DEFAULT = SizeHint()
        fun withExact(length: Long) = SizeHint(length, length)
    }
}

/**
 * A streaming message body (`http_body::Body`, SPEC §2): frames until null. The connection layer uses
 * [isEndStream] to know there is no body and [sizeHint].exact to choose a fixed length over chunked encoding.
 */
interface Body {
    /** The next frame, or null when the body has ended. */
    suspend fun nextFrame(): Frame?
    val isEndStream: Boolean get() = false
    val sizeHint: SizeHint get() = SizeHint.DEFAULT

    /**
     * The exact remaining length, or -1 when unknown: [sizeHint]'s `exact` without creating a [SizeHint] (the connection
     * asks once per message). Override together with [sizeHint] when the length is known.
     */
    val exactLength: Long get() = sizeHint.exact ?: -1
}

/** A body without content (`http_body_util::Empty`). */
object EmptyBody : Body {
    override suspend fun nextFrame(): Frame? = null
    override val isEndStream: Boolean get() = true
    override val sizeHint: SizeHint = SizeHint.withExact(0)
    override val exactLength: Long get() = 0
}

/** A body of one chunk of bytes (`http_body_util::Full`). */
class FullBody(bytes: Bytes) : Body {
    private var data: Bytes? = bytes.takeIf { it.size > 0 }
    override suspend fun nextFrame(): Frame? = data?.let { data = null; Frame.Data(it) }
    override val isEndStream: Boolean get() = data == null
    override val sizeHint: SizeHint get() = SizeHint.withExact(data?.size?.toLong() ?: 0)
    override val exactLength: Long get() = data?.size?.toLong() ?: 0
}
