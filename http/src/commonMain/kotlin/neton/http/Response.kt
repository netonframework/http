package neton.http

import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue

/** The head of a response (`http::response::Parts`). */
class ResponseParts(
    var status: StatusCode = StatusCode.DEFAULT,
    var version: Version = Version.DEFAULT,
    var headers: HeaderMap<HeaderValue> = HeaderMap.new(),
    var extensions: Extensions = Extensions(),
) {
    override fun toString(): String = "Parts { status: $status, version: $version, headers: $headers }"
}

/** An HTTP response: head + body (`http::Response<T>`). */
class Response<T>(val parts: ResponseParts, var body: T) {
    /** `200 OK`, HTTP/1.1, no headers (`Response::new`). */
    constructor(body: T) : this(ResponseParts(), body)

    var status: StatusCode by parts::status
    var version: Version by parts::version
    val headers: HeaderMap<HeaderValue> get() = parts.headers
    val extensions: Extensions get() = parts.extensions

    operator fun component1(): ResponseParts = parts
    operator fun component2(): T = body

    fun <U> map(f: (T) -> U): Response<U> = Response(parts, f(body))

    override fun toString(): String = "Response { status: $status, version: $version, headers: $headers, body: $body }"

    companion object {
        fun builder(): ResponseBuilder = ResponseBuilder()
        fun <T> fromParts(parts: ResponseParts, body: T): Response<T> = Response(parts, body)
    }
}

/** Builds a [Response] (`http::response::Builder`); the first error is thrown by [body]. */
class ResponseBuilder {
    private var parts: ResponseParts? = ResponseParts()
    private var error: HttpException? = null

    private inline fun and(f: (ResponseParts) -> Unit): ResponseBuilder {
        val p = parts ?: return this
        try { f(p) } catch (e: HttpException) { error = e; parts = null }
        return this
    }

    fun status(status: StatusCode): ResponseBuilder = and { it.status = status }
    fun status(code: Int): ResponseBuilder = and { it.status = StatusCode.fromU16(code) }
    fun version(version: Version): ResponseBuilder = and { it.version = version }
    fun header(name: HeaderName, value: HeaderValue): ResponseBuilder = and { it.headers.append(name, value) }
    fun header(name: String, value: String): ResponseBuilder = and { it.headers.append(HeaderName.fromStr(name), HeaderValue.fromStr(value)) }
    fun header(name: HeaderName, value: String): ResponseBuilder = and { it.headers.append(name, HeaderValue.fromStr(value)) }
    inline fun <reified E : Any> extension(value: E): ResponseBuilder = extensionOf(E::class, value)
    fun <E : Any> extensionOf(type: kotlin.reflect.KClass<E>, value: E): ResponseBuilder = and { it.extensions.insert(type, value) }

    fun statusRef(): StatusCode? = parts?.status
    fun versionRef(): Version? = parts?.version
    fun headersRef(): HeaderMap<HeaderValue>? = parts?.headers
    fun extensionsRef(): Extensions? = parts?.extensions

    /** @throws HttpException the first error recorded by the builder. */
    fun <T> body(body: T): Response<T> {
        error?.let { throw it }
        return Response(parts!!, body).also { parts = null; error = HttpException("builder already used") }
    }
}
