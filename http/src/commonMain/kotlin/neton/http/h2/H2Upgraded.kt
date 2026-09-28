package neton.http.h2

import neton.http.HttpError
import neton.http.h2.codec.takePayload
import neton.http.h2.frame.Reason
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.ClosedException
import neton.io.core.IoException
import neton.io.core.IoStream
import neton.io.core.StreamCapability

// A CONNECT / extended CONNECT tunnel over one HTTP/2 stream (hyper 1.11.1 `src/proto/h2/upgrade.rs`): the
// `IoStream` inside the `Upgraded` of an HTTP/2 upgrade. Reads are the stream's DATA (capacity released as consumed),
// writes become DATA, shutting the output down ends the stream.

/** An I/O error of a tunnel, with the HTTP/2 or HTTP error behind it as [cause]. */
class H2TunnelException internal constructor(message: String, override val cause: Throwable?) : IoException(message)

/**
 * hyper `H2Upgraded` with its `UpgradedSendStreamTask`.
 *
 * ⚖️ The reference writes through a one-slot channel drained by a spawned task that waits for stream capacity; here
 * [write] does that task's step itself: it claims one byte of capacity, waits until the stream has some, then hands
 * the bytes to the stream (buffered by h2 as the task's `send_data` does). The backpressure is the same (a write goes
 * out once the stream has capacity), without a task per tunnel; the reference may accept up to two writes ahead.
 */
internal class H2Upgraded(
    private val send: SendStream,
    private val recv: RecvStream,
    private val ping: Recorder,
) : IoStream {
    /** The rest of the last DATA payload not yet returned (`buf`). */
    private var buf: Bytes = Bytes.EMPTY
    private var closed = false
    private var shutdown = false

    /** The send side's error (the reference's `error_tx`), reported by later writes. */
    private var sendError: HttpError? = null

    override val capabilities: Set<StreamCapability> get() = CAPABILITIES

    override suspend fun read(dst: Buffer): Int {
        if (closed) throw ClosedException()
        if (buf.size == 0) {
            while (true) {
                val data = try {
                    recv.data()
                } catch (e: H2Error) {
                    return when (e.reason()) {
                        Reason.NO_ERROR, Reason.CANCEL -> -1
                        Reason.STREAM_CLOSED -> throw H2TunnelException("broken pipe", e)
                        else -> throw H2TunnelException(e.message, e)
                    }
                } ?: return -1
                if (data.size == 0 && !recv.isEndStream) continue
                if (data.size == 0) return -1 // an empty last frame: nothing more
                ping.recordData(data.size)
                buf = data
                break
            }
        }
        val cnt = buf.size
        dst.writeBytes(buf)
        buf = Bytes.EMPTY
        try {
            recv.flowControl().releaseCapacity(cnt)
        } catch (_: H2Error) {
        }
        return cnt
    }

    override suspend fun write(src: Buffer): Int {
        val n = src.readableBytes
        if (n == 0) return 0
        if (closed) throw ClosedException()
        sendError?.let { throw H2TunnelException(it.message ?: "", it) }
        // After shutdownOutput the task has ended: a broken pipe.
        if (shutdown) throw H2TunnelException("broken pipe", null)
        try {
            awaitSendCapacity()
            send.sendData(takePayload(src, n), false)
        } catch (e: H2Error) {
            fail(newBodyWrite(e))
        }
        return n
    }

    /** The send task's wait (`tick`): some capacity for the next chunk, unless the stream was reset. */
    private suspend fun awaitSendCapacity() {
        while (true) {
            // Claim one byte: h2 manages the capacity of the actual chunk.
            send.reserveCapacity(1)
            if (send.capacity() == 0) {
                val c = send.awaitCapacity()
                if (c == null) {
                    checkReset()
                    // The stream no longer sends: finished somehow, or the remote reset it.
                    fail(newBodyWrite(IllegalStateException("send stream capacity unexpectedly closed")))
                }
                if (c == 0) continue
            }
            checkReset()
            return
        }
    }

    private fun checkReset() {
        val reason = try {
            send.pollResetNow()
        } catch (e: HttpError) {
            fail(e)
        } ?: return
        // "stream received RST_STREAM"
        fail(newBodyWrite(H2Error.fromReason(reason)))
    }

    private fun fail(e: HttpError): Nothing {
        sendError = e
        throw H2TunnelException(e.message ?: "", e)
    }

    override suspend fun flush() {
        if (closed) throw ClosedException()
        sendError?.let { throw H2TunnelException(it.message ?: "", it) }
    }

    /** Ends the stream (hyper `poll_shutdown`: the task sends an empty END_STREAM DATA frame). */
    override suspend fun shutdownOutput() {
        if (closed) throw ClosedException()
        sendError?.let { throw H2TunnelException(it.message ?: "", it) }
        if (shutdown) return
        shutdown = true
        try {
            send.sendData(Bytes.EMPTY, true)
        } catch (e: H2Error) {
            fail(newBodyWrite(e))
        }
    }

    /**
     * Drops the tunnel: the send side ends (as the task does when the writer is dropped) and both stream handles are
     * released; a stream still receiving is then reset by h2 (NO_ERROR from a server whose side is done, else CANCEL).
     */
    override fun close() {
        if (closed) return
        closed = true
        if (!shutdown && sendError == null) {
            shutdown = true
            try {
                send.sendData(Bytes.EMPTY, true)
            } catch (_: H2Error) {
            }
        }
        recv.close()
        send.close()
        ping.release()
    }

    override fun toString(): String = "H2Upgraded { stream_id: ${send.streamId.value} }"

    private companion object {
        val CAPABILITIES = setOf(StreamCapability.HalfClose)
    }
}
