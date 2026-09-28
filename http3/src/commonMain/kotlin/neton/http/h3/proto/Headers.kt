package neton.http.h3.proto

import neton.http.Method
import neton.http.StatusCode
import neton.http.h2.frame.parseU64
import neton.http.h3.Code
import neton.http.h3.H3Exception
import neton.http.h3.qpack.Decoder
import neton.http.h3.qpack.Encoder
import neton.http.h3.qpack.FieldSink
import neton.http.h3.qpack.HeaderField
import neton.http.h3.qpack.StaticTable
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.uri.Authority
import neton.http.uri.PathAndQuery
import neton.http.uri.Scheme
import neton.http.uri.Uri
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes

// HTTP/3 message headers (RFC 9114 §4.2–4.3), `h3::proto::headers`.

/** The `:protocol` pseudo-header of extended CONNECT (RFC 9220), `h3::ext::Protocol`. */
enum class Protocol(val value: String) {
    WEB_TRANSPORT("webtransport"),
    CONNECT_UDP("connect-udp");

    companion object {
        /** The protocol named [s], or null (`FromStr`: only the two protocols the reference knows). */
        fun fromStr(s: String): Protocol? = entries.firstOrNull { it.value == s }
    }
}

/**
 * The pseudo-header fields of a message (`Pseudo`): [method], [scheme], [authority], [path] and [protocol] for a
 * request, [status] for a response; [len] counts those present.
 */
class Pseudo internal constructor(
    var method: Method? = null,
    var scheme: Scheme? = null,
    var authority: Authority? = null,
    var path: PathAndQuery? = null,
    var status: StatusCode? = null,
    var protocol: Protocol? = null,
    var len: Int = 0,
) {
    override fun equals(other: Any?): Boolean = other is Pseudo && other.method == method && other.scheme == scheme &&
        other.authority == authority && other.path == path && other.status == status && other.protocol == protocol &&
        other.len == len

    override fun hashCode(): Int = listOf(method, scheme, authority, path, status, protocol).hashCode() * 31 + len

    override fun toString(): String =
        "Pseudo(method=$method, scheme=$scheme, authority=$authority, path=$path, status=$status, protocol=$protocol)"

    companion object {
        /**
         * The pseudo-headers of a request (`Pseudo::request`): `:path` is `/` when the URI has none, `:scheme`
         * defaults to https, and `:protocol` is kept for CONNECT only.
         */
        internal fun request(method: Method, uri: Uri, protocol: Protocol?): Pseudo {
            val parts = uri.intoParts()
            val path = parts.pathAndQuery ?: PathAndQuery.fromStatic("/")
            val p = if (method == Method.CONNECT) protocol else null
            val len = 3 + (if (parts.authority != null) 1 else 0) + (if (p != null) 1 else 0)
            return Pseudo(method, parts.scheme ?: Scheme.HTTPS, parts.authority, path, null, p, len)
        }

        /** The pseudo-headers of a response (`Pseudo::response`). */
        internal fun response(status: StatusCode): Pseudo = Pseudo(status = status, len = 1)
    }
}

/** The parts of a request head (`into_request_parts`). */
data class RequestParts(val method: Method, val uri: Uri, val protocol: Protocol?, val headers: HeaderMap<HeaderValue>)

/**
 * The header section of an HTTP/3 message (`Header`): pseudo-header fields and regular fields.
 *
 * Decoding ([decode], [fromFields]) validates as the reference does (field names must be valid lowercase names, values
 * valid field values, pseudo-headers known and well-formed) and ⚖️ adds four checks the reference lacks, the same as
 * the HTTP/2 decoder of `neton.http.h2` (SPEC §5, for safety), each making the message malformed (H3_MESSAGE_ERROR,
 * RFC 9114 §4.1.2):
 * 1. a repeated pseudo-header ([HeaderError.DuplicatePseudo], RFC 9114 §4.3);
 * 2. a pseudo-header after a regular field ([HeaderError.PseudoAfterRegular], §4.3);
 * 3. a connection-specific field: connection, keep-alive, proxy-connection, transfer-encoding, upgrade, or `te` with
 *    a value other than `trailers` ([HeaderError.ConnectionSpecific], §4.2);
 * 4. a `content-length` that is not a decimal number of 1 to 19 digits, or several different ones
 *    ([HeaderError.InvalidContentLength], RFC 9110 §8.6); the value is kept in [contentLength] for the request stream
 *    to check the DATA length against. (The HTTP/2 decoder reads only the first value and takes an empty one as 0.)
 */
class Header internal constructor(val pseudo: Pseudo, val fields: HeaderMap<HeaderValue>) {
    /** The parsed `content-length` of a decoded section, or null when absent (⚖️ check 4). */
    var contentLength: ULong? = null
        internal set

    /** Whether any pseudo-header is present (they must not appear in trailers, RFC 9114 §4.3). */
    val hasPseudo: Boolean get() = pseudo.len > 0

    /** Pseudo-headers and field lines (`len`). */
    val len: Int get() = pseudo.len + fields.len()

    /**
     * The request head (`into_request_parts`): the URI from `:scheme`, `:path` and the authority, which is `:authority`
     * or `host` (RFC 9114 §4.3.1: one of them required, equal when both).
     * @throws HeaderException `MissingAuthority`, `ContradictedAuthority`, `MissingMethod` or `InvalidRequest`.
     */
    fun intoRequestParts(): RequestParts {
        val uri = Uri.builder()
        pseudo.path?.let { uri.pathAndQuery(it) }
        pseudo.scheme?.let { uri.scheme(it) }
        val a = pseudo.authority
        val h = fields[HeaderName.HOST]
        when {
            a == null && h == null -> throw HeaderException(HeaderError.MissingAuthority)
            a != null && h == null -> uri.authority(a)
            a != null && h != null && !h.contentEquals(a.asStr()) -> throw HeaderException(HeaderError.ContradictedAuthority)
            else -> {
                // RFC 9114 §4.3.1: an empty host fails here, when the URI is built.
                val host = Authority.tryFromBytes(h!!.asBytes())
                    ?: throw HeaderException(HeaderError.InvalidRequest("invalid host: $h"))
                uri.authority(host)
            }
        }
        val method = pseudo.method ?: throw HeaderException(HeaderError.MissingMethod)
        val built = try {
            uri.build()
        } catch (e: Exception) {
            throw HeaderException(HeaderError.InvalidRequest(e.message ?: "invalid request"))
        }
        return RequestParts(method, built, pseudo.protocol, fields)
    }

    /**
     * The response head (`into_response_parts`).
     * @throws HeaderException `MissingStatus` (RFC 9114 §4.3.2).
     */
    fun intoResponseParts(): Pair<StatusCode, HeaderMap<HeaderValue>> =
        (pseudo.status ?: throw HeaderException(HeaderError.MissingStatus)) to fields

    /** The regular fields (`into_fields`). */
    fun intoFields(): HeaderMap<HeaderValue> = fields

    /**
     * The field lines in encoding order (the reference's `HeaderIter`): pseudo-headers first (method, scheme,
     * authority, path, status, protocol), then the fields, repeated names once per value. Allocates; for tests and
     * diagnostics ([encode] does not use it).
     */
    fun fieldLines(): List<HeaderField> {
        val out = ArrayList<HeaderField>(len)
        pseudo.method?.let { out.add(HeaderField(":method", it.asStr())) }
        pseudo.scheme?.let { out.add(HeaderField(":scheme", it.asStr())) }
        pseudo.authority?.let { out.add(HeaderField(":authority", it.asStr())) }
        pseudo.path?.let { out.add(HeaderField(":path", it.asStr())) }
        pseudo.status?.let { out.add(HeaderField(":status", it.asStr())) }
        pseudo.protocol?.let { out.add(HeaderField(":protocol", it.value)) }
        fields.forEach { name, value -> out.add(HeaderField(name.toByteArray(), value.asBytes())) }
        return out
    }

    /**
     * QPACK-encodes the section into [block] (the reference's `encode_stateless` of a `Header`): static table and
     * literals only. Returns the decoded size (name + value + 32 per line), to compare with the peer's
     * SETTINGS_MAX_FIELD_SECTION_SIZE.
     */
    fun encode(encoder: Encoder, block: Buffer): Long {
        encoder.beginSection(block)
        var size = 0L
        pseudo.method?.let { size += encodePseudo(encoder, block, ":method", it.asStr()) }
        pseudo.scheme?.let { size += encodePseudo(encoder, block, ":scheme", it.asStr()) }
        pseudo.authority?.let { size += encodePseudo(encoder, block, ":authority", it.asStr()) }
        pseudo.path?.let { size += encodePseudo(encoder, block, ":path", it.asStr()) }
        pseudo.status?.let { size += encodePseudo(encoder, block, ":status", it.asStr()) }
        pseudo.protocol?.let {
            val v = it.value.encodeToByteArray()
            encoder.literal(PROTOCOL, 0, PROTOCOL.size, v, 0, v.size, block)
            size += PROTOCOL.size + v.size + 32
        }
        fields.forEach { name, value ->
            val index = StaticTable.find(name, value)
            if (index >= 0) {
                encoder.indexedStatic(index, block)
            } else {
                val v = value.copyInto(encoder.valueScratch(value.length))
                val nameIndex = StaticTable.findName(name)
                if (nameIndex >= 0) {
                    encoder.literalWithStaticName(nameIndex, v, 0, value.length, block)
                } else {
                    val n = name.copyInto(encoder.nameScratch(name.length))
                    encoder.literal(n, 0, name.length, v, 0, value.length, block)
                }
            }
            size += name.length.toLong() + value.length + 32
        }
        return size
    }

    private fun encodePseudo(encoder: Encoder, block: Buffer, name: String, value: String): Long {
        val index = StaticTable.findPseudo(name, value)
        val bytes = value.encodeToByteArray()
        if (index >= 0) {
            encoder.indexedStatic(index, block)
        } else {
            encoder.literalWithStaticName(StaticTable.findPseudoName(name), bytes, 0, bytes.size, block)
        }
        return name.length.toLong() + bytes.size + 32
    }

    override fun equals(other: Any?): Boolean = other is Header && other.pseudo == pseudo && other.fields == fields
    override fun hashCode(): Int = pseudo.hashCode()
    override fun toString(): String = "Header($pseudo, $fields)"

    companion object {
        private val PROTOCOL = ":protocol".encodeToByteArray()

        /**
         * The header section of a request (`Header::request`).
         * @throws HeaderException `MissingAuthority` when neither the URI nor a `host` field has an authority,
         * `ContradictedAuthority` when they differ.
         */
        fun request(method: Method, uri: Uri, fields: HeaderMap<HeaderValue>, protocol: Protocol? = null): Header {
            val a = uri.authority
            val h = fields[HeaderName.HOST]
            if (a == null && h == null) throw HeaderException(HeaderError.MissingAuthority)
            if (a != null && h != null && !h.contentEquals(a.asStr())) throw HeaderException(HeaderError.ContradictedAuthority)
            return Header(Pseudo.request(method, uri, protocol), fields)
        }

        /** The header section of a response (`Header::response`). */
        fun response(status: StatusCode, fields: HeaderMap<HeaderValue>): Header = Header(Pseudo.response(status), fields)

        /** A trailer section (`Header::trailer`): no pseudo-headers (RFC 9114 §4.3). */
        fun trailer(fields: HeaderMap<HeaderValue>): Header = Header(Pseudo(), fields)

        /**
         * Validates decoded field lines into a header section (`TryFrom<Vec<HeaderField>>`).
         * @throws HeaderException
         */
        fun fromFields(fields: List<HeaderField>): Header {
            val b = HeaderBuilder(fields.size)
            for (f in fields) b.onField(f.name, 0, f.name.size, f.value, 0, f.value.size)
            return b.build()
        }

        /**
         * QPACK-decodes the section `src[off, off + len)` with [decoder] (its limits apply) and validates it, field
         * line by field line: decoding stops at the first invalid line.
         * @throws neton.http.h3.qpack.DecoderException for a QPACK error or a limit.
         * @throws HeaderException for an invalid or malformed section.
         */
        fun decode(src: ByteArray, off: Int, len: Int, decoder: Decoder): Header {
            val b = HeaderBuilder(8)
            decoder.decode(src, off, len, b)
            return b.build()
        }

        /** [decode] of a HEADERS frame payload. */
        fun decode(block: Bytes, decoder: Decoder): Header {
            val b = Buffer.wrap(block)
            return decode(b.backingArray(), b.readerIndex(), b.readableBytes, decoder)
        }
    }
}

/** Builds and validates a [Header] from field lines as the QPACK decoder produces them (`Field::parse`). */
internal class HeaderBuilder(capacity: Int) : FieldSink {
    private val pseudo = Pseudo()
    private val fields = HeaderMap.withCapacity<HeaderValue>(capacity)
    private var regularSeen = false
    private var contentLength: ULong? = null

    override fun onField(name: ByteArray, nameOff: Int, nameLen: Int, value: ByteArray, valueOff: Int, valueLen: Int) {
        if (nameLen == 0) throw HeaderException(HeaderError.InvalidHeaderName("name is empty"))
        if (name[nameOff] != ':'.code.toByte()) {
            // RFC 9114 §4.2, §10.3: invalid or uppercase names and invalid values are malformed.
            val n = HeaderName.tryFromLowercase(name, nameOff, nameLen)
                ?: throw HeaderException(HeaderError.InvalidHeaderName(show(name, nameOff, nameLen)))
            val v = HeaderValue.tryFromBytes(value, valueOff, valueLen)
                ?: throw HeaderException(HeaderError.InvalidHeaderValue(show(name, nameOff, nameLen)))
            checkRegular(n, v)
            regularSeen = true
            if (fields.tryAppend(n, v).isFailure) throw HeaderException(HeaderError.InvalidHeaderName("too many fields"))
            return
        }
        // ⚖️ RFC 9114 §4.3: pseudo-headers come first.
        if (regularSeen) throw HeaderException(HeaderError.PseudoAfterRegular(show(name, nameOff, nameLen)))
        val key = show(name, nameOff, nameLen)
        val invalidValue = { HeaderException(HeaderError.InvalidHeaderValue(key)) }
        val p = pseudo
        val alreadySet: Boolean
        when (key) {
            ":scheme" -> {
                alreadySet = p.scheme != null
                p.scheme = Scheme.tryFromBytes(value, valueOff, valueLen) ?: throw invalidValue()
            }
            ":authority" -> {
                alreadySet = p.authority != null
                // RFC 9114 §4.3.1: not empty (the authority parser refuses an empty one).
                p.authority = Authority.tryFromBytes(value, valueOff, valueLen) ?: throw invalidValue()
            }
            ":path" -> {
                alreadySet = p.path != null
                p.path = PathAndQuery.tryFromBytes(value, valueOff, valueLen) ?: throw invalidValue()
            }
            ":method" -> {
                alreadySet = p.method != null
                p.method = Method.tryFromBytes(value, valueOff, valueLen) ?: throw invalidValue()
            }
            ":status" -> {
                alreadySet = p.status != null
                p.status = StatusCode.tryFromBytes(value, valueOff, valueLen) ?: throw invalidValue()
            }
            ":protocol" -> {
                alreadySet = p.protocol != null
                p.protocol = Protocol.fromStr(value.decodeToString(valueOff, valueOff + valueLen)) ?: throw invalidValue()
            }
            else -> throw HeaderException(HeaderError.InvalidHeaderName(key))
        }
        // ⚖️ RFC 9114 §4.3: each pseudo-header at most once (the reference keeps the last).
        if (alreadySet) throw HeaderException(HeaderError.DuplicatePseudo(key))
        p.len++
    }

    /** ⚖️ Checks 3 and 4 (connection-specific fields, content-length), as in the HTTP/2 decoder. */
    private fun checkRegular(n: HeaderName, v: HeaderValue) {
        if (n == HeaderName.CONNECTION || n == HeaderName.TRANSFER_ENCODING || n == HeaderName.UPGRADE ||
            n.equalsIgnoreCase("keep-alive") || n.equalsIgnoreCase("proxy-connection") ||
            (n == HeaderName.TE && !v.contentEquals("trailers"))
        ) {
            throw HeaderException(HeaderError.ConnectionSpecific(n.asStr()))
        }
        if (n == HeaderName.CONTENT_LENGTH) {
            // `parse_u64` of the HTTP/2 decoder (at most 19 digits); it reads an empty value as 0, which RFC 9110
            // §8.6 (1*DIGIT) does not allow, so an empty value is refused first.
            val bytes = v.asBytes()
            if (bytes.isEmpty()) throw HeaderException(HeaderError.InvalidContentLength)
            val parsed = parseU64(bytes) ?: throw HeaderException(HeaderError.InvalidContentLength)
            val previous = contentLength
            if (previous != null && previous != parsed) throw HeaderException(HeaderError.InvalidContentLength)
            contentLength = parsed
        }
    }

    fun build(): Header = Header(pseudo, fields).also { it.contentLength = contentLength }

    private fun show(a: ByteArray, off: Int, len: Int): String = a.decodeToString(off, off + len)
}

/**
 * Invalid header sections (`HeaderError`); all make the message malformed (H3_MESSAGE_ERROR, RFC 9114 §4.1.2), or are
 * errors of the caller when building a request ([MissingAuthority], [ContradictedAuthority]).
 */
sealed class HeaderError {
    data class InvalidHeaderName(val name: String) : HeaderError()
    data class InvalidHeaderValue(val name: String) : HeaderError()
    data class InvalidRequest(val reason: String) : HeaderError()
    object MissingMethod : HeaderError() {
        override fun toString() = "missing method in request headers"
    }

    object MissingStatus : HeaderError() {
        override fun toString() = "missing status in response headers"
    }

    object MissingAuthority : HeaderError() {
        override fun toString() = "missing authority"
    }

    object ContradictedAuthority : HeaderError() {
        override fun toString() = "uri and authority field are in contradiction"
    }

    /** ⚖️ A pseudo-header present twice. */
    data class DuplicatePseudo(val name: String) : HeaderError()

    /** ⚖️ A pseudo-header after a regular field. */
    data class PseudoAfterRegular(val name: String) : HeaderError()

    /** ⚖️ A connection-specific field (or `te` other than `trailers`). */
    data class ConnectionSpecific(val name: String) : HeaderError()

    /** ⚖️ A content-length that is not a number, or several different ones. */
    object InvalidContentLength : HeaderError() {
        override fun toString() = "invalid content-length"
    }
}

/** A [HeaderError] thrown; [code] is H3_MESSAGE_ERROR. */
class HeaderException(val error: HeaderError) : H3Exception(Code.H3_MESSAGE_ERROR, error.toString())
