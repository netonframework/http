package neton.http.h3

import neton.http.h3.quic.ConnectionErrorIncoming

/**
 * The state a connection shares with its request streams and request senders (`SharedState`, `src/shared_state.rs`):
 * the peer's settings, the connection error, and whether the connection is closing.
 *
 * ⚖️ The reference keeps these in atomics behind an `Arc` and wakes the connection's task, which closes the QUIC
 * connection the next time it is polled. Here the connection and its streams live on one reactor (SPEC §5), so plain
 * fields suffice, and the first connection error closes the QUIC connection at once through [closer] (whoever finds it,
 * a stream or the connection), so a connection error never waits for a driver to be polled.
 */
internal class SharedState(private val closer: (Code, String) -> Unit) {
    /** The peer's settings, once its SETTINGS frame arrived (set once, `OnceLock`). */
    var peerSettings: Settings? = null
        private set

    /** The connection error, once there is one (the first one wins). */
    var error: ErrorOrigin? = null
        private set

    /** A GOAWAY was sent or received: no new requests (`closing`). */
    var closing: Boolean = false
        private set

    /** Notified on every change of the connection's state (error, closing, incoming requests, request ends). */
    val changed = Notify()

    /**
     * The settings to follow when sending (`settings`): the peer's, or the defaults before they arrive (RFC 9114
     * §7.2.4.2).
     */
    val settings: Settings get() = peerSettings ?: Settings.DEFAULT

    /** Records the peer's settings, unless already known (`set_settings`). */
    fun setSettings(settings: Settings) {
        if (peerSettings == null) peerSettings = settings
    }

    fun setClosing() {
        closing = true
        changed.notifyAll()
    }

    /**
     * Records [origin] as the connection error unless there already is one, and returns the error in effect
     * (`set_conn_error_and_wake`). The first error closes the QUIC connection when it is ours to close
     * (`close_if_needed`): an error found here with its code, an internal error of the QUIC layer with
     * H3_INTERNAL_ERROR; a connection the peer or the transport closed needs nothing.
     */
    fun setConnError(origin: ErrorOrigin): ErrorOrigin {
        error?.let { return it }
        error = origin
        when (origin) {
            is ErrorOrigin.Internal -> closer(origin.error.code, origin.error.message)
            is ErrorOrigin.Quic -> if (origin.error is ConnectionErrorIncoming.InternalError) {
                closer(Code.H3_INTERNAL_ERROR, origin.error.reason)
            }
        }
        changed.notifyAll()
        return origin
    }

    /** Suspends until there is a connection error, and returns it. */
    suspend fun awaitError(): ErrorOrigin {
        while (true) {
            error?.let { return it }
            changed.await()
        }
    }
}
