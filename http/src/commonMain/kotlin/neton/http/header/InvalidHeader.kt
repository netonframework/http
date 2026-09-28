package neton.http.header

import neton.http.HttpException

/** A possible error when converting a [HeaderName] from another type (`http::header::InvalidHeaderName`). */
class InvalidHeaderName : HttpException("invalid HTTP header name")

/** A possible error when converting a [HeaderValue] from a string or byte slice (`http::header::InvalidHeaderValue`). */
class InvalidHeaderValue : HttpException("failed to parse header value")

/**
 * A possible error when converting a [HeaderValue] to a string representation (`http::header::ToStrError`).
 *
 * Header field values may contain opaque bytes, in which case it is not possible to represent the value as a string.
 */
class ToStrError : HttpException("failed to convert header to a str")
