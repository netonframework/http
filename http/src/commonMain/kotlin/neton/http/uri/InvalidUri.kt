package neton.http.uri

import neton.http.HttpException

/**
 * An error resulting from a failed attempt to construct a URI or one of its components (`http::uri::InvalidUri`).
 *
 * [kind] tells which rule was broken; the message is the reference's `Display` text.
 */
class InvalidUri(val kind: ErrorKind) : HttpException(kind.description) {

    /** The reference's twelve URI error kinds (`uri/mod.rs` `ErrorKind`), public here so callers can branch on them. */
    enum class ErrorKind(internal val description: String) {
        InvalidUriChar("invalid uri character"),
        InvalidScheme("invalid scheme"),
        InvalidAuthority("invalid authority"),
        InvalidPort("invalid port"),
        InvalidFormat("invalid format"),
        SchemeMissing("scheme missing"),
        AuthorityMissing("authority missing"),
        PathAndQueryMissing("path missing"),
        PathDoesNotStartWithSlash("path does not start with slash"),
        TooLong("uri too long"),
        Empty("empty string"),
        SchemeTooLong("scheme too long"),
    }

    internal val isEmpty: Boolean get() = kind == ErrorKind.Empty
}

/** An error resulting from a failed attempt to construct a URI from parts (`http::uri::InvalidUriParts`). */
class InvalidUriParts(val invalidUri: InvalidUri) : HttpException(invalidUri.kind.description) {
    /** The kind of the wrapped [InvalidUri]. */
    val kind: InvalidUri.ErrorKind get() = invalidUri.kind
}

/** Turns an internal parse result (the value or an [InvalidUri.ErrorKind]) into the value, throwing on error. */
@Suppress("UNCHECKED_CAST")
internal fun <T> unwrapOrThrow(r: Any): T {
    if (r is InvalidUri.ErrorKind) throw InvalidUri(r)
    return r as T
}
