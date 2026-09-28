package neton.http.h3.qpack

import neton.io.bytes.Buffer

// QPACK encoder and decoder stream instructions (RFC 9204 §4.3, §4.4), `h3::qpack::stream`. Each `decode` reads one
// instruction from the readable bytes of a Buffer and consumes it; it returns null, consuming nothing, when the
// instruction is not complete yet (the streams deliver instructions in arbitrary pieces), and throws ParseException
// for an invalid one.

/** Runs [read] over the readable bytes of [buf]; consumes what it read only when it returns non-null. */
private inline fun <T : Any> Buffer.tryRead(read: (QpackReader) -> T?): T? {
    val r = QpackReader()
    r.reset(backingArray(), readerIndex(), writerIndex())
    val out = read(r) ?: return null
    skip(r.pos - readerIndex())
    return out
}

/** [QpackReader.readInt]: false when incomplete. @throws ParseException on overflow. */
internal fun QpackReader.intOrNull(size: Int): Boolean = when (readInt(size)) {
    PrefixInt.OK -> true
    PrefixInt.UNEXPECTED_END -> false
    else -> throw ParseException(ParseError.Integer(PrefixIntError.Overflow))
}

/** [QpackReader.readString] into a copy; null when incomplete. @throws ParseException */
private fun QpackReader.stringOrNull(size: Int, slot: Int): ByteArray? = when (readString(size, slot)) {
    PrefixInt.OK -> copyString(slot)
    PrefixInt.UNEXPECTED_END -> null
    PrefixInt.OVERFLOW -> throw ParseException(ParseError.Str(PrefixStringError.Integer))
    else -> throw ParseException(ParseError.Str(PrefixStringError.HuffmanDecoding))
}

/** The encoder instruction a first byte starts (`EncoderInstruction::decode`). */
enum class EncoderInstruction {
    /** `001xxxxx`: Set Dynamic Table Capacity (§4.3.1). */
    DynamicTableSizeUpdate,

    /** `1Txxxxxx`: Insert with Name Reference (§4.3.2). */
    InsertWithNameRef,

    /** `01Hxxxxx`: Insert with Literal Name (§4.3.3). */
    InsertWithoutNameRef,

    /** `000xxxxx`: Duplicate (§4.3.4). */
    Duplicate,
    Unknown;

    companion object {
        fun decode(first: Int): EncoderInstruction = when {
            first and 0b1000_0000 != 0 -> InsertWithNameRef
            first and 0b0100_0000 == 0b0100_0000 -> InsertWithoutNameRef
            first and 0b1110_0000 == 0 -> Duplicate
            first and 0b0010_0000 == 0b0010_0000 -> DynamicTableSizeUpdate
            else -> Unknown
        }
    }
}

/** Insert with Name Reference (§4.3.2), `InsertWithNameRef`. */
sealed class InsertWithNameRef(val index: Long, val value: ByteArray) {
    class Static(index: Long, value: ByteArray) : InsertWithNameRef(index, value)
    class Dynamic(index: Long, value: ByteArray) : InsertWithNameRef(index, value)

    fun encode(buf: Buffer) {
        PrefixInt.encode(6, if (this is Static) 0b11 else 0b10, index, buf)
        PrefixString.encode(8, 0, value, buf)
    }

    override fun equals(other: Any?): Boolean =
        other is InsertWithNameRef && (other is Static) == (this is Static) && other.index == index &&
            other.value.contentEquals(value)

    override fun hashCode(): Int = index.hashCode() * 31 + value.contentHashCode()
    override fun toString(): String =
        "${if (this is Static) "Static" else "Dynamic"} { index: $index, value: ${value.decodeToString()} }"

    companion object {
        fun newStatic(index: Long, value: String): InsertWithNameRef = Static(index, value.encodeToByteArray())
        fun newDynamic(index: Long, value: String): InsertWithNameRef = Dynamic(index, value.encodeToByteArray())

        /** @throws ParseException */
        fun decode(buf: Buffer): InsertWithNameRef? = buf.tryRead { r ->
            if (!r.intOrNull(6)) return@tryRead null
            val flags = r.flags
            if (flags and 0b10 != 0b10) throw ParseException(ParseError.InvalidPrefix(flags))
            val index = r.value
            val value = r.stringOrNull(8, 1) ?: return@tryRead null
            if (flags and 0b01 == 0b01) Static(index, value) else Dynamic(index, value)
        }
    }
}

/** Insert with Literal Name (§4.3.3), `InsertWithoutNameRef`. */
class InsertWithoutNameRef(val name: ByteArray, val value: ByteArray) {
    constructor(name: String, value: String) : this(name.encodeToByteArray(), value.encodeToByteArray())

    fun encode(buf: Buffer) {
        PrefixString.encode(6, 0b01, name, buf)
        PrefixString.encode(8, 0, value, buf)
    }

    override fun equals(other: Any?): Boolean =
        other is InsertWithoutNameRef && other.name.contentEquals(name) && other.value.contentEquals(value)

    override fun hashCode(): Int = name.contentHashCode() * 31 + value.contentHashCode()
    override fun toString(): String = "InsertWithoutNameRef { name: ${name.decodeToString()}, value: ${value.decodeToString()} }"

    companion object {
        /** @throws ParseException */
        fun decode(buf: Buffer): InsertWithoutNameRef? = buf.tryRead { r ->
            val name = r.stringOrNull(6, 0) ?: return@tryRead null
            val value = r.stringOrNull(8, 1) ?: return@tryRead null
            InsertWithoutNameRef(name, value)
        }
    }
}

/** Duplicate (§4.3.4), `Duplicate`. */
data class Duplicate(val index: Long) {
    fun encode(buf: Buffer) = PrefixInt.encode(5, 0, index, buf)

    companion object {
        /** @throws ParseException */
        fun decode(buf: Buffer): Duplicate? = buf.tryRead { r ->
            if (!r.intOrNull(5)) return@tryRead null
            if (r.flags != 0) throw ParseException(ParseError.InvalidPrefix(r.flags))
            Duplicate(r.value)
        }
    }
}

/** Set Dynamic Table Capacity (§4.3.1), `DynamicTableSizeUpdate`. */
data class DynamicTableSizeUpdate(val size: Long) {
    fun encode(buf: Buffer) = PrefixInt.encode(5, 0b001, size, buf)

    companion object {
        /** @throws ParseException */
        fun decode(buf: Buffer): DynamicTableSizeUpdate? = buf.tryRead { r ->
            if (!r.intOrNull(5)) return@tryRead null
            if (r.flags != 0b001) throw ParseException(ParseError.InvalidPrefix(r.flags))
            DynamicTableSizeUpdate(r.value)
        }
    }
}

/** The decoder instruction a first byte starts (`DecoderInstruction::decode`). */
enum class DecoderInstruction {
    /** `1xxxxxxx`: Section Acknowledgment (§4.4.1). */
    HeaderAck,

    /** `01xxxxxx`: Stream Cancellation (§4.4.2). */
    StreamCancel,

    /** `00xxxxxx`: Insert Count Increment (§4.4.3). */
    InsertCountIncrement,
    Unknown;

    companion object {
        fun decode(first: Int): DecoderInstruction = when {
            first and 0b1100_0000 == 0 -> InsertCountIncrement
            first and 0b1000_0000 != 0 -> HeaderAck
            first and 0b0100_0000 == 0b0100_0000 -> StreamCancel
            else -> Unknown
        }
    }
}

/**
 * Insert Count Increment (§4.4.3), `InsertCountIncrement`. ⚖️ The increment is a 64-bit value; the reference holds it
 * in a `u8` and refuses increments above 64 as an integer overflow.
 */
data class InsertCountIncrement(val increment: Long) {
    fun encode(buf: Buffer) = PrefixInt.encode(6, 0b00, increment, buf)

    companion object {
        /** @throws ParseException */
        fun decode(buf: Buffer): InsertCountIncrement? = buf.tryRead { r ->
            if (!r.intOrNull(6)) return@tryRead null
            if (r.flags != 0) throw ParseException(ParseError.InvalidPrefix(r.flags))
            InsertCountIncrement(r.value)
        }
    }
}

/** Section Acknowledgment (§4.4.1), `HeaderAck`. */
data class HeaderAck(val streamId: Long) {
    fun encode(buf: Buffer) = PrefixInt.encode(7, 0b1, streamId, buf)

    companion object {
        /** @throws ParseException */
        fun decode(buf: Buffer): HeaderAck? = buf.tryRead { r ->
            if (!r.intOrNull(7)) return@tryRead null
            if (r.flags != 1) throw ParseException(ParseError.InvalidPrefix(r.flags))
            HeaderAck(r.value)
        }
    }
}

/** Stream Cancellation (§4.4.2), `StreamCancel`. */
data class StreamCancel(val streamId: Long) {
    fun encode(buf: Buffer) = PrefixInt.encode(6, 0b01, streamId, buf)

    companion object {
        /** @throws ParseException */
        fun decode(buf: Buffer): StreamCancel? = buf.tryRead { r ->
            if (!r.intOrNull(6)) return@tryRead null
            if (r.flags != 0b01) throw ParseException(ParseError.InvalidPrefix(r.flags))
            StreamCancel(r.value)
        }
    }
}
