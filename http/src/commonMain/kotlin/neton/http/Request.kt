package neton.http

import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.uri.Uri

/** The head of a request (`http::request::Parts`). Mutable, like the reference's public fields. */
class RequestParts(
    var method: Method = Method.DEFAULT,
    var uri: Uri = Uri.default(),
    var version: Version = Version.DEFAULT,
    var headers: HeaderMap<HeaderValue> = HeaderMap.new(),
    var extensions: Extensions = Extensions(),
) {
    override fun toString(): String = "Parts { method: $method, uri: $uri, version: $version, headers: $headers }"
}

/** An HTTP request: head + body (`http::Request<T>`). */
class Request<T>(val parts: RequestParts, var body: T) {
    /** A request with default parts: `GET /`, HTTP/1.1, no headers (`Request::new`). */
    constructor(body: T) : this(RequestParts(), body)

    var method: Method by parts::method
    var uri: Uri by parts::uri
    var version: Version by parts::version
    val headers: HeaderMap<HeaderValue> get() = parts.headers
    val extensions: Extensions get() = parts.extensions

    /** `into_parts`. */
    operator fun component1(): RequestParts = parts
    operator fun component2(): T = body

    /** A request with the same head and [f] applied to the body (`map`). */
    fun <U> map(f: (T) -> U): Request<U> = Request(parts, f(body))

    override fun toString(): String = "Request { method: $method, uri: $uri, version: $version, headers: $headers, body: $body }"

    companion object {
        fun builder(): RequestBuilder = RequestBuilder()
        fun get(uri: String): RequestBuilder = RequestBuilder().method(Method.GET).uri(uri)
        fun get(uri: Uri): RequestBuilder = RequestBuilder().method(Method.GET).uri(uri)
        fun put(uri: String): RequestBuilder = RequestBuilder().method(Method.PUT).uri(uri)
        fun post(uri: String): RequestBuilder = RequestBuilder().method(Method.POST).uri(uri)
        fun delete(uri: String): RequestBuilder = RequestBuilder().method(Method.DELETE).uri(uri)
        fun options(uri: String): RequestBuilder = RequestBuilder().method(Method.OPTIONS).uri(uri)
        fun head(uri: String): RequestBuilder = RequestBuilder().method(Method.HEAD).uri(uri)
        fun connect(uri: String): RequestBuilder = RequestBuilder().method(Method.CONNECT).uri(uri)
        fun patch(uri: String): RequestBuilder = RequestBuilder().method(Method.PATCH).uri(uri)
        fun trace(uri: String): RequestBuilder = RequestBuilder().method(Method.TRACE).uri(uri)
        fun <T> fromParts(parts: RequestParts, body: T): Request<T> = Request(parts, body)
    }
}

/**
 * Builds a [Request] (`http::request::Builder`). The first error (an invalid method, URI, header name or value) is
 * remembered, later calls are ignored, and [body] throws it: the reference returns it from `body()`.
 */
class RequestBuilder {
    private var parts: RequestParts? = RequestParts()
    private var error: HttpException? = null
    // The reuse error is created only when it happens: an exception captures a stack trace, far too costly per message.
    private var used = false

    private inline fun and(f: (RequestParts) -> Unit): RequestBuilder {
        val p = parts ?: return this
        try { f(p) } catch (e: HttpException) { error = e; parts = null }
        return this
    }

    fun method(method: Method): RequestBuilder = and { it.method = method }
    fun method(method: String): RequestBuilder = and { it.method = Method.fromStr(method) }
    fun uri(uri: Uri): RequestBuilder = and { it.uri = uri }
    fun uri(uri: String): RequestBuilder = and { it.uri = Uri.parse(uri) }
    fun version(version: Version): RequestBuilder = and { it.version = version }
    /** Appends a header (`header`: the reference appends, it does not replace). */
    fun header(name: HeaderName, value: HeaderValue): RequestBuilder = and { it.headers.append(name, value) }
    fun header(name: String, value: String): RequestBuilder = and { it.headers.append(HeaderName.fromStr(name), HeaderValue.fromStr(value)) }
    fun header(name: HeaderName, value: String): RequestBuilder = and { it.headers.append(name, HeaderValue.fromStr(value)) }
    inline fun <reified E : Any> extension(value: E): RequestBuilder = extensionOf(E::class, value)
    fun <E : Any> extensionOf(type: kotlin.reflect.KClass<E>, value: E): RequestBuilder = and { it.extensions.insert(type, value) }

    /** The method so far, or null after an error (`method_ref`). */
    fun methodRef(): Method? = parts?.method
    fun uriRef(): Uri? = parts?.uri
    fun versionRef(): Version? = parts?.version
    fun headersRef(): HeaderMap<HeaderValue>? = parts?.headers
    fun extensionsRef(): Extensions? = parts?.extensions

    /** Finishes the request. @throws HttpException the first error recorded by the builder. */
    fun <T> body(body: T): Request<T> {
        if (used) throw HttpException("builder already used")
        error?.let { throw it }
        return Request(parts!!, body).also { parts = null; used = true }
    }
}
