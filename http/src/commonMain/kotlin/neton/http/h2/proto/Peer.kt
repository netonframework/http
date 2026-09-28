package neton.http.h2.proto

import neton.http.Method
import neton.http.Request
import neton.http.RequestParts
import neton.http.Response
import neton.http.ResponseParts
import neton.http.StatusCode
import neton.http.Version
import neton.http.h2.Protocol
import neton.http.h2.codec.UserError
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Pseudo
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.Reason
import neton.http.h2.frame.StreamId
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.http.uri.Authority
import neton.http.uri.PathAndQuery
import neton.http.uri.Scheme
import neton.http.uri.Uri
import neton.http.uri.UriParts

/** How a remote stream is opened (`Open`). */
internal enum class Open {
    PushPromise,
    Headers,
}

/**
 * Client or server (`peer::Dyn`, `src/proto/peer.rs`), with the message conversions of `client::Peer` and
 * `server::Peer` (`src/client.rs`, `src/server.rs`).
 */
internal enum class Peer {
    Client,
    Server;

    val isServer: Boolean get() = this == Server

    /** Whether stream [id] is initiated by this side (`is_local_init`). */
    fun isLocalInit(id: StreamId): Boolean {
        check(!id.isZero)
        return isServer == id.isServerInitiated
    }

    /**
     * The received head of a message (`convert_poll_message`): a [Request] on the server, a [Response] on the
     * client, with a unit body.
     * @throws ProtoError a stream PROTOCOL_ERROR for a malformed message.
     */
    fun convertPollMessage(pseudo: Pseudo, fields: HeaderMap<HeaderValue>, streamId: StreamId): Any =
        if (isServer) serverConvertPollMessage(pseudo, fields, streamId) else clientConvertPollMessage(pseudo, fields, streamId)

    /**
     * Whether the remote peer may open stream [id] (`ensure_can_open`): a server accepts client-initiated HEADERS, a
     * client accepts server-initiated PUSH_PROMISE streams.
     * @throws ProtoError a GOAWAY PROTOCOL_ERROR otherwise.
     */
    fun ensureCanOpen(id: StreamId, mode: Open) {
        if (isServer) {
            // "cannot open stream - not client initiated"
            if (mode == Open.PushPromise || !id.isClientInitiated) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        } else {
            // "cannot open stream - not server initiated"
            if (mode != Open.PushPromise || !id.isServerInitiated) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        }
    }

    companion object {
        /** A received response (`client::Peer::convert_poll_message`). Without `:status` it is 200, as the builder's default. */
        fun clientConvertPollMessage(pseudo: Pseudo, fields: HeaderMap<HeaderValue>, streamId: StreamId): Response<Unit> {
            val parts = ResponseParts(status = pseudo.status ?: StatusCode.OK, version = Version.HTTP_2, headers = fields)
            return Response(parts, Unit)
        }

        /**
         * A received request (`server::Peer::convert_poll_message`), with the reference's checks: `:method` is
         * required; `:protocol` only with CONNECT; no `:status`; `:scheme` and `:path` required except for a plain
         * CONNECT, which must have neither; an extended CONNECT needs `:path`; the authority, scheme and path must
         * parse. The scheme is dropped when there is no authority (a URI cannot have a scheme and a path only).
         */
        fun serverConvertPollMessage(pseudo: Pseudo, fields: HeaderMap<HeaderValue>, streamId: StreamId): Request<Unit> {
            fun malformed(): Nothing = throw ProtoError.libraryReset(streamId, Reason.PROTOCOL_ERROR)

            val method = pseudo.method ?: malformed() // "missing method"
            val isConnect = method == Method.CONNECT
            val parts = RequestParts(method = method, version = Version.HTTP_2, headers = fields)

            val protocol = pseudo.protocol
            val hasProtocol = protocol != null
            if (protocol != null) {
                if (isConnect) parts.extensions.insert(Protocol(protocol)) else malformed() // ":protocol on non-CONNECT"
            }
            if (pseudo.status != null) malformed() // ":status field on request"

            val uriParts = UriParts()
            pseudo.authority?.let { uriParts.authority = Authority.tryParse(it) ?: malformed() }

            val scheme = pseudo.scheme
            if (scheme != null) {
                if (isConnect && !hasProtocol) malformed() // ":scheme in CONNECT"
                val parsed = Scheme.tryParse(scheme) ?: malformed()
                if (uriParts.authority != null) uriParts.scheme = parsed
            } else if (!isConnect || hasProtocol) {
                malformed() // "missing scheme"
            }

            val path = pseudo.path
            if (path != null) {
                if (isConnect && !hasProtocol) malformed() // ":path in CONNECT"
                if (path.isEmpty()) malformed() // "missing path"
                uriParts.pathAndQuery = PathAndQuery.tryParse(path) ?: malformed()
            } else if (isConnect && hasProtocol) {
                malformed() // "missing path in extended CONNECT"
            }

            parts.uri = Uri.tryFromParts(uriParts) ?: malformed() // "error building request"
            return Request(parts, Unit)
        }

        /**
         * The HEADERS of an outgoing request (`client::Peer::convert_send_message`). A request without scheme is
         * accepted only when its version is not HTTP/2 (a forwarded HTTP/1 request with a relative URI gets `http`);
         * an HTTP/2 one is [UserError.MissingUriSchemeAndAuthority].
         */
        fun clientConvertSendMessage(id: StreamId, request: Request<*>, protocol: Protocol?, endOfStream: Boolean): Headers {
            val parts = request.parts
            val pseudo = Pseudo.request(parts.method, parts.uri, protocol?.asStr())
            if (pseudo.scheme == null) {
                if (pseudo.authority == null) {
                    if (parts.version == Version.HTTP_2) throw UserErrorException(UserError.MissingUriSchemeAndAuthority)
                    pseudo.setScheme(Scheme.HTTP)
                }
                // else: must be CONNECT (TODO in the reference)
            }
            val frame = Headers(id, pseudo, parts.headersOrNull ?: HeaderMap())
            if (endOfStream) frame.setEndStream()
            return frame
        }

        /** The HEADERS of an outgoing response (`server::Peer::convert_send_message`). */
        fun serverConvertSendMessage(id: StreamId, response: Response<*>, endOfStream: Boolean): Headers {
            val frame = Headers(id, Pseudo.response(response.status), response.parts.headersOrNull ?: HeaderMap())
            if (endOfStream) frame.setEndStream()
            return frame
        }

        /**
         * The PUSH_PROMISE of a pushed request (`server::Peer::convert_push_message`); a request that is not safe and
         * cacheable or has a body is [UserError.MalformedHeaders].
         */
        fun convertPushMessage(streamId: StreamId, promisedId: StreamId, request: Request<*>): PushPromise {
            if (PushPromise.validateRequest(request) != null) throw UserErrorException(UserError.MalformedHeaders)
            val pseudo = Pseudo.request(request.method, request.uri, null)
            return PushPromise(streamId, promisedId, pseudo, request.parts.headersOrNull ?: HeaderMap())
        }
    }
}
