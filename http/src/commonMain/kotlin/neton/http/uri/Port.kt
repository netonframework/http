package neton.http.uri

/**
 * The port component of a URI (`http::uri::Port`).
 *
 * Kotlin shape: the reference is generic over its textual representation (`Port<T>`); here the representation is
 * always the `String` as written (e.g. `"080"` or `"+80"`), and the number is an `Int` in `0..65535` (`u16`).
 * Equality and hashing use the number only, like the reference.
 */
class Port internal constructor(private val port: Int, private val repr: String) {

    /** The port number (`as_u16`). */
    fun asU16(): Int = port

    /** The port as written in the authority (`as_str`). */
    fun asStr(): String = repr

    /** Equality with a port number (`PartialEq<u16>`). */
    infix fun eq(other: Int): Boolean = port == other

    override fun equals(other: Any?): Boolean = other is Port && other.port == port

    override fun hashCode(): Int = port

    /** The port number in decimal (the reference's `Display` prints the number, not the representation). */
    override fun toString(): String = port.toString()

    internal companion object {
        /** Parses a port like Rust's `u16::from_str` (optional `+`, decimal digits, no overflow); null on failure. */
        fun fromStr(s: String): Port? {
            val v = parseU16(s, 0, s.length)
            return if (v < 0) null else Port(v, s)
        }

        /** Rust `u16::from_str` on `s[from, to)`: the value, or -1 when invalid. */
        fun parseU16(s: String, from: Int, to: Int): Int {
            var i = from
            if (i < to && s[i] == '+') i++
            if (i >= to) return -1
            var v = 0
            while (i < to) {
                val d = s[i] - '0'
                if (d !in 0..9) return -1
                v = v * 10 + d
                if (v > 65535) return -1
                i++
            }
            return v
        }
    }
}

/** Equality of a port number with a [Port] (`PartialEq<Port<T>> for u16`). */
infix fun Int.eq(other: Port): Boolean = other.eq(this)
