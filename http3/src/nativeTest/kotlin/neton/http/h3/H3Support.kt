package neton.http.h3

import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.h3.client.SendRequest
import neton.http.h3.proto.Frame
import neton.http.h3.proto.Header
import neton.http.h3.proto.StreamType
import neton.http.h3.proto.VarInt
import neton.http.h3.qpack.Encoder
import neton.http.h3.quic.RecvStream
import neton.http.h3.quic.SendStream
import neton.http.h3.server.Connection as ServerConnection
import neton.http.h3.server.RequestStream as ServerRequestStream
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

// Helpers of the reference's `tests/mod.rs`, `tests/connection.rs` and `tests/request.rs`, and for raw peers written
// directly on the in-memory QUIC double.

/**
 * Accepts a request and waits for its head (`get_stream_blocking`); null when the connection or the request fails.
 */
suspend fun getStreamBlocking(incoming: ServerConnection): Pair<Request<Unit>, ServerRequestStream>? {
    val resolver = try {
        incoming.accept()
    } catch (e: ConnectionError) {
        null
    } ?: return null
    return try {
        resolver.resolveRequest()
    } catch (e: StreamError) {
        null
    }
}

/** `request`: GET http://no.way and its response. */
suspend fun request(send: SendRequest): Response<Unit> {
    val stream = send.sendRequest(Request.get("http://no.way").body(Unit))
    return stream.recvResponse()
}

/** `response`: 418 without a body. */
suspend fun response(stream: ServerRequestStream) {
    stream.sendResponse(Response.builder().status(StatusCode.IM_A_TEAPOT).body(Unit))
    stream.finish()
}

fun ok(): Response<Unit> = Response.builder().status(200).body(Unit)

fun headers(vararg pairs: Pair<String, String>): HeaderMap<HeaderValue> =
    HeaderMap.new().also { m -> pairs.forEach { m.append(HeaderName.fromStr(it.first), HeaderValue.fromStr(it.second)) } }

/** Encodes frames, [Frame.encode] only (a frame header without its payload for DATA and HEADERS). */
fun Buffer.frame(frame: Frame): Buffer = apply { frame.encode(this) }

/** Encodes frames with their payloads. */
fun Buffer.frameWithPayload(frame: Frame): Buffer = apply { frame.encodeWithPayload(this) }

fun Buffer.varint(x: Long): Buffer = apply { VarInt.encode(x, this) }

fun Buffer.bytes(vararg b: Int): Buffer = apply { for (x in b) writeByte(x.toByte()) }

fun Buffer.toBytes(): Bytes = readSlice(readableBytes)

fun dataFrame(s: String): Frame.Data = Frame.Data(bytes(s))

/** `request_encode`: the HEADERS frame of [request]. */
fun Buffer.request(request: Request<*>): Buffer = apply {
    val block = Buffer()
    Header.request(request.method, request.uri, request.headers).encode(Encoder(), block)
    Frame.Headers(block.toBytes()).encodeWithPayload(this)
}

/** `trailers_encode`: a HEADERS frame of trailers. */
fun Buffer.trailers(fields: HeaderMap<HeaderValue>): Buffer = apply {
    val block = Buffer()
    Header.trailer(fields).encode(Encoder(), block)
    Frame.Headers(block.toBytes()).encodeWithPayload(this)
}

/** `unknown_frame_encode`. */
fun Buffer.unknownFrame(): Buffer = bytes(22, 4, 0, 255, 128, 0)

/** The type prefix of a control stream followed by SETTINGS (a raw peer's control stream). */
fun controlWithSettings(settings: neton.http.h3.proto.Settings = neton.http.h3.proto.Settings()): Buffer =
    Buffer().varint(StreamType.CONTROL).frame(settings)

suspend fun SendStream.send(buf: Buffer) = write(buf.toBytes())

/** Reads everything until the end of [stream]. */
suspend fun RecvStream.drain(): ByteArray {
    val out = Buffer()
    while (true) out.writeBytes(read() ?: return out.readAll())
}

/** Asserts a local connection error with [code]. */
fun assertLocal(code: Code, e: Throwable?): ConnectionError.Local {
    val err = assertIs<ConnectionError.Local>(e, "expected a local connection error $code, got $e")
    val app = assertIs<LocalError.Application>(err.error)
    assertEquals(code, app.code, "local error: ${app.reason}")
    return err
}

/** Asserts that the peer closed the connection with [code]. */
fun assertRemote(code: Code, e: Throwable?) {
    val err = assertIs<ConnectionError.Remote>(e, "expected the peer to close with $code, got $e")
    val close = assertIs<neton.http.h3.quic.ConnectionErrorIncoming.ApplicationClose>(err.error)
    assertEquals(code, Code(close.errorCode))
}

/** Asserts [e] is a stream error carrying a local connection error with [code]. */
fun assertStreamLocal(code: Code, e: Throwable?) {
    val err = assertIs<StreamError.Connection>(e, "expected a connection error $code on the stream, got $e")
    assertLocal(code, err.error)
}

/** The exception [block] throws, failing if it returns. */
inline fun <reified T : Throwable> failsWith(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        fail("expected ${T::class.simpleName}, got $e")
    }
    fail("expected ${T::class.simpleName}, got no exception")
}
