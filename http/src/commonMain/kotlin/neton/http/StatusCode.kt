package neton.http

/**
 * Thrown when a value is not a valid status code: not exactly three ASCII digits, a first digit of 0, or a
 * number outside 100..999 (the reference's `InvalidStatusCode`).
 */
class InvalidStatusCode : HttpException("invalid status code")

/**
 * An HTTP status code (`status-code` in RFC 9110), mirroring `http::StatusCode`.
 *
 * Every value in 100..999 is supported. Values in 100..599 are classified by their first digit
 * ([isInformational], [isSuccess], ...); values above 599 are unclassified but allowed for legacy
 * compatibility.
 *
 * All 900 values are preallocated shared instances, so [fromU16], [fromBytes] and [fromStr] never allocate.
 * Equality, hashing and ordering follow the numeric value. The reference's `u16` is an [Int] here (no
 * unsigned boxing); Kotlin cannot overload `==` between a `StatusCode` and a number, so compare with
 * [asU16] (`status.asU16() == 200`) where the reference compares with `u16`.
 */
class StatusCode private constructor(private val code: Int) : Comparable<StatusCode> {

    private val digits: String = code.toString()

    /** The numeric value, in 100..999. */
    fun asU16(): Int = code

    /** The three-digit numeric form, without the reason phrase (for example `"200"`). */
    fun asStr(): String = digits

    /**
     * The standard reason phrase for this code, or null if the code has none.
     *
     * The phrase is for human readers only; HTTP/2 and HTTP/3 do not transmit reason phrases.
     */
    fun canonicalReason(): String? = canonicalReason(code)

    /** Whether the code is in 100..199. */
    fun isInformational(): Boolean = code in 100..199

    /** Whether the code is in 200..299. */
    fun isSuccess(): Boolean = code in 200..299

    /** Whether the code is in 300..399. */
    fun isRedirection(): Boolean = code in 300..399

    /** Whether the code is in 400..499. */
    fun isClientError(): Boolean = code in 400..499

    /** Whether the code is in 500..599. */
    fun isServerError(): Boolean = code in 500..599

    override fun compareTo(other: StatusCode): Int = code.compareTo(other.code)

    override fun equals(other: Any?): Boolean = this === other || (other is StatusCode && code == other.code)

    override fun hashCode(): Int = code

    /** The code and its reason phrase, as the reference's `Display`: `"200 OK"`, or `"<unknown status code>"` as the phrase. */
    override fun toString(): String = "$digits ${canonicalReason() ?: "<unknown status code>"}"

    companion object {
        private val TABLE: Array<StatusCode> = Array(900) { StatusCode(it + 100) }

        /** The default status code, [OK] (the reference's `Default`). */
        val DEFAULT: StatusCode get() = OK

        /** Returns the status code [src]; throws [InvalidStatusCode] unless it is in 100..999. */
        fun fromU16(src: Int): StatusCode = tryFromU16(src) ?: throw InvalidStatusCode()

        /** As [fromU16], but returns null instead of throwing [InvalidStatusCode]. */
        fun tryFromU16(src: Int): StatusCode? = if (src in 100..999) TABLE[src - 100] else null

        /**
         * Parses `length` bytes of [src] starting at [offset] as a status code.
         *
         * The range must be exactly three ASCII digits with a first digit of 1..9; otherwise throws
         * [InvalidStatusCode].
         */
        fun fromBytes(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): StatusCode =
            tryFromBytes(src, offset, length) ?: throw InvalidStatusCode()

        /** As [fromBytes], but returns null instead of throwing [InvalidStatusCode]. */
        fun tryFromBytes(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): StatusCode? {
            if (offset < 0 || length < 0 || offset > src.size - length) {
                throw IndexOutOfBoundsException("offset=$offset length=$length size=${src.size}")
            }
            if (length != 3) return null
            return fromDigits(src[offset].toInt() - '0'.code, src[offset + 1].toInt() - '0'.code, src[offset + 2].toInt() - '0'.code)
        }

        /** Parses [src] as a status code (the reference's `FromStr`); same rules as [fromBytes]. */
        fun fromStr(src: String): StatusCode = tryFromStr(src) ?: throw InvalidStatusCode()

        /** As [fromStr], but returns null instead of throwing [InvalidStatusCode]. */
        fun tryFromStr(src: String): StatusCode? {
            if (src.length != 3) return null
            return fromDigits(src[0].code - '0'.code, src[1].code - '0'.code, src[2].code - '0'.code)
        }

        private fun fromDigits(a: Int, b: Int, c: Int): StatusCode? {
            if (a !in 1..9 || b !in 0..9 || c !in 0..9) return null
            return TABLE[a * 100 + b * 10 + c - 100]
        }

        private fun canonicalReason(code: Int): String? = when (code) {
            100 -> "Continue"
            101 -> "Switching Protocols"
            102 -> "Processing"
            103 -> "Early Hints"
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            203 -> "Non Authoritative Information"
            204 -> "No Content"
            205 -> "Reset Content"
            206 -> "Partial Content"
            207 -> "Multi-Status"
            208 -> "Already Reported"
            226 -> "IM Used"
            300 -> "Multiple Choices"
            301 -> "Moved Permanently"
            302 -> "Found"
            303 -> "See Other"
            304 -> "Not Modified"
            305 -> "Use Proxy"
            307 -> "Temporary Redirect"
            308 -> "Permanent Redirect"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            402 -> "Payment Required"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            406 -> "Not Acceptable"
            407 -> "Proxy Authentication Required"
            408 -> "Request Timeout"
            409 -> "Conflict"
            410 -> "Gone"
            411 -> "Length Required"
            412 -> "Precondition Failed"
            413 -> "Payload Too Large"
            414 -> "URI Too Long"
            415 -> "Unsupported Media Type"
            416 -> "Range Not Satisfiable"
            417 -> "Expectation Failed"
            418 -> "I'm a teapot"
            421 -> "Misdirected Request"
            422 -> "Unprocessable Entity"
            423 -> "Locked"
            424 -> "Failed Dependency"
            425 -> "Too Early"
            426 -> "Upgrade Required"
            428 -> "Precondition Required"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            451 -> "Unavailable For Legal Reasons"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            505 -> "HTTP Version Not Supported"
            506 -> "Variant Also Negotiates"
            507 -> "Insufficient Storage"
            508 -> "Loop Detected"
            510 -> "Not Extended"
            511 -> "Network Authentication Required"
            else -> null
        }

        /** 100 Continue (RFC9110, Section 15.2.1) */
        val CONTINUE: StatusCode = TABLE[0]

        /** 101 Switching Protocols (RFC9110, Section 15.2.2) */
        val SWITCHING_PROTOCOLS: StatusCode = TABLE[1]

        /** 102 Processing (RFC2518, Section 10.1) */
        val PROCESSING: StatusCode = TABLE[2]

        /** 103 Early Hints (RFC8297, Section 2) */
        val EARLY_HINTS: StatusCode = TABLE[3]

        /** 200 OK (RFC9110, Section 15.3.1) */
        val OK: StatusCode = TABLE[100]

        /** 201 Created (RFC9110, Section 15.3.2) */
        val CREATED: StatusCode = TABLE[101]

        /** 202 Accepted (RFC9110, Section 15.3.3) */
        val ACCEPTED: StatusCode = TABLE[102]

        /** 203 Non-Authoritative Information (RFC9110, Section 15.3.4) */
        val NON_AUTHORITATIVE_INFORMATION: StatusCode = TABLE[103]

        /** 204 No Content (RFC9110, Section 15.3.5) */
        val NO_CONTENT: StatusCode = TABLE[104]

        /** 205 Reset Content (RFC9110, Section 15.3.6) */
        val RESET_CONTENT: StatusCode = TABLE[105]

        /** 206 Partial Content (RFC9110, Section 15.3.7) */
        val PARTIAL_CONTENT: StatusCode = TABLE[106]

        /** 207 Multi-Status (RFC4918, Section 11.1) */
        val MULTI_STATUS: StatusCode = TABLE[107]

        /** 208 Already Reported (RFC5842, Section 7.1) */
        val ALREADY_REPORTED: StatusCode = TABLE[108]

        /** 226 IM Used (RFC3229, Section 10.4.1) */
        val IM_USED: StatusCode = TABLE[126]

        /** 300 Multiple Choices (RFC9110, Section 15.4.1) */
        val MULTIPLE_CHOICES: StatusCode = TABLE[200]

        /** 301 Moved Permanently (RFC9110, Section 15.4.2) */
        val MOVED_PERMANENTLY: StatusCode = TABLE[201]

        /** 302 Found (RFC9110, Section 15.4.3) */
        val FOUND: StatusCode = TABLE[202]

        /** 303 See Other (RFC9110, Section 15.4.4) */
        val SEE_OTHER: StatusCode = TABLE[203]

        /** 304 Not Modified (RFC9110, Section 15.4.5) */
        val NOT_MODIFIED: StatusCode = TABLE[204]

        /** 305 Use Proxy (RFC9110, Section 15.4.6) */
        val USE_PROXY: StatusCode = TABLE[205]

        /** 307 Temporary Redirect (RFC9110, Section 15.4.7) */
        val TEMPORARY_REDIRECT: StatusCode = TABLE[207]

        /** 308 Permanent Redirect (RFC9110, Section 15.4.8) */
        val PERMANENT_REDIRECT: StatusCode = TABLE[208]

        /** 400 Bad Request (RFC9110, Section 15.5.1) */
        val BAD_REQUEST: StatusCode = TABLE[300]

        /** 401 Unauthorized (RFC9110, Section 15.5.2) */
        val UNAUTHORIZED: StatusCode = TABLE[301]

        /** 402 Payment Required (RFC9110, Section 15.5.3) */
        val PAYMENT_REQUIRED: StatusCode = TABLE[302]

        /** 403 Forbidden (RFC9110, Section 15.5.4) */
        val FORBIDDEN: StatusCode = TABLE[303]

        /** 404 Not Found (RFC9110, Section 15.5.5) */
        val NOT_FOUND: StatusCode = TABLE[304]

        /** 405 Method Not Allowed (RFC9110, Section 15.5.6) */
        val METHOD_NOT_ALLOWED: StatusCode = TABLE[305]

        /** 406 Not Acceptable (RFC9110, Section 15.5.7) */
        val NOT_ACCEPTABLE: StatusCode = TABLE[306]

        /** 407 Proxy Authentication Required (RFC9110, Section 15.5.8) */
        val PROXY_AUTHENTICATION_REQUIRED: StatusCode = TABLE[307]

        /** 408 Request Timeout (RFC9110, Section 15.5.9) */
        val REQUEST_TIMEOUT: StatusCode = TABLE[308]

        /** 409 Conflict (RFC9110, Section 15.5.10) */
        val CONFLICT: StatusCode = TABLE[309]

        /** 410 Gone (RFC9110, Section 15.5.11) */
        val GONE: StatusCode = TABLE[310]

        /** 411 Length Required (RFC9110, Section 15.5.12) */
        val LENGTH_REQUIRED: StatusCode = TABLE[311]

        /** 412 Precondition Failed (RFC9110, Section 15.5.13) */
        val PRECONDITION_FAILED: StatusCode = TABLE[312]

        /** 413 Payload Too Large (RFC9110, Section 15.5.14) */
        val PAYLOAD_TOO_LARGE: StatusCode = TABLE[313]

        /** 414 URI Too Long (RFC9110, Section 15.5.15) */
        val URI_TOO_LONG: StatusCode = TABLE[314]

        /** 415 Unsupported Media Type (RFC9110, Section 15.5.16) */
        val UNSUPPORTED_MEDIA_TYPE: StatusCode = TABLE[315]

        /** 416 Range Not Satisfiable (RFC9110, Section 15.5.17) */
        val RANGE_NOT_SATISFIABLE: StatusCode = TABLE[316]

        /** 417 Expectation Failed (RFC9110, Section 15.5.18) */
        val EXPECTATION_FAILED: StatusCode = TABLE[317]

        /** 418 I'm a teapot (curiously not registered by IANA, but RFC2324, Section 2.3.2) */
        val IM_A_TEAPOT: StatusCode = TABLE[318]

        /** 421 Misdirected Request (RFC9110, Section 15.5.20) */
        val MISDIRECTED_REQUEST: StatusCode = TABLE[321]

        /** 422 Unprocessable Entity (RFC9110, Section 15.5.21) */
        val UNPROCESSABLE_ENTITY: StatusCode = TABLE[322]

        /** 423 Locked (RFC4918, Section 11.3) */
        val LOCKED: StatusCode = TABLE[323]

        /** 424 Failed Dependency (RFC4918, Section 11.4) */
        val FAILED_DEPENDENCY: StatusCode = TABLE[324]

        /** 425 Too early (RFC8470, Section 5.2) */
        val TOO_EARLY: StatusCode = TABLE[325]

        /** 426 Upgrade Required (RFC9110, Section 15.5.22) */
        val UPGRADE_REQUIRED: StatusCode = TABLE[326]

        /** 428 Precondition Required (RFC6585, Section 3) */
        val PRECONDITION_REQUIRED: StatusCode = TABLE[328]

        /** 429 Too Many Requests (RFC6585, Section 4) */
        val TOO_MANY_REQUESTS: StatusCode = TABLE[329]

        /** 431 Request Header Fields Too Large (RFC6585, Section 5) */
        val REQUEST_HEADER_FIELDS_TOO_LARGE: StatusCode = TABLE[331]

        /** 451 Unavailable For Legal Reasons (RFC7725, Section 3) */
        val UNAVAILABLE_FOR_LEGAL_REASONS: StatusCode = TABLE[351]

        /** 500 Internal Server Error (RFC9110, Section 15.6.1) */
        val INTERNAL_SERVER_ERROR: StatusCode = TABLE[400]

        /** 501 Not Implemented (RFC9110, Section 15.6.2) */
        val NOT_IMPLEMENTED: StatusCode = TABLE[401]

        /** 502 Bad Gateway (RFC9110, Section 15.6.3) */
        val BAD_GATEWAY: StatusCode = TABLE[402]

        /** 503 Service Unavailable (RFC9110, Section 15.6.4) */
        val SERVICE_UNAVAILABLE: StatusCode = TABLE[403]

        /** 504 Gateway Timeout (RFC9110, Section 15.6.5) */
        val GATEWAY_TIMEOUT: StatusCode = TABLE[404]

        /** 505 HTTP Version Not Supported (RFC9110, Section 15.6.6) */
        val HTTP_VERSION_NOT_SUPPORTED: StatusCode = TABLE[405]

        /** 506 Variant Also Negotiates (RFC2295, Section 8.1) */
        val VARIANT_ALSO_NEGOTIATES: StatusCode = TABLE[406]

        /** 507 Insufficient Storage (RFC4918, Section 11.5) */
        val INSUFFICIENT_STORAGE: StatusCode = TABLE[407]

        /** 508 Loop Detected (RFC5842, Section 7.2) */
        val LOOP_DETECTED: StatusCode = TABLE[408]

        /** 510 Not Extended (RFC2774, Section 7) */
        val NOT_EXTENDED: StatusCode = TABLE[410]

        /** 511 Network Authentication Required (RFC6585, Section 6) */
        val NETWORK_AUTHENTICATION_REQUIRED: StatusCode = TABLE[411]
    }
}
