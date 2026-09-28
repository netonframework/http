package neton.http.h2.codec

import neton.http.h2.frame.Frame
import neton.http.h2.frame.Reason
import neton.http.h2.frame.StreamId
import neton.http.h2.hpack.Encoder
import neton.http.h2.hpack.Header
import neton.http.h2.proto.Initiator
import neton.http.h2.proto.ProtoError
import neton.http.h2.raw
import neton.io.bytes.Buffer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Helpers for the SPEC §4.1 rule tests.

/** One frame on the wire: a 9-byte header and [payload] chunks (as for [raw]). */
fun frame(type: Int, flags: Int, stream: Int, vararg payload: Any): ByteArray {
    val body = raw(*payload)
    return raw(
        body.size ushr 16, body.size ushr 8, body.size, type, flags,
        stream ushr 24, stream ushr 16, stream ushr 8, stream, body,
    )
}

/** An HPACK block of [headers] from [encoder]. */
fun hpack(vararg headers: Header, encoder: Encoder = Encoder()): ByteArray {
    val b = Buffer()
    encoder.encode(headers.toList(), b)
    return b.peekAll()
}

/** Reads every frame of [bytes] with [reader]. */
fun readAll(bytes: ByteArray, reader: FramedRead = FramedRead()): List<Frame> {
    val buf = Buffer().also { it.writeBytes(bytes) }
    val out = ArrayList<Frame>()
    while (true) out.add(reader.decodeEof(buf) ?: break)
    return out
}

/** Reading [bytes] ends the connection with GOAWAY [reason] and [debugData]. */
fun assertGoAway(bytes: ByteArray, reason: Reason, debugData: String = "", reader: FramedRead = FramedRead()) {
    val e = assertFailsWith<ProtoError.GoAway> { readAll(bytes, reader) }
    assertEquals(reason, e.reason)
    assertEquals(debugData, e.debugData.decodeToString())
    assertEquals(Initiator.Library, e.initiator)
}

/** Reading [bytes] resets stream [stream] with PROTOCOL_ERROR. */
fun assertReset(bytes: ByteArray, stream: Int, reader: FramedRead = FramedRead()) {
    val e = assertFailsWith<ProtoError.Reset> { readAll(bytes, reader) }
    assertEquals(StreamId(stream), e.streamId)
    assertEquals(Reason.PROTOCOL_ERROR, e.reason)
    assertEquals(Initiator.Library, e.initiator)
}
