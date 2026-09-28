package neton.http

/**
 * An error from a connection (hyper `Error`, `error.rs`, SPEC §3.8): its [kind] and, when there is one, the
 * underlying [cause]. The predicates carry hyper's names.
 */
class HttpError(val kind: Kind, cause: Throwable? = null) : Exception(describe(kind, cause), cause) {

    /** hyper `Kind` with `Parse`, `Header` and `User` flattened into one enumeration. */
    enum class Kind(val description: String) {
        ParseMethod("invalid HTTP method parsed"),
        ParseVersion("invalid HTTP version parsed"),
        ParseVersionH2("invalid HTTP version parsed (found HTTP2 preface)"),
        ParseUri("invalid URI"),
        ParseUriTooLong("URI too long"),
        ParseHeaderToken("invalid HTTP header parsed"),
        ParseHeaderContentLengthInvalid("invalid content-length parsed"),
        ParseHeaderTransferEncodingInvalid("invalid transfer-encoding parsed"),
        ParseHeaderTransferEncodingUnexpected("unexpected transfer-encoding parsed"),
        /** ⚖️ Transfer-Encoding with Content-Length (SPEC §3.9); hyper has no such error. */
        ParseHeaderTransferEncodingWithContentLength("transfer-encoding with content-length parsed"),
        ParseTooLarge("message head is too large"),
        ParseStatus("invalid HTTP status-code parsed"),
        ParseInternal("internal error inside the HTTP library, please report"),
        IncompleteMessage("connection closed before message completed"),
        UnexpectedMessage("received unexpected message from connection"),
        Canceled("operation was canceled"),
        ChannelClosed("channel closed"),
        Io("connection error"),
        HeaderTimeout("read header from client timeout"),
        Body("error reading a body from connection"),
        BodyWrite("error writing a body to connection"),
        Shutdown("error shutting down connection"),
        Http2("http2 error"),
        UserBody("error from user's Body stream"),
        UserBodyWriteAborted("user body write aborted"),
        UserInvalidConnectWithBody("user sent CONNECT request with non-zero body"),
        UserService("error from user's Service"),
        UserUnexpectedHeader("user sent unexpected header"),
        UserUnsupportedStatusCode("response has 1xx status code, not supported by server"),
        UserNoUpgrade("no upgrade available"),
        UserManualUpgrade("upgrade expected but low level API in use"),
        UserDispatchGone("dispatch task is gone"),
        /** ⚖️ Request body over `maxRequestBodySize` (SPEC §3.7); hyper has no limit. */
        UserBodyTooLarge("request body too large"),
    }

    fun isParse(): Boolean = kind.name.startsWith("Parse")
    fun isParseTooLarge(): Boolean = kind == Kind.ParseTooLarge || kind == Kind.ParseUriTooLong
    fun isParseStatus(): Boolean = kind == Kind.ParseStatus
    fun isParseVersionH2(): Boolean = kind == Kind.ParseVersionH2
    fun isUser(): Boolean = kind.name.startsWith("User")
    fun isCanceled(): Boolean = kind == Kind.Canceled
    fun isClosed(): Boolean = kind == Kind.ChannelClosed
    fun isIncompleteMessage(): Boolean = kind == Kind.IncompleteMessage
    fun isBodyWriteAborted(): Boolean = kind == Kind.UserBodyWriteAborted
    fun isShutdown(): Boolean = kind == Kind.Shutdown

    /** A header read timeout, or any cause chain containing a timeout (hyper `find_source::<TimedOut>`). */
    fun isTimeout(): Boolean {
        if (kind == Kind.HeaderTimeout) return true
        var c = cause
        while (c != null) {
            if (c is neton.io.core.TimeoutException) return true
            c = c.cause
        }
        return false
    }

    private companion object {
        fun describe(kind: Kind, cause: Throwable?): String =
            if (cause == null) kind.description else "${kind.description}: ${cause.message ?: cause::class.simpleName}"
    }
}
