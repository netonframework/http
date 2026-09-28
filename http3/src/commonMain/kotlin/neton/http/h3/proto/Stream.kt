package neton.http.h3.proto

import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.random.Random

// Stream types and stream IDs (`src/proto/stream.rs`) and the codec parts of `src/stream.rs`.

/** Unidirectional stream types (RFC 9114 §6.2, RFC 9204 §4.2), `StreamType`. */
object StreamType {
    const val CONTROL: Long = 0x00
    const val PUSH: Long = 0x01
    const val ENCODER: Long = 0x02
    const val DECODER: Long = 0x03

    /** ⛔ WebTransport is not in the first version (SPEC §5); these types are unknown types here. */
    const val WEBTRANSPORT_BIDI: Long = 0x41
    const val WEBTRANSPORT_UNI: Long = 0x54

    /** The largest encoded stream type (`StreamType::MAX_ENCODED_SIZE`). */
    const val MAX_ENCODED_SIZE: Int = VarInt.MAX_SIZE

    /** A random reserved stream type `0x1f * N + 0x21` (`StreamType::grease`). */
    fun grease(random: Random = Random.Default): Long = greaseValue(random)

    /** The reference's `Display`. */
    fun name(type: Long): String = when (type) {
        CONTROL -> "Control"
        ENCODER -> "Encoder"
        DECODER -> "Decoder"
        WEBTRANSPORT_UNI -> "WebTransportUni"
        else -> "StreamType($type)"
    }
}

/**
 * A QUIC stream ID (`StreamId`): the two low bits give the initiator (bit 0: server) and the direction (bit 1:
 * unidirectional).
 */
class StreamId(val value: Long) : Comparable<StreamId> {
    init {
        require(VarInt.isValid(value)) { "invalid stream id: ${value.toString(16)}" }
    }

    /** Whether this is a client-initiated bidirectional stream, i.e. a request (`is_request`). */
    val isRequest: Boolean get() = dir == Dir.Bi && initiator == Side.Client

    /** Whether this is a server-initiated unidirectional stream, i.e. a push (`is_push`). */
    val isPush: Boolean get() = dir == Dir.Uni && initiator == Side.Server

    /** Which side opened the stream (`initiator`). */
    val initiator: Side get() = if (value and 1L == 0L) Side.Client else Side.Server

    /** The direction (`dir`). */
    val dir: Dir get() = if (value and 2L == 0L) Dir.Bi else Dir.Uni

    /** The index among the streams of the same initiator and direction (`index`). */
    val index: Long get() = value ushr 2

    /** The stream [n] indexes later, saturating at the largest index (`Add<usize>`). */
    operator fun plus(n: Long): StreamId {
        require(n >= 0)
        val max = VarInt.MAX ushr 2
        val i = if (index > max - n) max else index + n
        return of(i, dir, initiator)
    }

    /** Appends the ID as a varint (`Encode for StreamId`). */
    fun encode(buf: Buffer) = VarInt.encode(value, buf)

    override fun compareTo(other: StreamId): Int = value.compareTo(other.value)
    override fun equals(other: Any?): Boolean = other is StreamId && other.value == value
    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String =
        "${if (initiator == Side.Client) "client" else "server"} ${if (dir == Dir.Uni) "uni" else "bi"}directional stream $index"

    companion object {
        /** The first request stream (`FIRST_REQUEST`). */
        val FIRST_REQUEST: StreamId = of(0, Dir.Bi, Side.Client)

        /** The stream of [index] for [dir] and [initiator] (`StreamId::new`). */
        fun of(index: Long, dir: Dir, initiator: Side): StreamId =
            StreamId((index shl 2) or (dir.bit.toLong() shl 1) or initiator.bit.toLong())

        /** A stream ID from its value, or null when not below 2^62 (`TryFrom<u64>`). */
        fun tryFrom(value: Long): StreamId? = if (VarInt.isValid(value)) StreamId(value) else null
    }
}

/** The side of a connection (`Side`). */
enum class Side(internal val bit: Int) { Client(0), Server(1) }

/** The direction of a stream (`Dir`). */
enum class Dir(internal val bit: Int) { Bi(0), Uni(1) }

/**
 * The header a local unidirectional stream starts with (`UniStreamHeader`): the stream type, followed by SETTINGS for
 * the control stream. ⛔ The reference's `WebTransportUni` is not in the first version.
 */
sealed class UniStreamHeader {
    class Control(val settings: Settings) : UniStreamHeader()
    object Encoder : UniStreamHeader()
    object Decoder : UniStreamHeader()

    /** Appends the header. */
    fun encode(buf: Buffer) {
        when (this) {
            is Control -> { VarInt.encode(StreamType.CONTROL, buf); settings.encode(buf) }
            Encoder -> VarInt.encode(StreamType.ENCODER, buf)
            Decoder -> VarInt.encode(StreamType.DECODER, buf)
        }
    }
}

/**
 * Reads the header of a peer's unidirectional stream (`AcceptRecvStream::poll_type`): the stream type, and for a push
 * stream the push ID. Sans-I/O: call [decode] with the stream's received bytes until it returns true.
 *
 * ⚖️ The reference also reads a session ID after `WEBTRANSPORT_UNI`; without WebTransport that type is unknown, and a
 * stream of unknown type is answered with STOP_SENDING by the connection layer (RFC 9114 §6.2), so nothing more is
 * read from it.
 */
class StreamTypeDecoder {
    /** The stream type, once read; -1 before. */
    var type: Long = -1
        private set

    /** The push ID of a push stream, once read; -1 otherwise. */
    var pushId: Long = -1
        private set

    /** Whether the header is complete. */
    val isResolved: Boolean get() = type >= 0 && (type != StreamType.PUSH || pushId >= 0)

    /** Consumes header bytes from [buf]; returns true once the header is complete (the rest is the stream's body). */
    fun decode(buf: Buffer): Boolean {
        if (type < 0) {
            val t = VarInt.decode(buf)
            if (t < 0) return false
            type = t
        }
        if (type == StreamType.PUSH && pushId < 0) {
            val id = VarInt.decode(buf)
            if (id < 0) return false
            pushId = id
        }
        return true
    }
}

/**
 * Wire data of one frame to send (`WriteBuf`): the encoded frame header (preceded by a stream type for the first
 * frame of a unidirectional stream) in a small array, then the payload, without copying the payload. The transport
 * takes it with [chunk] / [advance] like the reference's `Buf`, or all at once with [writeTo].
 */
class WriteBuf private constructor(private val frame: Frame?) {
    private var buf = ByteArray(WRITE_BUF_ENCODE_SIZE)
    private var len = 0
    private var pos = 0
    private var payload: Bytes = Bytes.EMPTY

    /** Number of header bytes encoded (the reference's `len`, read by its tests). */
    val headerLength: Int get() = len

    private fun encodeVarInt(x: Long) {
        len = VarInt.encode(x, buf, len)
    }

    /** Encodes through a [Buffer], for the rare headers that are not two varints (SETTINGS may exceed the array). */
    private fun encodeVia(write: (Buffer) -> Unit) {
        val scratch = Buffer(WRITE_BUF_ENCODE_SIZE)
        write(scratch)
        val n = scratch.readableBytes
        if (len + n > buf.size) buf = buf.copyOf(len + n)
        scratch.backingArray().copyInto(buf, len, scratch.readerIndex(), scratch.readerIndex() + n)
        len += n
    }

    private fun encodeFrameHeader() {
        when (val f = frame ?: return) {
            is Frame.Data -> {
                encodeVarInt(FrameType.DATA); encodeVarInt(f.length)
                payload = f.payload ?: Bytes.EMPTY
            }
            is Frame.Headers -> {
                encodeVarInt(FrameType.HEADERS); encodeVarInt(f.block.size.toLong())
                payload = f.block
            }
            is PushPromise -> {
                encodeVia { f.encode(it) }
                payload = f.encoded
            }
            else -> encodeVia { f.encode(it) }
        }
    }

    /** Bytes left to send (`remaining`). */
    val remaining: Int get() = len - pos + payload.size

    /** The next contiguous bytes to send (`chunk`): the rest of the header, else the rest of the payload. */
    fun chunk(): Bytes = if (len - pos > 0) Bytes.copyOf(buf, pos, len) else payload

    /** Marks [n] bytes as sent (`advance`). */
    fun advance(n: Int) {
        require(n in 0..remaining) { "advance $n past remaining $remaining" }
        var cnt = n
        val header = len - pos
        if (header > 0) {
            val k = minOf(cnt, header)
            pos += k
            cnt -= k
        }
        if (cnt > 0) payload = payload.slice(cnt)
    }

    /** Appends everything left to [dst] and marks it sent. */
    fun writeTo(dst: Buffer) {
        if (len - pos > 0) dst.writeBytes(buf, pos, len - pos)
        dst.writeBytes(payload)
        pos = len
        payload = Bytes.EMPTY
    }

    companion object {
        /** Room for a stream type and a frame header (`WRITE_BUF_ENCODE_SIZE`). */
        const val WRITE_BUF_ENCODE_SIZE: Int = StreamType.MAX_ENCODED_SIZE + Frame.MAX_ENCODED_SIZE

        /** A stream type alone (`From<StreamType>`). */
        fun ofStreamType(type: Long): WriteBuf = WriteBuf(null).apply { encodeVarInt(type) }

        /** A unidirectional stream header (`From<UniStreamHeader>`). */
        fun of(header: UniStreamHeader): WriteBuf = WriteBuf(null).apply { encodeVia { header.encode(it) } }

        /** A frame (`From<Frame>`). */
        fun of(frame: Frame): WriteBuf = WriteBuf(frame).apply { encodeFrameHeader() }

        /** A stream type followed by a frame (`From<(StreamType, Frame)>`). */
        fun of(type: Long, frame: Frame): WriteBuf = WriteBuf(frame).apply {
            encodeVarInt(type)
            encodeFrameHeader()
        }
    }
}
