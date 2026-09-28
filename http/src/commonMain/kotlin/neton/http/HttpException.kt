package neton.http

/**
 * Base of every error the HTTP types raise (the `http` crate's `http::Error` and its inner kinds, SPEC §2).
 * Parsing and construction APIs throw a subclass (`InvalidMethod`, `InvalidUri`, `InvalidHeaderName`, ...);
 * `tryX` variants return null instead where the reference offers a fallible form.
 */
open class HttpException(message: String) : IllegalArgumentException(message)
