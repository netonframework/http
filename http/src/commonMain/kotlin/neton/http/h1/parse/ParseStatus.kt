package neton.http.h1.parse

import neton.http.HttpException

/**
 * An error in parsing an HTTP/1 head (httparse `lib.rs Error`).
 *
 * The parse functions do not return or throw these directly: they return an `Int` status (see [ParseStatus]) and
 * an error is encoded as the negative [code] of its entry, so a failed parse allocates nothing.
 */
enum class HttpParseError(
    /** The negative status code a parse function returns for this error. */
    val code: Int,
    /** The reference's `Display` text. */
    val description: String,
) {
    /** Invalid byte in header name. */
    HeaderName(-2, "invalid header name"),

    /** Invalid byte in header value. */
    HeaderValue(-3, "invalid header value"),

    /** Invalid byte in new line. */
    NewLine(-4, "invalid new line"),

    /** Invalid byte in Response status. */
    Status(-5, "invalid response status"),

    /** Invalid byte where token is required. */
    Token(-6, "invalid token"),

    /** Parsed more headers than provided buffer can contain. */
    TooManyHeaders(-7, "too many headers"),

    /** Invalid byte in HTTP version. */
    Version(-8, "invalid HTTP version");

    override fun toString(): String = description
}

/**
 * Interpretation of the `Int` status returned by the parse functions (httparse `Result<Status<usize>>`).
 *
 * - `>= 0`: `Complete(n)`: the head is complete and `n` bytes (counted from the parse offset) were consumed;
 *   the message body, if any, starts at `offset + n`.
 * - [PARTIAL]: `Partial`: no invalid data was found but the input ends before the head does; read more and
 *   parse again.
 * - [INVALID_CHUNK_SIZE]: only from [parseChunkSize] (httparse `InvalidChunkSize`).
 * - any other negative value: the [HttpParseError.code] of an error; see [error].
 */
object ParseStatus {
    /** The input is incomplete (httparse `Status::Partial`). */
    const val PARTIAL: Int = -1

    /** [parseChunkSize] found an invalid chunk-size line (httparse `InvalidChunkSize`). */
    const val INVALID_CHUNK_SIZE: Int = -9

    /** `status` is `Complete(status)`. */
    fun isComplete(status: Int): Boolean = status >= 0

    /** `status` is `Partial`. */
    fun isPartial(status: Int): Boolean = status == PARTIAL

    /** `status` is an error (a [HttpParseError] code or [INVALID_CHUNK_SIZE]). */
    fun isError(status: Int): Boolean = status < PARTIAL

    /** The [HttpParseError] encoded by [status], or null if [status] is not one of their codes. */
    fun error(status: Int): HttpParseError? = when (status) {
        -2 -> HttpParseError.HeaderName
        -3 -> HttpParseError.HeaderValue
        -4 -> HttpParseError.NewLine
        -5 -> HttpParseError.Status
        -6 -> HttpParseError.Token
        -7 -> HttpParseError.TooManyHeaders
        -8 -> HttpParseError.Version
        else -> null
    }

    /**
     * Returns the consumed byte count of a `Complete` [status]; throws [HttpParseException] for a parse error and
     * [IllegalStateException] for `Partial` (httparse `Status::unwrap` panics on `Partial`). Allocates on failure.
     */
    fun completeOrThrow(status: Int): Int {
        if (status >= 0) return status
        if (status == PARTIAL) throw IllegalStateException("Tried to unwrap Status::Partial")
        val e = error(status) ?: throw IllegalArgumentException(describe(status))
        throw HttpParseException(e)
    }

    /** Human-readable form of a status, for messages and tests (allocates; not for the hot path). */
    fun describe(status: Int): String = when {
        status >= 0 -> "Complete($status)"
        status == PARTIAL -> "Partial"
        status == INVALID_CHUNK_SIZE -> "Err(InvalidChunkSize)"
        else -> "Err(${error(status)?.name ?: status.toString()})"
    }
}

/**
 * Thrown by [ParseStatus.completeOrThrow] (a convenience for tests and cold paths; the parse functions themselves
 * never throw).
 */
class HttpParseException(
    /** The error. */
    val error: HttpParseError,
) : HttpException(error.description)
