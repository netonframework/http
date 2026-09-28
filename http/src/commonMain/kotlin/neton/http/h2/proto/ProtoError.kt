package neton.http.h2.proto

import neton.http.h2.frame.Reason
import neton.http.h2.frame.StreamId
import neton.io.bytes.Bytes

/** Who caused an error (`h2::proto::error::Initiator`). */
enum class Initiator {
    User,
    Library,
    Remote;

    val isLocal: Boolean get() = this != Remote

    val isLibrary: Boolean get() = this == Library
}

/** The class of an I/O error (the subset of `std::io::ErrorKind` the HTTP/2 layers report). */
enum class IoErrorKind(val description: String) {
    /** A write accepted no bytes although bytes remained (`WriteZero`). */
    WriteZero("write zero"),

    /** Any other I/O error (`Other`). */
    Other("other error"),
}

/**
 * A connection-level or stream-level HTTP/2 error (`h2::proto::Error`): a stream to reset, a connection to close with
 * GOAWAY, or an I/O error. The message is the reason's description (the reference's `Display`).
 *
 * ⚖️ Named `ProtoError` (Kotlin's `Error` is taken by `kotlin.Error`), and an exception, so the codec throws it where
 * the reference returns `Err`.
 */
sealed class ProtoError(message: String) : Exception(message) {
    /** The stream is to be reset with [reason] (RST_STREAM). */
    class Reset(val streamId: StreamId, val reason: Reason, val initiator: Initiator) : ProtoError(reason.description()) {
        override fun equals(other: Any?): Boolean =
            other is Reset && other.streamId == streamId && other.reason == reason && other.initiator == initiator

        override fun hashCode(): Int = (streamId.value * 31 + reason.code) * 31 + initiator.hashCode()

        override fun toString(): String = "Reset(${streamId.value}, $reason, $initiator)"
    }

    /** The connection is to be closed with GOAWAY [reason] and [debugData]. */
    class GoAway(val debugData: Bytes, val reason: Reason, val initiator: Initiator) : ProtoError(reason.description()) {
        override fun equals(other: Any?): Boolean =
            other is GoAway && other.debugData == debugData && other.reason == reason && other.initiator == initiator

        override fun hashCode(): Int = (debugData.hashCode() * 31 + reason.code) * 31 + initiator.hashCode()

        override fun toString(): String = "GoAway(${debugData.decodeToString()}, $reason, $initiator)"
    }

    /** An I/O error. */
    class Io(val kind: IoErrorKind, message: String? = null) : ProtoError(message ?: kind.description) {
        override fun equals(other: Any?): Boolean = other is Io && other.kind == kind && other.message == message

        override fun hashCode(): Int = kind.hashCode()

        override fun toString(): String = "Io($kind, $message)"
    }

    /** Whether the error was raised locally, by the user or by the library (`is_local`). */
    val isLocal: Boolean
        get() = when (this) {
            is Reset -> initiator.isLocal
            is GoAway -> initiator.isLocal
            is Io -> true
        }

    companion object {
        fun userGoAway(reason: Reason): ProtoError = GoAway(Bytes.EMPTY, reason, Initiator.User)

        fun libraryReset(streamId: StreamId, reason: Reason): ProtoError = Reset(streamId, reason, Initiator.Library)

        fun libraryGoAway(reason: Reason): ProtoError = GoAway(Bytes.EMPTY, reason, Initiator.Library)

        fun libraryGoAwayData(reason: Reason, debugData: String): ProtoError =
            GoAway(Bytes.wrap(debugData.encodeToByteArray()), reason, Initiator.Library)

        fun remoteReset(streamId: StreamId, reason: Reason): ProtoError = Reset(streamId, reason, Initiator.Remote)

        fun remoteGoAway(debugData: Bytes, reason: Reason): ProtoError = GoAway(debugData, reason, Initiator.Remote)
    }
}
