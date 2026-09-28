package neton.http

import kotlinx.coroutines.CompletableDeferred
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.core.StreamCapability

/**
 * A connection after an HTTP upgrade (hyper `upgrade::Upgraded`, SPEC §3.6): an [IoStream] that first returns the
 * bytes the HTTP connection had already read past the head (hyper `Rewind`), then reads the original stream. It can
 * be handed to another protocol (a WebSocket, a tunnel) directly.
 */
class Upgraded internal constructor(private val io: IoStream, readBuf: Bytes) : IoStream {
    private var prefix: Bytes? = readBuf.takeIf { it.size > 0 }

    override val capabilities: Set<StreamCapability> get() = io.capabilities

    override suspend fun read(dst: Buffer): Int {
        val p = prefix
        if (p != null) { prefix = null; dst.writeBytes(p); return p.size }
        return io.read(dst)
    }

    override suspend fun write(src: Buffer): Int = io.write(src)
    override suspend fun flush() = io.flush()
    override fun close() = io.close()
    override suspend fun writev(buffers: Array<Buffer>, count: Int): Long = io.writev(buffers, count)
    override suspend fun shutdownOutput() = io.shutdownOutput()
    override fun setTimeouts(readTimeoutMillis: Long, writeTimeoutMillis: Long, idleTimeoutMillis: Long) =
        io.setTimeouts(readTimeoutMillis, writeTimeoutMillis, idleTimeoutMillis)
    override fun setReadTimeout(millis: Long) = io.setReadTimeout(millis)

    /** hyper `downcast`: the original stream and the bytes already read from it that were not consumed. */
    fun downcast(): Pair<IoStream, Bytes> {
        val p = prefix ?: Bytes.EMPTY
        prefix = null
        return io to p
    }
}

/**
 * A pending upgrade (hyper `upgrade::OnUpgrade`), found in the extensions of a request that asked for one (server) or
 * of a response that granted one (client). [await] returns the [Upgraded] connection once the HTTP exchange is done.
 * Errors (hyper): [HttpError.Kind.UserNoUpgrade] when the connection did not switch protocols,
 * [HttpError.Kind.UserManualUpgrade] when the connection was served without upgrades enabled,
 * [HttpError.Kind.Canceled] when it ended first.
 */
class OnUpgrade internal constructor() {
    private val result = CompletableDeferred<Upgraded>()

    suspend fun await(): Upgraded = result.await()

    internal fun fulfill(upgraded: Upgraded) { result.complete(upgraded) }
    internal fun fail(error: HttpError) { result.completeExceptionally(error) }
    internal val isDone: Boolean get() = result.isCompleted
}

/** hyper `hyper::upgrade::on(request)`: the pending upgrade of [message]'s extensions, or [HttpError.Kind.UserNoUpgrade]. */
suspend fun upgradeOn(extensions: Extensions): Upgraded =
    (extensions.get<OnUpgrade>() ?: throw HttpError(HttpError.Kind.UserNoUpgrade)).await()
