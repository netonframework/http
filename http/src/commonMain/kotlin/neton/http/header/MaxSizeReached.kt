package neton.http.header

import neton.http.HttpException

/**
 * Error returned when the maximum capacity of a [HeaderMap] ([HeaderMap.MAX_SIZE] entries) would be exceeded
 * (`http::header::MaxSizeReached`).
 *
 * The throwing APIs (`insert`, `append`, `entry`, `reserve`, `withCapacity`, ...) throw it; the `try*` variants return
 * it as the failure of a [Result].
 */
class MaxSizeReached : HttpException("max size reached")
