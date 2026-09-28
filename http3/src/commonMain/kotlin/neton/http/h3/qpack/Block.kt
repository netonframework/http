package neton.http.h3.qpack

import neton.io.bytes.Buffer

// Field line representations of an encoded field section (RFC 9204 §4.5), `h3::qpack::block`. Each representation
// has an `encode` and a `decode` from a Buffer, as in the reference (whose tests use them); the header block decoder
// reads the same wire formats straight from the frame payload through a QpackReader.

/** The representation of the next field line, from its first byte (`HeaderBlockField::decode`). */
enum class HeaderBlockField {
    /** `1Txxxxxx`: indexed field line (§4.5.2). */
    Indexed,

    /** `0001xxxx`: indexed field line with post-base index (§4.5.3). */
    IndexedWithPostBase,

    /** `01NTxxxx`: literal field line with name reference (§4.5.4). */
    LiteralWithNameRef,

    /** `0000Nxxx`: literal field line with post-base name reference (§4.5.5). */
    LiteralWithPostBaseNameRef,

    /** `001NHxxx`: literal field line with literal name (§4.5.6). */
    Literal,
    Unknown;

    companion object {
        fun decode(first: Int): HeaderBlockField = when {
            first and 0b1000_0000 != 0 -> Indexed
            first and 0b1111_0000 == 0b0001_0000 -> IndexedWithPostBase
            first and 0b1100_0000 == 0b0100_0000 -> LiteralWithNameRef
            first and 0b1111_0000 == 0 -> LiteralWithPostBaseNameRef
            first and 0b1110_0000 == 0b0010_0000 -> Literal
            else -> Unknown
        }
    }
}

/** Parse errors of representations and instructions (`ParseError`). */
sealed class ParseError {
    data class Integer(val error: PrefixIntError) : ParseError()
    data class Str(val error: PrefixStringError) : ParseError()
    data class InvalidPrefix(val prefix: Int) : ParseError()
    data class InvalidBase(val base: Long) : ParseError()
}

/** A [ParseError] thrown. */
class ParseException(val error: ParseError) : Exception(error.toString())

/** Runs [read] over the readable bytes of [buf], consuming what it read. */
internal inline fun <T> Buffer.readWith(read: (QpackReader) -> T): T {
    val r = QpackReader()
    r.reset(backingArray(), readerIndex(), writerIndex())
    val out = read(r)
    skip(r.pos - readerIndex())
    return out
}

internal fun QpackReader.intOrThrow(size: Int) {
    when (readInt(size)) {
        PrefixInt.OK -> Unit
        PrefixInt.UNEXPECTED_END -> throw ParseException(ParseError.Integer(PrefixIntError.UnexpectedEnd))
        else -> throw ParseException(ParseError.Integer(PrefixIntError.Overflow))
    }
}

internal fun QpackReader.stringOrThrow(size: Int, slot: Int): ByteArray {
    when (readString(size, slot)) {
        PrefixInt.OK -> return copyString(slot)
        PrefixInt.UNEXPECTED_END -> throw ParseException(ParseError.Str(PrefixStringError.UnexpectedEnd))
        PrefixInt.OVERFLOW -> throw ParseException(ParseError.Str(PrefixStringError.Integer))
        else -> throw ParseException(ParseError.Str(PrefixStringError.HuffmanDecoding))
    }
}

/**
 * The encoded field section prefix (§4.5.1), `HeaderPrefix`: the encoded Required Insert Count, and the Base as a sign
 * and a delta.
 */
data class HeaderPrefix(val encodedInsertCount: Long, val signNegative: Boolean, val deltaBase: Long) {
    /**
     * The Required Insert Count and the Base (`get`, §4.5.1.1 – §4.5.1.2) for a decoder that has seen
     * [totalInserted] insertions into a table of [maxTableSize] bytes. With a table size of 0 the result is (0, 0),
     * as in the reference; the stateless decoder checks the encoded count itself.
     * @throws ParseException `InvalidBase` for a negative Base.
     */
    fun get(totalInserted: Long, maxTableSize: Long): Pair<Long, Long> {
        if (maxTableSize == 0L) return 0L to 0L
        val required = if (encodedInsertCount == 0L) 0L else {
            var insertCount = encodedInsertCount - 1
            val maxEntries = maxTableSize / 32
            var wrapped = totalInserted % (2 * maxEntries)
            if (wrapped >= insertCount + maxEntries) {
                insertCount += 2 * maxEntries
            } else if (wrapped + maxEntries < insertCount) {
                wrapped += 2 * maxEntries
            }
            insertCount + totalInserted - wrapped
        }
        val base = when {
            required == 0L -> 0L
            !signNegative -> required + deltaBase
            else -> {
                if (deltaBase + 1 > required) throw ParseException(ParseError.InvalidBase(required - deltaBase - 1))
                required - deltaBase - 1
            }
        }
        return required to base
    }

    /** `encode`: Required Insert Count with an 8-bit prefix, then the sign and Delta Base with a 7-bit prefix. */
    fun encode(buf: Buffer) {
        PrefixInt.encode(8, 0, encodedInsertCount, buf)
        PrefixInt.encode(7, if (signNegative) 1 else 0, deltaBase, buf)
    }

    companion object {
        /** The prefix of a section (`HeaderPrefix::new`). */
        fun new(required: Long, base: Long, totalInserted: Long, maxTableSize: Long): HeaderPrefix {
            if (maxTableSize == 0L || required == 0L) return HeaderPrefix(0, false, 0)
            require(required <= totalInserted)
            val (signNegative, deltaBase) = if (required > base) true to required - base - 1 else false to base - required
            val maxEntries = maxTableSize / 32
            return HeaderPrefix(required % (2 * maxEntries) + 1, signNegative, deltaBase)
        }

        /** The prefix with no dynamic table references, as the stateless encoder writes it. */
        val ZERO: HeaderPrefix = HeaderPrefix(0, false, 0)

        /** `decode`. @throws ParseException */
        fun decode(buf: Buffer): HeaderPrefix = buf.readWith { r ->
            r.intOrThrow(8)
            val encodedInsertCount = r.value
            r.intOrThrow(7)
            HeaderPrefix(encodedInsertCount, r.flags == 1, r.value)
        }
    }
}

/** Indexed field line (§4.5.2), `Indexed`: an entry of the static or the dynamic table. */
sealed class Indexed {
    data class Static(val index: Long) : Indexed()
    data class Dynamic(val index: Long) : Indexed()

    fun encode(buf: Buffer) = when (this) {
        is Static -> PrefixInt.encode(6, 0b11, index, buf)
        is Dynamic -> PrefixInt.encode(6, 0b10, index, buf)
    }

    companion object {
        /** @throws ParseException */
        fun decode(buf: Buffer): Indexed = buf.readWith { r ->
            r.intOrThrow(6)
            when (r.flags) {
                0b11 -> Static(r.value)
                0b10 -> Dynamic(r.value)
                else -> throw ParseException(ParseError.InvalidPrefix(r.flags))
            }
        }
    }
}

/** Indexed field line with post-base index (§4.5.3), `IndexedWithPostBase`. */
data class IndexedWithPostBase(val index: Long) {
    fun encode(buf: Buffer) = PrefixInt.encode(4, 0b0001, index, buf)

    companion object {
        /** @throws ParseException */
        fun decode(buf: Buffer): IndexedWithPostBase = buf.readWith { r ->
            r.intOrThrow(4)
            if (r.flags != 0b0001) throw ParseException(ParseError.InvalidPrefix(r.flags))
            IndexedWithPostBase(r.value)
        }
    }
}

/** Literal field line with name reference (§4.5.4), `LiteralWithNameRef`. */
sealed class LiteralWithNameRef(val index: Long, val value: ByteArray) {
    class Static(index: Long, value: ByteArray) : LiteralWithNameRef(index, value)
    class Dynamic(index: Long, value: ByteArray) : LiteralWithNameRef(index, value)

    fun encode(buf: Buffer) {
        PrefixInt.encode(4, if (this is Static) 0b0101 else 0b0100, index, buf)
        PrefixString.encode(8, 0, value, buf)
    }

    override fun equals(other: Any?): Boolean =
        other is LiteralWithNameRef && (other is Static) == (this is Static) && other.index == index &&
            other.value.contentEquals(value)

    override fun hashCode(): Int = index.hashCode() * 31 + value.contentHashCode()

    override fun toString(): String =
        "${if (this is Static) "Static" else "Dynamic"} { index: $index, value: ${value.decodeToString()} }"

    companion object {
        fun newStatic(index: Long, value: String): LiteralWithNameRef = Static(index, value.encodeToByteArray())
        fun newDynamic(index: Long, value: String): LiteralWithNameRef = Dynamic(index, value.encodeToByteArray())

        /** @throws ParseException */
        fun decode(buf: Buffer): LiteralWithNameRef = buf.readWith { r ->
            r.intOrThrow(4)
            val f = r.flags
            val index = r.value
            when (f and 0b0101) {
                0b0101 -> Static(index, r.stringOrThrow(8, 1))
                0b0100 -> Dynamic(index, r.stringOrThrow(8, 1))
                else -> throw ParseException(ParseError.InvalidPrefix(f))
            }
        }
    }
}

/** Literal field line with post-base name reference (§4.5.5), `LiteralWithPostBaseNameRef`. */
class LiteralWithPostBaseNameRef(val index: Long, val value: ByteArray) {
    constructor(index: Long, value: String) : this(index, value.encodeToByteArray())

    fun encode(buf: Buffer) {
        PrefixInt.encode(3, 0b0000, index, buf)
        PrefixString.encode(8, 0, value, buf)
    }

    override fun equals(other: Any?): Boolean =
        other is LiteralWithPostBaseNameRef && other.index == index && other.value.contentEquals(value)

    override fun hashCode(): Int = index.hashCode() * 31 + value.contentHashCode()
    override fun toString(): String = "LiteralWithPostBaseNameRef { index: $index, value: ${value.decodeToString()} }"

    companion object {
        /** @throws ParseException */
        fun decode(buf: Buffer): LiteralWithPostBaseNameRef = buf.readWith { r ->
            r.intOrThrow(3)
            if (r.flags and 0b1111_0000 != 0) throw ParseException(ParseError.InvalidPrefix(r.flags))
            val index = r.value
            LiteralWithPostBaseNameRef(index, r.stringOrThrow(8, 1))
        }
    }
}

/** Literal field line with literal name (§4.5.6), `Literal`. */
class Literal(val name: ByteArray, val value: ByteArray) {
    constructor(name: String, value: String) : this(name.encodeToByteArray(), value.encodeToByteArray())

    fun encode(buf: Buffer) {
        PrefixString.encode(4, 0b0010, name, buf)
        PrefixString.encode(8, 0, value, buf)
    }

    override fun equals(other: Any?): Boolean =
        other is Literal && other.name.contentEquals(name) && other.value.contentEquals(value)

    override fun hashCode(): Int = name.contentHashCode() * 31 + value.contentHashCode()
    override fun toString(): String = "Literal { name: ${name.decodeToString()}, value: ${value.decodeToString()} }"

    companion object {
        /** @throws ParseException */
        fun decode(buf: Buffer): Literal = buf.readWith { r ->
            if (r.remaining < 1) throw ParseException(ParseError.Integer(PrefixIntError.UnexpectedEnd))
            val first = r.peek()
            if (first and 0b1110_0000 != 0b0010_0000) throw ParseException(ParseError.InvalidPrefix(first))
            val name = r.stringOrThrow(4, 0)
            Literal(name, r.stringOrThrow(8, 1))
        }
    }
}
