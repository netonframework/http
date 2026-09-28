package neton.http

/**
 * A version of the HTTP protocol, mirroring `http::Version`.
 *
 * Declaration order gives the reference's ordering (0.9 < 1.0 < 1.1 < 2 < 3). [toString] is the
 * reference's `Debug` form (`"HTTP/1.1"`, `"HTTP/2.0"`, ...).
 */
enum class Version(private val text: String) {
    /** `HTTP/0.9` */
    HTTP_09("HTTP/0.9"),

    /** `HTTP/1.0` */
    HTTP_10("HTTP/1.0"),

    /** `HTTP/1.1` */
    HTTP_11("HTTP/1.1"),

    /** `HTTP/2.0` */
    HTTP_2("HTTP/2.0"),

    /** `HTTP/3.0` */
    HTTP_3("HTTP/3.0");

    override fun toString(): String = text

    companion object {
        /** The default version, [HTTP_11] (the reference's `Default`). */
        val DEFAULT: Version get() = HTTP_11
    }
}
