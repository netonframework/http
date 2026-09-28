package neton.http.h2.hpack

import neton.http.StatusCode
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.Method as HttpMethod

/**
 * One HTTP/2 header field as seen by HPACK (`h2::hpack::Header`, `src/hpack/header.rs`).
 *
 * Regular fields carry a [HeaderName] and a [HeaderValue]; the pseudo-header fields are separate variants with typed
 * values, as in the reference. The reference's `BytesStr` values (`:authority`, `:scheme`, `:path`, and the
 * `Protocol` wrapper of `:protocol`) are [String]s here: the decoder only accepts valid UTF-8 for them, exactly like
 * `BytesStr::try_from`.
 *
 * [Value] is the reference's `Header::Field { name: None, .. }`: encoder input for a further value of the previous
 * field's name (what a header map yields for a repeated name). The decoder never produces it.
 *
 * Instances are immutable (apart from [HeaderValue.isSensitive], which belongs to the value). Equality compares the
 * variant and its contents, like the reference's derived `PartialEq`.
 */
sealed class Header {
    /** Variant tag used by the tables; one of the `K_*` constants. */
    internal abstract val kind: Int

    /** The value object: [HeaderValue], [String], [HttpMethod] or [StatusCode]. */
    internal abstract val valueObj: Any

    /** A regular header field. */
    data class Field(val name: HeaderName, val value: HeaderValue) : Header() {
        override val kind: Int get() = K_FIELD
        override val valueObj: Any get() = value
    }

    /**
     * Encoder input only: another value for the name of the preceding [Field] (`Header::Field { name: None }`).
     * Encoding one without a preceding named header is a programming error.
     */
    data class Value(val value: HeaderValue) : Header() {
        override val kind: Int get() = K_VALUE
        override val valueObj: Any get() = value
    }

    /** `:authority`. */
    data class Authority(val value: String) : Header() {
        override val kind: Int get() = K_AUTHORITY
        override val valueObj: Any get() = value
    }

    /** `:method`. */
    data class Method(val value: HttpMethod) : Header() {
        override val kind: Int get() = K_METHOD
        override val valueObj: Any get() = value
    }

    /** `:scheme`. */
    data class Scheme(val value: String) : Header() {
        override val kind: Int get() = K_SCHEME
        override val valueObj: Any get() = value
    }

    /** `:path`. */
    data class Path(val value: String) : Header() {
        override val kind: Int get() = K_PATH
        override val valueObj: Any get() = value
    }

    /** `:protocol` (extended CONNECT, RFC 8441). */
    data class Protocol(val value: String) : Header() {
        override val kind: Int get() = K_PROTOCOL
        override val valueObj: Any get() = value
    }

    /** `:status`. */
    data class Status(val value: StatusCode) : Header() {
        override val kind: Int get() = K_STATUS
        override val valueObj: Any get() = value
    }

    /**
     * The HPACK entry size of this header (RFC 7541 §4.1): 32 + name length + value length, in bytes
     * (`Header::len`). Not defined for [Value], which has no name of its own.
     */
    fun len(): Int = when (this) {
        is Field -> 32 + name.length + value.length
        is Value -> throw IllegalStateException("a nameless header has no entry size")
        else -> entryLen(kind, null, valueObj)
    }

    /**
     * Whether the value is flagged sensitive (`Header::is_sensitive`); only regular fields can be, as in the
     * reference.
     */
    val isSensitive: Boolean
        get() = when (this) {
            is Field -> value.isSensitive
            is Value -> value.isSensitive
            else -> false
        }
}

/**
 * Receives decoded headers (the closure passed to the reference's `Decoder::decode`). Return `true` to continue
 * (`ControlFlow::Continue`) or `false` to stop decoding after this header (`ControlFlow::Break`).
 */
fun interface HeaderSink {
    fun onHeader(header: Header): Boolean
}

internal const val K_FIELD = 0
internal const val K_AUTHORITY = 1
internal const val K_METHOD = 2
internal const val K_SCHEME = 3
internal const val K_PATH = 4
internal const val K_PROTOCOL = 5
internal const val K_STATUS = 6
internal const val K_VALUE = 7

/** Pseudo-header names by kind (`Name::as_slice`); index [K_FIELD] is unused. */
internal val PSEUDO_NAMES: Array<ByteArray> = arrayOf(
    ByteArray(0),
    ":authority".encodeToByteArray(),
    ":method".encodeToByteArray(),
    ":scheme".encodeToByteArray(),
    ":path".encodeToByteArray(),
    ":protocol".encodeToByteArray(),
    ":status".encodeToByteArray(),
)

/** Entry size of a header given as (kind, name, value object); see [Header.len]. */
internal fun entryLen(kind: Int, name: HeaderName?, value: Any): Int = when (kind) {
    K_FIELD -> 32 + name!!.length + (value as HeaderValue).length
    K_METHOD -> 32 + 7 + utf8Length((value as HttpMethod).asStr())
    K_STATUS -> 32 + 7 + 3
    else -> 32 + PSEUDO_NAMES[kind].size + utf8Length(value as String)
}

/** Builds the [Header] for (kind, name, value object). */
internal fun makeHeader(kind: Int, name: HeaderName?, value: Any): Header = when (kind) {
    K_FIELD -> Header.Field(name!!, value as HeaderValue)
    K_AUTHORITY -> Header.Authority(value as String)
    K_METHOD -> Header.Method(value as HttpMethod)
    K_SCHEME -> Header.Scheme(value as String)
    K_PATH -> Header.Path(value as String)
    K_PROTOCOL -> Header.Protocol(value as String)
    K_STATUS -> Header.Status(value as StatusCode)
    else -> throw IllegalArgumentException("kind $kind")
}

/** Number of UTF-8 bytes of [s], without encoding it. */
internal fun utf8Length(s: String): Int {
    var n = 0
    var i = 0
    val len = s.length
    while (i < len) {
        val c = s[i].code
        when {
            c < 0x80 -> n += 1
            c < 0x800 -> n += 2
            c in 0xD800..0xDBFF && i + 1 < len && s[i + 1].code in 0xDC00..0xDFFF -> { n += 4; i++ }
            else -> n += 3
        }
        i++
    }
    return n
}

/** Whether `src[off, off + len)` is valid UTF-8 (`std::str::from_utf8`): no overlongs, surrogates or > U+10FFFF. */
internal fun isValidUtf8(src: ByteArray, off: Int, len: Int): Boolean {
    var i = off
    val end = off + len
    while (i < end) {
        val b0 = src[i].toInt() and 0xff
        if (b0 < 0x80) { i++; continue }
        val need: Int
        val min: Int
        when {
            b0 in 0xC2..0xDF -> { need = 1; min = 0x80 }
            b0 in 0xE0..0xEF -> { need = 2; min = 0x800 }
            b0 in 0xF0..0xF4 -> { need = 3; min = 0x10000 }
            else -> return false
        }
        if (i + need >= end) return false
        var cp = b0 and (0x3F ushr need)
        for (k in 1..need) {
            val b = src[i + k].toInt() and 0xff
            if (b and 0xC0 != 0x80) return false
            cp = (cp shl 6) or (b and 0x3F)
        }
        if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return false
        i += need + 1
    }
    return true
}
