package neton.http.uri

import neton.http.HttpException

/**
 * A builder for [Uri]s (`http::uri::Builder`).
 *
 * Like the reference, the first error from a setter is remembered, later setters are ignored, and the error is
 * thrown by [build]. Kotlin shape: the builder is mutable and each setter returns it; string setters stand in for
 * the reference's `TryInto` arguments. Named `UriBuilder` so it does not clash with the request / response builders.
 */
class UriBuilder() {
    private val parts = UriParts()
    private var error: HttpException? = null

    /** Sets the scheme. */
    fun scheme(scheme: Scheme): UriBuilder {
        if (error == null) parts.scheme = scheme
        return this
    }

    /** Sets the scheme, parsed with [Scheme.parse]; an error is kept for [build]. */
    fun scheme(scheme: String): UriBuilder {
        if (error == null) {
            when (val r = Scheme.parseExact(scheme)) {
                is Scheme -> parts.scheme = r
                else -> error = InvalidUri(r as InvalidUri.ErrorKind)
            }
        }
        return this
    }

    /** Sets the authority. */
    fun authority(authority: Authority): UriBuilder {
        if (error == null) parts.authority = authority
        return this
    }

    /** Sets the authority, parsed with [Authority.parse]; an error is kept for [build]. */
    fun authority(authority: String): UriBuilder {
        if (error == null) {
            when (val r = Authority.create(authority)) {
                is Authority -> parts.authority = r
                else -> error = InvalidUri(r as InvalidUri.ErrorKind)
            }
        }
        return this
    }

    /** Sets the path and query. */
    fun pathAndQuery(pathAndQuery: PathAndQuery): UriBuilder {
        if (error == null) parts.pathAndQuery = pathAndQuery
        return this
    }

    /**
     * Sets the path and query, parsed with [PathAndQuery.parse]; an error is kept for [build]. As in the
     * reference, the empty string is accepted here and means an empty path (shown as `/`).
     */
    fun pathAndQuery(pathAndQuery: String): UriBuilder {
        if (error == null) {
            when (val r = PathAndQuery.parseString(pathAndQuery)) {
                is PathAndQuery -> parts.pathAndQuery = r
                InvalidUri.ErrorKind.Empty -> parts.pathAndQuery = PathAndQuery.EMPTY
                else -> error = InvalidUri(r as InvalidUri.ErrorKind)
            }
        }
        return this
    }

    /**
     * Builds the URI (`build`).
     *
     * @throws InvalidUri the first error of a setter.
     * @throws InvalidUriParts if the parts do not form a valid URI (see [Uri.fromParts]).
     */
    fun build(): Uri {
        error?.let { throw it }
        return Uri.fromParts(UriParts(parts.scheme, parts.authority, parts.pathAndQuery))
    }

    /** Like [build], returning null on error. */
    fun tryBuild(): Uri? {
        if (error != null) return null
        return Uri.tryFromParts(UriParts(parts.scheme, parts.authority, parts.pathAndQuery))
    }

    companion object {
        /** A builder starting from the parts of [uri] (`From<Uri>`). */
        fun from(uri: Uri): UriBuilder {
            val b = UriBuilder()
            val p = uri.intoParts()
            b.parts.scheme = p.scheme
            b.parts.authority = p.authority
            b.parts.pathAndQuery = p.pathAndQuery
            return b
        }
    }
}
