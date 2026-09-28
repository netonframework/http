package neton.http.h2

import neton.http.Frame
import neton.http.HttpError
import neton.http.Incoming
import neton.http.h2.frame.Reason

// The HTTP/2 kind of hyper's `Incoming` body (hyper 1.11.1 `src/body/incoming.rs`, `Kind::H2`): DATA read from a
// `RecvStream`, its capacity released as it is read, then the trailers.

/**
 * hyper `Incoming::h2`: the body of [recv], with the declared [contentLength] (-1 when unknown) and the ping
 * [ping] recorder (released with the body). A stream that already ended with no length is an empty body; its stream
 * handle is released at once.
 */
internal fun h2Incoming(recv: RecvStream, contentLength: Long, ping: Recorder): Incoming {
    var len = contentLength
    // If the stream is already EOS, the "unknown length" is clearly zero.
    val ended = recv.isEndStream
    if (len < 0 && ended) len = 0
    if (ended && len == 0L) {
        recv.close()
        ping.release()
        return Incoming.EMPTY
    }
    return Incoming(H2BodySource(recv, len, ping), 0, len)
}

/** Reads an HTTP/2 body for an [Incoming] (`Kind::H2`). */
private class H2BodySource(
    private var recv: RecvStream?,
    /** The declared length still to come, or -1 (`content_length`, decremented as data arrives: `sub_if`). */
    private var remaining: Long,
    private val ping: Recorder,
) : Incoming.Source {
    private var dataDone = false

    override suspend fun readBodyFrame(generation: Int): Frame? {
        val r = recv ?: return null
        if (!dataDone) {
            val bytes = try {
                r.data()
            } catch (e: H2Error) {
                finish()
                // RFC 9113 §8.1: a RST_STREAM with NO_ERROR is an early response; the body stops without failing.
                if (e.reason() == Reason.NO_ERROR) return null
                throw HttpError(HttpError.Kind.Body, e)
            }
            if (bytes != null) {
                try {
                    r.flowControl().releaseCapacity(bytes.size)
                } catch (_: H2Error) {
                }
                if (remaining > 0) remaining = maxOf(0L, remaining - bytes.size)
                ping.recordData(bytes.size)
                return Frame.Data(bytes)
            }
            dataDone = true
            // Fall through to the trailers.
        }
        val trailers = try {
            r.trailers()
        } catch (e: H2Error) {
            finish()
            // As above: a NO_ERROR reset stops reading the trailers without failing.
            if (e.reason() == Reason.NO_ERROR) return null
            throw newH2(e)
        }
        ping.recordNonData()
        finish()
        return trailers?.let { Frame.Trailers(it) }
    }

    override fun remaining(generation: Int): Long = remaining

    override fun ended(generation: Int): Boolean = recv?.isEndStream ?: true

    override fun close(generation: Int) = finish()

    /** The body is done (read to its end, failed, or dropped): the stream handle and the ping recorder are released. */
    private fun finish() {
        val r = recv ?: return
        recv = null
        r.close()
        ping.release()
    }
}
