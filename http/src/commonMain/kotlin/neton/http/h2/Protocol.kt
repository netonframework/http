package neton.http.h2

/**
 * The `:protocol` pseudo-header of an extended CONNECT request (`h2::ext::Protocol`, `src/ext.rs`; RFC 8441). A
 * client puts it in a CONNECT request's extensions to send it; a server finds it there on a received request.
 */
class Protocol(private val value: String) {
    /** The protocol name (`as_str`). */
    fun asStr(): String = value

    override fun equals(other: Any?): Boolean = other is Protocol && other.value == value

    override fun hashCode(): Int = value.hashCode()

    /** The reference's `Debug`: the quoted value. */
    override fun toString(): String = "\"$value\""

    companion object {
        /** `Protocol::from_static`. */
        fun fromStatic(value: String): Protocol = Protocol(value)
    }
}
