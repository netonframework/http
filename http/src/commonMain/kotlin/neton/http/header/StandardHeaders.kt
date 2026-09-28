package neton.http.header

/** Size of the open-addressing table used to find a standard name by hash (power of two, > 3x the name count). */
internal const val STANDARD_SLOT_MASK: Int = 255

/**
 * The standard header name constants of the reference (`http::header::ACCEPT`, ...; the same 81 names), exposed
 * through [HeaderName]'s companion object as `HeaderName.ACCEPT`, `HeaderName.CONTENT_TYPE`, and so on.
 *
 * Each constant is a unique [HeaderName] instance; parsing a standard name in any case returns that instance. The
 * lookup table built here lets the parser find a standard name by hash and a byte comparison, without allocating.
 */
sealed class StandardHeaderNames {
    // Must be declared before the constants: each std(...) call registers its constant here, in declaration order.
    private val registry = ArrayList<HeaderName>(96)

    private fun std(name: String): HeaderName {
        val h = HeaderName(name.encodeToByteArray(), hashLowercase(name), registry.size, name)
        registry.add(h)
        return h
    }

    /** Advertises which content types the client is able to understand. */
    val ACCEPT: HeaderName = std("accept")

    /** Advertises which character set the client is able to understand. */
    val ACCEPT_CHARSET: HeaderName = std("accept-charset")

    /** Advertises which content encoding the client is able to understand. */
    val ACCEPT_ENCODING: HeaderName = std("accept-encoding")

    /** Advertises which languages the client is able to understand. */
    val ACCEPT_LANGUAGE: HeaderName = std("accept-language")

    /** Marker used by the server to advertise partial request support. */
    val ACCEPT_RANGES: HeaderName = std("accept-ranges")

    /** Preflight response indicating if the response to the request can be exposed to the page. */
    val ACCESS_CONTROL_ALLOW_CREDENTIALS: HeaderName = std("access-control-allow-credentials")

    /** Preflight response indicating permitted HTTP headers. */
    val ACCESS_CONTROL_ALLOW_HEADERS: HeaderName = std("access-control-allow-headers")

    /** Preflight header response indicating permitted access methods. */
    val ACCESS_CONTROL_ALLOW_METHODS: HeaderName = std("access-control-allow-methods")

    /** Indicates whether the response can be shared with resources with the given origin. */
    val ACCESS_CONTROL_ALLOW_ORIGIN: HeaderName = std("access-control-allow-origin")

    /** Indicates which headers can be exposed as part of the response by listing their names. */
    val ACCESS_CONTROL_EXPOSE_HEADERS: HeaderName = std("access-control-expose-headers")

    /** Indicates how long the results of a preflight request can be cached. */
    val ACCESS_CONTROL_MAX_AGE: HeaderName = std("access-control-max-age")

    /** Informs the server which HTTP headers will be used when an actual request is made. */
    val ACCESS_CONTROL_REQUEST_HEADERS: HeaderName = std("access-control-request-headers")

    /** Informs the server know which HTTP method will be used when the actual request is made. */
    val ACCESS_CONTROL_REQUEST_METHOD: HeaderName = std("access-control-request-method")

    /** Indicates the time in seconds the object has been in a proxy cache. */
    val AGE: HeaderName = std("age")

    /** Lists the set of methods support by a resource. */
    val ALLOW: HeaderName = std("allow")

    /** Advertises the availability of alternate services to clients. */
    val ALT_SVC: HeaderName = std("alt-svc")

    /** Contains the credentials to authenticate a user agent with a server. */
    val AUTHORIZATION: HeaderName = std("authorization")

    /** Specifies directives for caching mechanisms in both requests and responses. */
    val CACHE_CONTROL: HeaderName = std("cache-control")

    /** Indicates how caches have handled a response and its corresponding request. */
    val CACHE_STATUS: HeaderName = std("cache-status")

    /** Specifies directives that allow origin servers to control the behavior of CDN caches interposed between them and clients separately from other caches that might handle the response. */
    val CDN_CACHE_CONTROL: HeaderName = std("cdn-cache-control")

    /** Controls whether or not the network connection stays open after the current transaction finishes. */
    val CONNECTION: HeaderName = std("connection")

    /** Indicates if the content is expected to be displayed inline. */
    val CONTENT_DISPOSITION: HeaderName = std("content-disposition")

    /** Used to compress the media-type. */
    val CONTENT_ENCODING: HeaderName = std("content-encoding")

    /** Used to describe the languages intended for the audience. */
    val CONTENT_LANGUAGE: HeaderName = std("content-language")

    /** Indicates the size of the entity-body. */
    val CONTENT_LENGTH: HeaderName = std("content-length")

    /** Indicates an alternate location for the returned data. */
    val CONTENT_LOCATION: HeaderName = std("content-location")

    /** Indicates where in a full body message a partial message belongs. */
    val CONTENT_RANGE: HeaderName = std("content-range")

    /** Allows controlling resources the user agent is allowed to load for a given page. */
    val CONTENT_SECURITY_POLICY: HeaderName = std("content-security-policy")

    /** Allows experimenting with policies by monitoring their effects. */
    val CONTENT_SECURITY_POLICY_REPORT_ONLY: HeaderName = std("content-security-policy-report-only")

    /** Used to indicate the media type of the resource. */
    val CONTENT_TYPE: HeaderName = std("content-type")

    /** Contains stored HTTP cookies previously sent by the server with the Set-Cookie header. */
    val COOKIE: HeaderName = std("cookie")

    /** Indicates the client's tracking preference. */
    val DNT: HeaderName = std("dnt")

    /** Contains the date and time at which the message was originated. */
    val DATE: HeaderName = std("date")

    /** Identifier for a specific version of a resource. */
    val ETAG: HeaderName = std("etag")

    /** Indicates expectations that need to be fulfilled by the server in order to properly handle the request. */
    val EXPECT: HeaderName = std("expect")

    /** Contains the date/time after which the response is considered stale. */
    val EXPIRES: HeaderName = std("expires")

    /** Contains information from the client-facing side of proxy servers that is altered or lost when a proxy is involved in the path of the request. */
    val FORWARDED: HeaderName = std("forwarded")

    /** Contains an Internet email address for a human user who controls the requesting user agent. */
    val FROM: HeaderName = std("from")

    /** Specifies the domain name of the server and (optionally) the TCP port number on which the server is listening. */
    val HOST: HeaderName = std("host")

    /** Makes a request conditional based on the E-Tag. */
    val IF_MATCH: HeaderName = std("if-match")

    /** Makes a request conditional based on the modification date. */
    val IF_MODIFIED_SINCE: HeaderName = std("if-modified-since")

    /** Makes a request conditional based on the E-Tag. */
    val IF_NONE_MATCH: HeaderName = std("if-none-match")

    /** Makes a request conditional based on range. */
    val IF_RANGE: HeaderName = std("if-range")

    /** Makes the request conditional based on the last modification date. */
    val IF_UNMODIFIED_SINCE: HeaderName = std("if-unmodified-since")

    /** The Last-Modified header contains the date and time when the origin believes the resource was last modified. */
    val LAST_MODIFIED: HeaderName = std("last-modified")

    /** Allows the server to point an interested client to another resource containing metadata about the requested resource. */
    val LINK: HeaderName = std("link")

    /** Indicates the URL to redirect a page to. */
    val LOCATION: HeaderName = std("location")

    /** Indicates the max number of intermediaries the request should be sent through. */
    val MAX_FORWARDS: HeaderName = std("max-forwards")

    /** Indicates where a fetch originates from. */
    val ORIGIN: HeaderName = std("origin")

    /** HTTP/1.0 header usually used for backwards compatibility. */
    val PRAGMA: HeaderName = std("pragma")

    /** Defines the authentication method that should be used to gain access to a proxy. */
    val PROXY_AUTHENTICATE: HeaderName = std("proxy-authenticate")

    /** Contains the credentials to authenticate a user agent to a proxy server. */
    val PROXY_AUTHORIZATION: HeaderName = std("proxy-authorization")

    /** Associates a specific cryptographic public key with a certain server. */
    val PUBLIC_KEY_PINS: HeaderName = std("public-key-pins")

    /** Sends reports of pinning violation to the report-uri specified in the header. */
    val PUBLIC_KEY_PINS_REPORT_ONLY: HeaderName = std("public-key-pins-report-only")

    /** Indicates the part of a document that the server should return. */
    val RANGE: HeaderName = std("range")

    /** Contains the address of the previous web page from which a link to the currently requested page was followed. */
    val REFERER: HeaderName = std("referer")

    /** Governs which referrer information should be included with requests made. */
    val REFERRER_POLICY: HeaderName = std("referrer-policy")

    /** Informs the web browser that the current page or frame should be refreshed. */
    val REFRESH: HeaderName = std("refresh")

    /** The Retry-After response HTTP header indicates how long the user agent should wait before making a follow-up request. */
    val RETRY_AFTER: HeaderName = std("retry-after")

    /** The |Sec-WebSocket-Accept| header field is used in the WebSocket opening handshake. */
    val SEC_WEBSOCKET_ACCEPT: HeaderName = std("sec-websocket-accept")

    /** The |Sec-WebSocket-Extensions| header field is used in the WebSocket opening handshake. */
    val SEC_WEBSOCKET_EXTENSIONS: HeaderName = std("sec-websocket-extensions")

    /** The |Sec-WebSocket-Key| header field is used in the WebSocket opening handshake. */
    val SEC_WEBSOCKET_KEY: HeaderName = std("sec-websocket-key")

    /** The |Sec-WebSocket-Protocol| header field is used in the WebSocket opening handshake. */
    val SEC_WEBSOCKET_PROTOCOL: HeaderName = std("sec-websocket-protocol")

    /** The |Sec-WebSocket-Version| header field is used in the WebSocket opening handshake. */
    val SEC_WEBSOCKET_VERSION: HeaderName = std("sec-websocket-version")

    /** Contains information about the software used by the origin server to handle the request. */
    val SERVER: HeaderName = std("server")

    /** Used to send cookies from the server to the user agent. */
    val SET_COOKIE: HeaderName = std("set-cookie")

    /** Tells the client to communicate with HTTPS instead of using HTTP. */
    val STRICT_TRANSPORT_SECURITY: HeaderName = std("strict-transport-security")

    /** Informs the server of transfer encodings willing to be accepted as part of the response. */
    val TE: HeaderName = std("te")

    /** Allows the sender to include additional fields at the end of chunked messages. */
    val TRAILER: HeaderName = std("trailer")

    /** Specifies the form of encoding used to safely transfer the entity to the client. */
    val TRANSFER_ENCODING: HeaderName = std("transfer-encoding")

    /** Contains a string that allows identifying the requesting client's software. */
    val USER_AGENT: HeaderName = std("user-agent")

    /** Used as part of the exchange to upgrade the protocol. */
    val UPGRADE: HeaderName = std("upgrade")

    /** Sends a signal to the server expressing the client’s preference for an encrypted and authenticated response. */
    val UPGRADE_INSECURE_REQUESTS: HeaderName = std("upgrade-insecure-requests")

    /** Determines how to match future requests with cached responses. */
    val VARY: HeaderName = std("vary")

    /** Added by proxies to track routing. */
    val VIA: HeaderName = std("via")

    /** General HTTP header contains information about possible problems with the status of the message. */
    val WARNING: HeaderName = std("warning")

    /** Defines the authentication method that should be used to gain access to a resource. */
    val WWW_AUTHENTICATE: HeaderName = std("www-authenticate")

    /** Marker used by the server to indicate that the MIME types advertised in the `content-type` headers should not be changed and be followed. */
    val X_CONTENT_TYPE_OPTIONS: HeaderName = std("x-content-type-options")

    /** Controls DNS prefetching. */
    val X_DNS_PREFETCH_CONTROL: HeaderName = std("x-dns-prefetch-control")

    /** Indicates whether or not a browser should be allowed to render a page in a frame. */
    val X_FRAME_OPTIONS: HeaderName = std("x-frame-options")

    /** Stop pages from loading when an XSS attack is detected. */
    val X_XSS_PROTECTION: HeaderName = std("x-xss-protection")

    // ===== lookup table (must stay after the constants) =====

    /** Every standard name, indexed by [HeaderName.standardIndex]. */
    internal val standardAll: Array<HeaderName> = registry.toTypedArray()

    /** Length of the longest standard name; longer inputs skip the standard lookup. */
    internal val standardMaxLength: Int = standardAll.maxOf { it.length }

    /**
     * The standard names by length (index = length; empty where none): what [HeaderName.tryFromBytes] compares
     * against first, as hyper's `StandardHeader::from_bytes` matches on the length before the bytes.
     */
    internal val standardByLength: Array<Array<HeaderName>> =
        Array(standardMaxLength + 1) { len -> standardAll.filter { it.length == len }.toTypedArray() }

    /**
     * Per length, the [chunkAt] chunks of each name in [standardByLength] (name j, chunk k at `j * chunkCount(len) + k`),
     * and the matching case masks: 0x20 on letters, so `(input or mask) == chunk` accepts either case for letters
     * and only the exact byte elsewhere (standard names are lowercase letters and `-`).
     */
    internal val standardChunks: Array<LongArray> = Array(standardMaxLength + 1) { len -> chunkTable(len, masks = false) }
    internal val standardMasks: Array<LongArray> = Array(standardMaxLength + 1) { len -> chunkTable(len, masks = true) }

    private fun chunkTable(len: Int, masks: Boolean): LongArray {
        val names = standardByLength[len]
        val n = chunkCount(len)
        val out = LongArray(names.size * n)
        for (j in names.indices) {
            val b = names[j].bytes
            val src = if (!masks) b else ByteArray(len) { if (b[it] in 'a'.code.toByte()..'z'.code.toByte()) 0x20 else 0 }
            for (k in 0 until n) out[j * n + k] = chunkAt(src, 0, len, k, n)
        }
        return out
    }

    /** Open-addressing slots (linear probing) holding `standardIndex + 1`, 0 meaning empty. */
    internal val standardSlots: ShortArray = ShortArray(STANDARD_SLOT_MASK + 1).also { slots ->
        for (h in standardAll) {
            var slot = h.hash and STANDARD_SLOT_MASK
            while (slots[slot].toInt() != 0) slot = (slot + 1) and STANDARD_SLOT_MASK
            slots[slot] = (h.standardIndex + 1).toShort()
        }
    }
}
