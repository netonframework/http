package neton.http.h2.codec

import neton.http.h2.proto.ProtoError

/** Errors caused by users of the library (`h2::codec::UserError`, `src/codec/error.rs`). */
enum class UserError(val message: String) {
    /** The stream ID is no longer accepting frames. */
    InactiveStreamId("inactive stream"),

    /** The stream is not currently expecting a frame of this type. */
    UnexpectedFrameType("unexpected frame type"),

    /** The payload size is too big. */
    PayloadTooBig("payload too big"),

    /** The application attempted to initiate too many streams to remote. */
    Rejected("rejected"),

    /** The released capacity is larger than claimed capacity. */
    ReleaseCapacityTooBig("release capacity too big"),

    /** The stream ID space is overflowed; a new connection is needed. */
    OverflowedStreamId("stream ID overflowed"),

    /** Illegal headers, such as connection-specific headers. */
    MalformedHeaders("malformed headers"),

    /** Request submitted with relative URI. */
    MissingUriSchemeAndAuthority("request URI missing scheme and authority"),

    /** `SendResponse.pollReset` called after `sendResponse`. */
    PollResetAfterSendResponse("poll_reset after send_response is illegal"),

    /** `PingPong.sendPing` called before receiving the previous pong. */
    SendPingWhilePending("send_ping before received previous pong"),

    /** Local SETTINGS updated while the previous ones are not acknowledged. */
    SendSettingsWhilePending("sending SETTINGS before received previous ACK"),

    /** A push promise to a peer that has disabled server push. */
    PeerDisabledServerPush("sending PUSH_PROMISE to peer who disabled server push"),

    /** An informational response whose status is not 1xx. */
    InvalidInformationalStatusCode("invalid informational status code");

    override fun toString(): String = message
}

/** Errors caused by sending a message (`h2::codec::SendError`). */
sealed class SendError(message: String) : Exception(message) {
    class Connection(val error: ProtoError) : SendError(error.message ?: "connection error")

    class User(val error: UserError) : SendError(error.message)
}
