package neton.http.h2

import neton.http.h2.codec.UserError
import neton.http.h2.frame.Reason
import neton.http.h2.frame.StreamId
import neton.http.h2.proto.Initiator
import neton.http.h2.proto.IoErrorKind
import neton.http.h2.proto.ProtoError
import neton.http.h2.proto.UserErrorException
import neton.io.bytes.Bytes
import neton.io.core.IoException

/**
 * An HTTP/2 error (`h2::Error`, `src/error.rs`): a stream reset, a connection-level GOAWAY, a bare [Reason], a
 * [UserError] (API misuse), or an I/O error. The message is the reference's `Display` form.
 *
 * ⚖️ Named `H2Error`: `Error` would shadow `kotlin.Error`.
 */
class H2Error private constructor(
    private val kind: Int,
    private val reasonValue: Reason,
    /** Who raised a reset or GOAWAY; null for the other kinds. */
    val initiator: Initiator?,
    /** The GOAWAY's debug data (empty for the other kinds). */
    val debugData: Bytes,
    /** The user error, for [isUser] errors. */
    val userError: UserError?,
    /** The I/O error kind, for [isIo] errors. */
    val ioKind: IoErrorKind?,
    private val ioMessage: String?,
    /** The stream of a reset. */
    val streamId: StreamId?,
) : Exception() {

    override val message: String get() = display()

    /** The reason of a reset, a GOAWAY or a bare reason (`reason`). */
    fun reason(): Reason? = if (kind == RESET || kind == GO_AWAY || kind == REASON) reasonValue else null

    /** An I/O error (`is_io`). */
    val isIo: Boolean get() = kind == IO

    /** A GOAWAY, sent or received (`is_go_away`). */
    val isGoAway: Boolean get() = kind == GO_AWAY

    /** A stream reset (`is_reset`). */
    val isReset: Boolean get() = kind == RESET

    /** A user error. */
    val isUser: Boolean get() = kind == USER

    /** Raised by the peer: a GOAWAY or reset it sent (`is_remote`). */
    val isRemote: Boolean get() = (kind == GO_AWAY || kind == RESET) && initiator == Initiator.Remote

    /** Raised by this library: a GOAWAY or reset it sent (`is_library`). */
    val isLibrary: Boolean get() = (kind == GO_AWAY || kind == RESET) && initiator == Initiator.Library

    private fun display(): String = when (kind) {
        RESET -> when (initiator) {
            Initiator.User -> "stream error sent by user: ${reasonValue.description()}"
            Initiator.Library -> "stream error detected: ${reasonValue.description()}"
            else -> "stream error received: ${reasonValue.description()}"
        }
        GO_AWAY -> {
            val head = when (initiator) {
                Initiator.User -> "connection error sent by user: ${reasonValue.description()}"
                Initiator.Library -> "connection error detected: ${reasonValue.description()}"
                else -> "connection error received: ${reasonValue.description()}"
            }
            if (debugData.isEmpty) head else "$head (${debugBytes(debugData)})"
        }
        REASON -> "protocol error: ${reasonValue.description()}"
        USER -> "user error: ${userError!!.message}"
        else -> ioMessage ?: ioKind!!.description
    }

    override fun toString(): String = "H2Error(${display()})"

    companion object {
        private const val RESET = 0
        private const val GO_AWAY = 1
        private const val REASON = 2
        private const val USER = 3
        private const val IO = 4

        /** An error carrying only a reason (`From<Reason>`). */
        fun fromReason(reason: Reason): H2Error = H2Error(REASON, reason, null, Bytes.EMPTY, null, null, null, null)

        /** A user error (`From<UserError>`). */
        fun fromUser(e: UserError): H2Error = H2Error(USER, Reason.NO_ERROR, null, Bytes.EMPTY, e, null, null, null)

        /** An I/O error (`from_io`). */
        fun fromIo(kind: IoErrorKind, message: String? = null): H2Error =
            H2Error(IO, Reason.NO_ERROR, null, Bytes.EMPTY, null, kind, message, null)

        internal fun fromIo(e: IoException): H2Error = fromIo(
            if (e is UnexpectedEofException) IoErrorKind.UnexpectedEof else IoErrorKind.Other,
            e.message,
        )

        /** `From<proto::Error>`. */
        internal fun from(e: ProtoError): H2Error = when (e) {
            is ProtoError.Reset -> H2Error(RESET, e.reason, e.initiator, Bytes.EMPTY, null, null, null, e.streamId)
            is ProtoError.GoAway -> H2Error(GO_AWAY, e.reason, e.initiator, e.debugData, null, null, null, null)
            is ProtoError.Io -> fromIo(e.kind, e.message.takeIf { it != e.kind.description })
        }

        /** Converts the internal exceptions of the protocol layer. */
        internal fun from(e: Throwable): Throwable = when (e) {
            is ProtoError -> from(e)
            is UserErrorException -> fromUser(e.error)
            else -> e
        }

        /** Rust's `Debug` of `Bytes`: `b"..."` with escapes. */
        private fun debugBytes(b: Bytes): String {
            val sb = StringBuilder("b\"")
            for (i in 0 until b.size) {
                val c = b[i].toInt() and 0xff
                when {
                    c == '"'.code -> sb.append("\\\"")
                    c == '\\'.code -> sb.append("\\\\")
                    c == '\n'.code -> sb.append("\\n")
                    c == '\r'.code -> sb.append("\\r")
                    c == '\t'.code -> sb.append("\\t")
                    c in 0x20..0x7e -> sb.append(c.toChar())
                    else -> sb.append("\\x").append(c.toString(16).padStart(2, '0'))
                }
            }
            return sb.append('"').toString()
        }
    }
}

/** Runs [block], converting the protocol layer's exceptions to [H2Error]. */
internal inline fun <T> h2Call(block: () -> T): T {
    try {
        return block()
    } catch (e: ProtoError) {
        throw H2Error.from(e)
    } catch (e: UserErrorException) {
        throw H2Error.fromUser(e.error)
    }
}

/** Throws [H2Error] for a user error returned by the protocol layer. */
internal fun UserError?.orThrow() {
    if (this != null) throw H2Error.fromUser(this)
}

/**
 * The transport ended without a clean close (a TLS stream missing its close_notify, for instance): the I/O error kind
 * `UnexpectedEof`. A stream wrapper throws it from `read`; a server connection then closes without error when it has
 * nothing more to send (hyper issue #3427), as the reference does.
 *
 * ⚖️ neton-io reports I/O errors without Rust's `io::ErrorKind`; this exception carries that one kind.
 */
class UnexpectedEofException(message: String = "unexpected end of file") : IoException(message)
