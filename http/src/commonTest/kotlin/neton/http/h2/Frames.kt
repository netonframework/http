package neton.http.h2

import neton.http.Method
import neton.http.StatusCode
import neton.http.h2.codec.Codec
import neton.http.h2.codec.FramedWrite
import neton.http.h2.frame.Data
import neton.http.h2.frame.Frame
import neton.http.h2.frame.GoAway
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Ping
import neton.http.h2.frame.Pseudo
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import neton.http.h2.frame.WindowUpdate
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.uri.Scheme
import neton.http.uri.Uri
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Test support ported from h2 0.4.19 `tests/h2-support/src/frames.rs` (the frame builders), `raw.rs` (raw_codec!),
// `assert.rs` (poll_frame!, poll_err!, assert_closed!) and `prelude.rs` (build_large_headers).

/** The frame builders of `frames.rs`: `frames::headers(1).request("GET", uri).eos()` and so on. */
object Frames {
    val SETTINGS: ByteArray = byteArrayOf(0, 0, 0, 4, 0, 0, 0, 0, 0)
    val SETTINGS_ACK: ByteArray = byteArrayOf(0, 0, 0, 4, 1, 0, 0, 0, 0)

    fun headers(id: Int): MockHeaders = MockHeaders(Headers(StreamId(id), Pseudo(), HeaderMap()))

    fun data(id: Int, buf: ByteArray): MockData = MockData(Data(StreamId(id), Bytes.copyOf(buf)))

    fun data(id: Int, buf: String): MockData = data(id, buf.encodeToByteArray())

    fun pushPromise(id: Int, promised: Int): MockPushPromise =
        MockPushPromise(PushPromise(StreamId(id), StreamId(promised), Pseudo(), HeaderMap()))

    fun windowUpdate(id: Int, sz: Int): WindowUpdate = WindowUpdate(StreamId(id), sz)

    fun goAway(id: Int): MockGoAway = MockGoAway(GoAway(StreamId(id), Reason.NO_ERROR))

    fun reset(id: Int): MockReset = MockReset(Reset(StreamId(id), Reason.NO_ERROR))

    fun settings(): MockSettings = MockSettings(Settings())

    fun settingsAck(): MockSettings = MockSettings(Settings.ack())

    fun ping(payload: ByteArray): MockPing = MockPing(Ping(Ping.payloadOf(payload)))
}

class MockHeaders(val frame: Headers) {
    private fun parts(): Triple<StreamId, Pseudo, HeaderMap<HeaderValue>> {
        check(!frame.isEndStream) { "eos flag will be lost" }
        check(frame.isEndHeaders) { "unset eoh will be lost" }
        val (pseudo, fields) = frame.intoParts()
        return Triple(frame.streamId, pseudo, fields)
    }

    fun request(method: String, uri: String): MockHeaders {
        val (id, _, fields) = parts()
        return MockHeaders(Headers(id, Pseudo.request(Method.fromStr(method), Uri.parse(uri)), fields))
    }

    fun method(method: String): MockHeaders {
        val (id, pseudo, fields) = parts()
        return MockHeaders(Headers(id, pseudo.copy(method = Method.fromStr(method)), fields))
    }

    fun pseudo(pseudo: Pseudo): MockHeaders {
        val (id, _, fields) = parts()
        return MockHeaders(Headers(id, pseudo, fields))
    }

    fun response(status: Int): MockHeaders {
        val (id, _, fields) = parts()
        return MockHeaders(Headers(id, Pseudo.response(StatusCode.fromU16(status)), fields))
    }

    fun fields(fields: HeaderMap<HeaderValue>): MockHeaders {
        val (id, pseudo, _) = parts()
        return MockHeaders(Headers(id, pseudo, fields))
    }

    fun field(key: String, value: String): MockHeaders {
        val (id, pseudo, fields) = parts()
        fields.insert(HeaderName.fromStr(key), HeaderValue.fromStr(value))
        return MockHeaders(Headers(id, pseudo, fields))
    }

    fun status(value: StatusCode): MockHeaders {
        val (id, pseudo, fields) = parts()
        pseudo.status = value
        return MockHeaders(Headers(id, pseudo, fields))
    }

    fun scheme(value: String): MockHeaders {
        val (id, pseudo, fields) = parts()
        pseudo.setScheme(Scheme.parse(value))
        return MockHeaders(Headers(id, pseudo, fields))
    }

    fun eos(): MockHeaders {
        frame.setEndStream()
        return this
    }

    fun intoFields(): HeaderMap<HeaderValue> = frame.intoParts().second
}

class MockData(val frame: Data) {
    fun padded(): MockData {
        frame.setPadded()
        return this
    }

    fun eos(): MockData {
        frame.setEndStream(true)
        return this
    }
}

class MockPushPromise(val frame: PushPromise) {
    private fun check() = check(frame.isEndHeaders) { "unset eoh will be lost" }

    fun request(method: String, uri: String): MockPushPromise {
        check()
        return MockPushPromise(
            PushPromise(frame.streamId, frame.promisedId, Pseudo.request(Method.fromStr(method), Uri.parse(uri)), frame.fields),
        )
    }

    fun fields(fields: HeaderMap<HeaderValue>): MockPushPromise {
        check()
        return MockPushPromise(PushPromise(frame.streamId, frame.promisedId, frame.pseudo, fields))
    }

    fun field(key: String, value: String): MockPushPromise {
        check()
        val fields = frame.fields
        fields.insert(HeaderName.fromStr(key), HeaderValue.fromStr(value))
        return MockPushPromise(PushPromise(frame.streamId, frame.promisedId, frame.pseudo, fields))
    }
}

class MockGoAway(val frame: GoAway) {
    fun protocolError() = reason(Reason.PROTOCOL_ERROR)
    fun internalError() = reason(Reason.INTERNAL_ERROR)
    fun flowControl() = reason(Reason.FLOW_CONTROL_ERROR)
    fun frameSize() = reason(Reason.FRAME_SIZE_ERROR)
    fun calm() = reason(Reason.ENHANCE_YOUR_CALM)
    fun noError() = reason(Reason.NO_ERROR)
    fun data(debugData: String) = MockGoAway(GoAway(frame.lastStreamId, frame.reason, Bytes.wrap(debugData.encodeToByteArray())))
    fun reason(reason: Reason) = MockGoAway(GoAway(frame.lastStreamId, reason, frame.debugData))
}

class MockReset(val frame: Reset) {
    fun protocolError() = reason(Reason.PROTOCOL_ERROR)
    fun flowControl() = reason(Reason.FLOW_CONTROL_ERROR)
    fun refused() = reason(Reason.REFUSED_STREAM)
    fun cancel() = reason(Reason.CANCEL)
    fun streamClosed() = reason(Reason.STREAM_CLOSED)
    fun internalError() = reason(Reason.INTERNAL_ERROR)
    fun reason(reason: Reason) = MockReset(Reset(frame.streamId, reason))
}

class MockSettings(val frame: Settings) {
    fun maxConcurrentStreams(max: Long) = apply { frame.maxConcurrentStreams = max }
    fun maxFrameSize(value: Long) = apply { frame.maxFrameSize = value }
    fun initialWindowSize(value: Long) = apply { frame.initialWindowSize = value }
    fun maxHeaderListSize(value: Long) = apply { frame.maxHeaderListSize = value }
    fun disablePush() = apply { frame.setEnablePush(false) }
    fun enableConnectProtocol(value: Long) = apply { frame.enableConnectProtocol = value }
    fun headerTableSize(value: Long) = apply { frame.headerTableSize = value }
}

class MockPing(val frame: Ping) {
    fun pong() = MockPing(Ping.pong(frame.payload))
}

/** `build_large_headers` (`prelude.rs`). */
fun buildLargeHeaders(): List<Pair<String, String>> = listOf(
    "one" to "hello",
    "two" to "2".repeat(4 * 1024),
    "three" to "three",
    "four" to "4".repeat(4 * 1024),
    "five" to "five",
    "six" to "6".repeat(4 * 1024),
    "seven" to "seven",
    "eight" to "8".repeat(4 * 1024),
    "nine" to "nine",
    "ten" to "0".repeat(4 * 1024),
    "eleven" to "1".repeat(32 * 1024),
)

/** Concatenates bytes given as Ints, Strings (UTF-8) and ByteArrays (the chunks of `raw_codec!`). */
fun raw(vararg chunks: Any): ByteArray {
    val out = ArrayList<Byte>()
    for (c in chunks) {
        when (c) {
            is Int -> out.add(c.toByte())
            is String -> out.addAll(c.encodeToByteArray().toList())
            is ByteArray -> out.addAll(c.toList())
            else -> throw IllegalArgumentException("chunk $c")
        }
    }
    return out.toByteArray()
}

/** `raw_codec! { read => [...] }`: a codec over a read buffer holding [bytes]. */
class RawCodec(bytes: ByteArray, val codec: Codec = Codec()) {
    val buf = Buffer().also { it.writeBytes(bytes) }

    /** `next()`: the next frame, or null at the end of the input. */
    fun next(): Frame? = codec.decodeEof(buf)
}

/** `poll_frame!(Type, codec)`. */
inline fun <reified T : Frame> RawCodec.pollFrame(): T = assertIs<T>(next())

/** `assert_closed!(codec)`. */
fun RawCodec.assertClosed() {
    assertNull(next())
    assertTrue(buf.isEmpty)
}

/** All bytes a [FramedWrite] produces, running its flush loop with complete writes. */
fun FramedWrite.drain(): ByteArray {
    val out = ArrayList<Byte>()
    while (true) {
        while (!isEmpty) {
            val bytes = writeBuffer.peekAll()
            val payload = queuedPayload
            out.addAll(bytes.toList())
            payload?.let { out.addAll(it.toByteArray().toList()) }
            advance(bytes.size + (payload?.size ?: 0))
        }
        if (!unsetFrame()) break
    }
    return out.toByteArray()
}

/** Buffers [frame] and returns the bytes written for it. */
fun FramedWrite.write(frame: Frame): ByteArray {
    assertNull(buffer(frame))
    return drain()
}

fun assertBytes(expected: ByteArray, actual: ByteArray) =
    assertEquals(expected.toList().map { it.toInt() and 0xff }, actual.toList().map { it.toInt() and 0xff })
