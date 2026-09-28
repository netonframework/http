package neton.http.h3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import neton.http.Request
import neton.http.StatusCode
import neton.http.h3.proto.Frame
import neton.http.h3.proto.PushPromise
import neton.http.h3.proto.StreamType
import neton.http.h3.proto.StreamTypeDecoder
import neton.http.h3.quic.RecvStream
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import neton.http.h3.client.builder as clientBuilder
import neton.http.h3.client.newClient
import neton.http.h3.server.builder as serverBuilder
import neton.http.h3.server.newConnection

/** Reads a raw peer's view of the other side's control stream. */
private class RawControl(private val stream: RecvStream, leftover: Buffer) {
    val frames = FrameStream()

    init {
        frames.buffer.writeBytes(leftover.backingArray(), leftover.readerIndex(), leftover.readableBytes)
    }

    suspend fun next(): Frame? {
        while (true) {
            frames.nextFrame()?.let { return it }
            if (frames.isFinished) return null
            val chunk = stream.read()
            if (chunk == null) frames.onEnd() else frames.onData(chunk)
        }
    }

    val recv: RecvStream get() = stream
}

/** Accepts [conn]'s peer unidirectional streams until the control stream. */
private suspend fun acceptControl(conn: neton.http.h3.quic.Connection): RawControl {
    while (true) {
        val s = conn.acceptUni()
        val buf = Buffer()
        val type = StreamTypeDecoder()
        while (!type.decode(buf)) buf.writeBytes(s.read()!!)
        if (type.type == StreamType.CONTROL) return RawControl(s, buf)
    }
}

// Tests the reference does not have (SPEC §5, first-version scope and layer 2): critical streams, QPACK stream
// validation surfacing as connection errors, the 431 path, cancellation isolation, a blocked request stream not
// blocking others, the ⚖️ GOAWAY boundary, and other stream-type and control-stream rules.
// They run on the in-memory double (ConnectionLayerTestMemory) and on neton.quic over loopback UDP
// (ConnectionLayerTestQuic).
abstract class ConnectionLayerTest {
    /** The QUIC the scenarios run on: the in-memory double (layer 2) or neton.quic on loopback UDP (layer 3). */
    abstract val quic: QuicPairFactory

    private suspend fun CoroutineScope.quicPair(streamCapacity: Int? = null) = with(quic) { pair(null, streamCapacity) }

    // ---- critical streams (RFC 9114 §6.2.1, RFC 9204 §4.2): closed or reset → H3_CLOSED_CRITICAL_STREAM ----

    private suspend fun CoroutineScope.serverFailsAfter(code: Code, rawClient: suspend (neton.http.h3.quic.Connection) -> Unit) {
        val (cq, sq) = quicPair()
        rawClient(cq)
        val incoming = newConnection(sq)
        assertLocal(code, incoming.run())
        assertLocal(code, failsWith<ConnectionError> { incoming.accept() })
        eventually { cq.applicationCloseCode() != null }
        assertEquals(code.value, cq.applicationCloseCode(), "the code the connection is closed with")
    }

    @Test
    fun encoderStreamFinishedIsCriticalClosure() = h3Test {
        serverFailsAfter(Code.H3_CLOSED_CRITICAL_STREAM) { cq ->
            cq.openUni().send(controlWithSettings())
            val enc = cq.openUni()
            enc.send(Buffer().varint(StreamType.ENCODER))
            enc.finish()
        }
    }

    // A reset stream whose type never arrived is ignored (RFC 9114 §6.2), and over QUIC a reset abandons the data not
    // yet sent: the reset must follow the type's delivery. The raw client waits until the server has its SETTINGS
    // (sent after the stream type in the decoder case), which the double delivers at once.
    @Test
    fun decoderStreamResetIsCriticalClosure() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        val drive = async { incoming.run() }
        val dec = cq.openUni()
        dec.send(Buffer().varint(StreamType.DECODER))
        cq.openUni().send(controlWithSettings())
        eventually { incoming.peerSettings != null }
        dec.reset(Code.H3_NO_ERROR.value)
        assertLocal(Code.H3_CLOSED_CRITICAL_STREAM, drive.await())
        eventually { cq.applicationCloseCode() != null }
        assertEquals(Code.H3_CLOSED_CRITICAL_STREAM.value, cq.applicationCloseCode())
    }

    @Test
    fun controlStreamResetIsCriticalClosure() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        val drive = async { incoming.run() }
        val control = cq.openUni()
        control.send(controlWithSettings())
        eventually { incoming.peerSettings != null }
        control.reset(Code.H3_NO_ERROR.value)
        assertLocal(Code.H3_CLOSED_CRITICAL_STREAM, drive.await())
        eventually { cq.applicationCloseCode() != null }
        assertEquals(Code.H3_CLOSED_CRITICAL_STREAM.value, cq.applicationCloseCode())
    }

    @Test
    fun stopSendingOnLocalControlStreamIsCriticalClosure() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        val drive = async { incoming.run() }
        cq.openUni().send(controlWithSettings())
        // The server's control stream: the raw client asks it to stop.
        acceptControl(cq).recv.stopSending(Code.H3_NO_ERROR.value)
        val err = assertLocal(Code.H3_CLOSED_CRITICAL_STREAM, drive.await())
        assertTrue((err.error as LocalError.Application).reason.startsWith("control stream was requested to stop sending"))
    }

    @Test
    fun serverControlStreamClosedOnClient() = h3Test {
        val (cq, sq) = quicPair()
        val control = sq.openUni()
        control.send(controlWithSettings())
        control.finish()
        val (driver, _) = newClient(cq)
        assertLocal(Code.H3_CLOSED_CRITICAL_STREAM, driver.run())
    }

    // ---- QPACK encoder / decoder streams are read and validated (SPEC §5): errors close the connection ----

    @Test
    fun encoderStreamInsertionIsEncoderStreamError() = h3Test {
        serverFailsAfter(Code.QPACK_ENCODER_STREAM_ERROR) { cq ->
            cq.openUni().send(controlWithSettings())
            // Set Dynamic Table Capacity 0 (valid), then Insert with Literal Name "abc" (over the capacity of 0).
            cq.openUni().send(Buffer().varint(StreamType.ENCODER).bytes(0x20, 0x43, 'a'.code, 'b'.code, 'c'.code, 0x01, 'x'.code))
        }
    }

    @Test
    fun encoderStreamCapacityAboveMaximumIsEncoderStreamError() = h3Test {
        serverFailsAfter(Code.QPACK_ENCODER_STREAM_ERROR) { cq ->
            cq.openUni().send(controlWithSettings())
            // Set Dynamic Table Capacity 16 while the advertised maximum is 0.
            cq.openUni().send(Buffer().varint(StreamType.ENCODER).bytes(0x30))
        }
    }

    @Test
    fun zeroInsertCountIncrementIsDecoderStreamError() = h3Test {
        serverFailsAfter(Code.QPACK_DECODER_STREAM_ERROR) { cq ->
            cq.openUni().send(controlWithSettings())
            cq.openUni().send(Buffer().varint(StreamType.DECODER).bytes(0x00))
        }
    }

    @Test
    fun sectionAcknowledgmentIsDecoderStreamErrorOnClient() = h3Test {
        val (cq, sq) = quicPair()
        sq.openUni().send(controlWithSettings())
        // No section with a dynamic reference was ever sent: Section Acknowledgment of stream 0 is an error.
        sq.openUni().send(Buffer().varint(StreamType.DECODER).bytes(0x80))
        val (driver, _) = newClient(cq)
        assertLocal(Code.QPACK_DECODER_STREAM_ERROR, driver.run())
    }

    @Test
    fun validQpackInstructionsKeepTheConnection() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        val drive = async { incoming.run() }
        cq.openUni().send(controlWithSettings())
        // Set Dynamic Table Capacity 0 on the encoder stream, Stream Cancellation on the decoder stream: both valid.
        cq.openUni().send(Buffer().varint(StreamType.ENCODER).bytes(0x20))
        cq.openUni().send(Buffer().varint(StreamType.DECODER).bytes(0x41))
        val raw = cq.openBi()
        raw.send(Buffer().request(Request.get("http://localhost/").body(Unit)))
        raw.finish()
        val (_, s) = getStreamBlocking(incoming)!!
        response(s)
        assertTrue(raw.drain().isNotEmpty())
        assertTrue(drive.isActive)
        assertNull(incoming.error)
        drive.cancel()
    }

    @Test
    fun secondEncoderStreamIsStreamCreationError() = h3Test {
        serverFailsAfter(Code.H3_STREAM_CREATION_ERROR) { cq ->
            cq.openUni().send(controlWithSettings())
            cq.openUni().send(Buffer().varint(StreamType.ENCODER))
            cq.openUni().send(Buffer().varint(StreamType.ENCODER))
        }
    }

    // ---- other unidirectional stream types ----

    @Test
    fun unknownStreamTypeIsStopped() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        val drive = async { incoming.run() }
        cq.openUni().send(controlWithSettings())
        val grease = cq.openUni()
        grease.send(Buffer().varint(StreamType.grease()).bytes(1, 2, 3))
        // RFC 9114 §6.2: aborted with H3_STREAM_CREATION_ERROR, never a connection error.
        assertEquals(Code.H3_STREAM_CREATION_ERROR.value, grease.stopped())
        yield()
        assertTrue(drive.isActive)
        assertNull(incoming.error)
        drive.cancel()
    }

    @Test
    fun pushStreamFromClientIsStreamCreationError() = h3Test {
        serverFailsAfter(Code.H3_STREAM_CREATION_ERROR) { cq ->
            cq.openUni().send(controlWithSettings())
            cq.openUni().send(Buffer().varint(StreamType.PUSH).varint(0))
        }
    }

    @Test
    fun pushStreamWithoutMaxPushIdIsIdErrorOnClient() = h3Test {
        val (cq, sq) = quicPair()
        sq.openUni().send(controlWithSettings())
        sq.openUni().send(Buffer().varint(StreamType.PUSH).varint(0))
        val (driver, _) = newClient(cq)
        assertLocal(Code.H3_ID_ERROR, driver.run())
    }

    // ---- control stream rules ----

    @Test
    fun secondSettingsIsFrameUnexpected() = h3Test {
        serverFailsAfter(Code.H3_FRAME_UNEXPECTED) { cq ->
            cq.openUni().send(controlWithSettings().frame(neton.http.h3.proto.Settings()))
        }
    }

    @Test
    fun headersOnControlStreamIsFrameUnexpected() = h3Test {
        serverFailsAfter(Code.H3_FRAME_UNEXPECTED) { cq ->
            cq.openUni().send(controlWithSettings().frameWithPayload(Frame.headers("abc")))
        }
    }

    @Test
    fun oversizedHeadersOnControlStreamIsFrameUnexpected() = h3Test {
        // A HEADERS frame over maxHeadersFrameSize is refused from its header; on the control stream it is still
        // H3_FRAME_UNEXPECTED (not H3_EXCESSIVE_LOAD), before or after SETTINGS as the reference would report it.
        serverFailsAfter(Code.H3_FRAME_UNEXPECTED) { cq ->
            cq.openUni().send(controlWithSettings().varint(1).varint(1L shl 20))
        }
        serverFailsAfter(Code.H3_MISSING_SETTINGS) { cq ->
            cq.openUni().send(Buffer().varint(StreamType.CONTROL).varint(1).varint(1L shl 20))
        }
    }

    @Test
    fun pushPromiseOnServerControlStreamIsFrameUnexpected() = h3Test {
        serverFailsAfter(Code.H3_FRAME_UNEXPECTED) { cq ->
            cq.openUni().send(controlWithSettings().frameWithPayload(PushPromise(0, bytes("x"))))
        }
    }

    @Test
    fun maxPushIdOnClientControlStreamIsFrameUnexpected() = h3Test {
        val (cq, sq) = quicPair()
        sq.openUni().send(controlWithSettings().frame(Frame.MaxPushId(3)))
        val (driver, _) = newClient(cq)
        assertLocal(Code.H3_FRAME_UNEXPECTED, driver.run())
    }

    @Test
    fun maxPushIdAndCancelPushIgnoredByServer() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        val drive = async { incoming.run() }
        cq.openUni().send(controlWithSettings().frame(Frame.MaxPushId(3)).frame(Frame.CancelPush(1)))
        eventually { incoming.peerSettings != null }
        yield()
        assertTrue(drive.isActive)
        drive.cancel()
    }

    // ---- 431 (SPEC §5, the three header limits) ----

    @Test
    fun tooManyFieldsGets431AndConnectionContinues() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = serverBuilder().maxFieldCount(3).build(sq)
        launch { incoming.run() }
        val (driver, send) = newClient(cq)
        launch { driver.run() }
        val server = async {
            val resolver = incoming.accept()!!
            failsWith<StreamError.HeaderTooBig> { resolver.resolveRequest() }
        }
        val stream = send.sendRequest(Request.get("http://localhost/salut").body(Unit))
        assertEquals(StatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE, stream.recvResponse().status)
        // The response is complete (FIN) ...
        assertNull(stream.recvData())
        // ... and the server stopped reading the request with H3_NO_ERROR.
        assertEquals(Code.H3_NO_ERROR, failsWith<StreamError.RemoteTerminate> { stream.sendData(bytes("more")) }.code)
        val err = server.await()
        // :method, :scheme, :authority, :path: the fourth line is refused.
        assertEquals(4L, err.actualSize)
        assertEquals(3L, err.maxSize)
        // The connection goes on (a request within the limit would still be served).
        assertNull(incoming.error)
        assertNull(driver.error)
    }

    @Test
    fun oversizedHeadersFrameGets431FromItsFrameHeader() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = serverBuilder().maxHeadersFrameSize(16).build(sq)
        launch { incoming.run() }
        val (driver, send) = newClient(cq)
        launch { driver.run() }
        val server = async {
            val resolver = incoming.accept()!!
            failsWith<StreamError.HeaderTooBig> { resolver.resolveRequest() }
        }
        val stream = send.sendRequest(
            Request.get("http://localhost/salut").header("x-long", "v".repeat(100)).body(Unit),
        )
        assertEquals(StatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE, stream.recvResponse().status)
        val err = server.await()
        assertEquals(16L, err.maxSize)
        assertTrue(err.actualSize > 16)
        // A second request, within the limit, is served on the same connection.
        val serve = launch {
            val (_, s) = getStreamBlocking(incoming)!!
            response(s)
        }
        assertEquals(StatusCode.IM_A_TEAPOT, send.sendRequest(Request.get("http://a/").body(Unit)).recvResponse().status)
        serve.join()
    }

    @Test
    fun oversizedResponseHeadersFrameFailsOnClient() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val (driver, send) = clientBuilder().maxHeadersFrameSize(32).build(cq)
        launch { driver.run() }
        val server = async {
            val (_, s) = getStreamBlocking(incoming)!!
            s.sendResponse(neton.http.Response.builder().status(200).header("x-long", "v".repeat(100)).body(Unit))
            failsWith<StreamError.RemoteTerminate> {
                while (true) s.sendData(bytes("data"))
            }
        }
        val stream = send.sendRequest(Request.get("http://localhost/salut").body(Unit))
        val err = failsWith<StreamError.HeaderTooBig> { stream.recvResponse() }
        assertEquals(32L, err.maxSize)
        // The client stopped the response with H3_REQUEST_CANCELLED.
        assertEquals(Code.H3_REQUEST_CANCELLED, server.await().code)
    }

    // ---- request cancellation only affects that request ----

    @Test
    fun clientCancellationIsIsolated() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        val serverDrive = async { incoming.run() }
        val (driver, send) = newClient(cq)
        val clientDrive = async { driver.run() }
        val a = send.sendRequest(Request.post("http://localhost/a").body(Unit))
        a.sendData(bytes("part"))
        val b = send.sendRequest(Request.post("http://localhost/b").body(Unit))
        val (_, sa) = getStreamBlocking(incoming)!!
        val (_, sb) = getStreamBlocking(incoming)!!
        assertEquals("part", sa.recvData()!!.decodeToString())
        // The client cancels A (RFC 9114 §4.1.1: reset the sending part, abort reading).
        a.stopStream(Code.H3_REQUEST_CANCELLED)
        a.stopSending(Code.H3_REQUEST_CANCELLED)
        assertEquals(Code.H3_REQUEST_CANCELLED, failsWith<StreamError.RemoteTerminate> { sa.recvData() }.code)
        // B is unaffected.
        b.sendData(bytes("body b"))
        b.finish()
        assertEquals("body b", sb.recvData()!!.decodeToString())
        assertNull(sb.recvData())
        response(sb)
        assertEquals(StatusCode.IM_A_TEAPOT, b.recvResponse().status)
        assertTrue(serverDrive.isActive && clientDrive.isActive)
        assertNull(incoming.error)
        assertNull(driver.error)
    }

    @Test
    fun serverCancellationIsIsolated() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        val serverDrive = async { incoming.run() }
        val (driver, send) = newClient(cq)
        val clientDrive = async { driver.run() }
        val a = send.sendRequest(Request.get("http://localhost/a").body(Unit))
        a.finish()
        val b = send.sendRequest(Request.get("http://localhost/b").body(Unit))
        b.finish()
        val (_, sa) = getStreamBlocking(incoming)!!
        val (_, sb) = getStreamBlocking(incoming)!!
        sa.stopStream(Code.H3_REQUEST_CANCELLED)
        assertEquals(Code.H3_REQUEST_CANCELLED, failsWith<StreamError.RemoteTerminate> { a.recvResponse() }.code)
        response(sb)
        assertEquals(StatusCode.IM_A_TEAPOT, b.recvResponse().status)
        assertTrue(serverDrive.isActive && clientDrive.isActive)
        assertNull(incoming.error)
    }

    // ---- a blocked request stream does not block the control stream or other requests ----

    @Test
    fun blockedRequestStreamDoesNotBlockOthers() = h3Test {
        val (cq, sq) = quicPair(streamCapacity = 1024)
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val (driver, send) = newClient(cq)
        launch { driver.run() }
        val a = send.sendRequest(Request.get("http://localhost/a").body(Unit))
        a.finish()
        val (_, sa) = getStreamBlocking(incoming)!!
        sa.sendResponse(ok())
        // A 64 KiB body the client does not read: the server's write on A blocks (1 KiB buffer).
        var sent = false
        val writerA = launch {
            sa.sendData(Bytes.wrap(ByteArray(64 * 1024)))
            sa.finish()
            sent = true
        }
        yield()
        assertFalse(sent, "request A is blocked")
        // Request B is served meanwhile.
        val b = send.sendRequest(Request.get("http://localhost/b").body(Unit))
        b.finish()
        val (_, sb) = getStreamBlocking(incoming)!!
        response(sb)
        assertEquals(StatusCode.IM_A_TEAPOT, b.recvResponse().status)
        // The control stream progresses too: the GOAWAY reaches the client.
        incoming.shutdown(0)
        eventually { driver.isClosing }
        assertFalse(sent, "request A is still blocked")
        // Reading A unblocks it.
        assertEquals(StatusCode.OK, a.recvResponse().status)
        var total = 0
        while (true) total += (a.recvData() ?: break).size
        assertEquals(64 * 1024, total)
        writerA.join()
        assertTrue(sent)
    }

    @Test
    fun blockedRequestBodyDoesNotBlockOthers() = h3Test {
        val (cq, sq) = quicPair(streamCapacity = 1024)
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val (driver, send) = newClient(cq)
        launch { driver.run() }
        // The client uploads 64 KiB on A; the server does not read it.
        val a = send.sendRequest(Request.post("http://localhost/a").body(Unit))
        val (_, sa) = getStreamBlocking(incoming)!!
        var uploaded = false
        val uploader = launch {
            a.sendData(Bytes.wrap(ByteArray(64 * 1024)))
            a.finish()
            uploaded = true
        }
        yield()
        assertFalse(uploaded)
        val b = send.sendRequest(Request.get("http://localhost/b").body(Unit))
        b.finish()
        val (_, sb) = getStreamBlocking(incoming)!!
        response(sb)
        assertEquals(StatusCode.IM_A_TEAPOT, b.recvResponse().status)
        var total = 0
        while (true) total += (sa.recvData() ?: break).size
        assertEquals(64 * 1024, total)
        uploader.join()
        assertTrue(uploaded)
        response(sa)
        assertEquals(StatusCode.IM_A_TEAPOT, a.recvResponse().status)
    }

    // ---- GOAWAY (⚖️ SPEC §5: IDs >= the GOAWAY ID are rejected, RFC 9114 §5.2) ----

    @Test
    fun goawayIdIsFirstRejectedStreamAndBoundaryIsRejected() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        launch { incoming.run() }
        cq.openUni().send(controlWithSettings())
        val control = acceptControl(cq)
        assertIs<neton.http.h3.proto.Settings>(control.next())
        // Raw requests on streams 0 and 4.
        val s0 = cq.openBi()
        s0.send(Buffer().request(Request.get("http://localhost/0").body(Unit)))
        val (_, first) = getStreamBlocking(incoming)!!
        assertEquals(0L, first.id.value)
        // shutdown(0) after accepting stream 0: the GOAWAY carries 4, the first stream not processed.
        incoming.shutdown(0)
        assertEquals(Frame.Goaway(4), control.next())
        // Stream 4, exactly the GOAWAY ID, is rejected (the reference would send GOAWAY(0) here, and with
        // shutdown(1) would send GOAWAY(4) and accept stream 4).
        val s4 = cq.openBi()
        s4.send(Buffer().request(Request.get("http://localhost/4").body(Unit)))
        assertEquals(Code.H3_REQUEST_REJECTED.value, s4.stopped())
        assertEquals(Code.H3_REQUEST_REJECTED.value, failsWith<neton.http.h3.quic.StreamErrorIncoming.StreamTerminated> { s4.read() }.errorCode)
        // The accepted request still completes, then accept() reports the end.
        response(first)
        assertNull(incoming.accept())
        val body = s0.drain()
        assertTrue(body.isNotEmpty())
    }

    @Test
    fun shutdownBeforeAnyRequestRejectsStreamZero() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        launch { incoming.run() }
        cq.openUni().send(controlWithSettings())
        val control = acceptControl(cq)
        control.next()
        incoming.shutdown(0)
        // GOAWAY(0): no request is processed (the reference's GOAWAY(0) with `>` would still accept stream 0).
        assertEquals(Frame.Goaway(0), control.next())
        val s0 = cq.openBi()
        s0.send(Buffer().request(Request.get("http://localhost/0").body(Unit)))
        assertEquals(Code.H3_REQUEST_REJECTED.value, s0.stopped())
        assertNull(incoming.accept())
    }

    @Test
    fun shutdownWithGraceIntervalAndNeverIncreasingGoaway() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        launch { incoming.run() }
        cq.openUni().send(controlWithSettings())
        val control = acceptControl(cq)
        control.next()
        cq.openBi().send(Buffer().request(Request.get("http://localhost/0").body(Unit)))
        val (_, first) = getStreamBlocking(incoming)!!
        // One more request after stream 0: GOAWAY(8), stream 4 accepted, stream 8 rejected.
        incoming.shutdown(1)
        assertEquals(Frame.Goaway(8), control.next())
        // A later shutdown with more requests does not raise the ID; one with fewer lowers it.
        incoming.shutdown(5)
        incoming.shutdown(0)
        assertEquals(Frame.Goaway(4), control.next())
        assertEquals(4L, incoming.inner.sentClosing)
        response(first)
    }

    @Test
    fun increasingGoawayIsIdErrorOnClient() = h3Test {
        val (cq, sq) = quicPair()
        sq.openUni().send(controlWithSettings().frame(Frame.Goaway(8)).frame(Frame.Goaway(4)).frame(Frame.Goaway(12)))
        val (driver, send) = newClient(cq)
        val err = assertLocal(Code.H3_ID_ERROR, driver.run())
        assertTrue((err.error as LocalError.Application).reason.contains("greater than the former one (4)"))
        assertIs<StreamError>(failsWith<StreamError> { request(send) })
    }

    @Test
    fun increasingGoawayIsIdErrorOnServer() = h3Test {
        serverFailsAfter(Code.H3_ID_ERROR) { cq ->
            cq.openUni().send(controlWithSettings().frame(Frame.Goaway(0)).frame(Frame.Goaway(1)))
        }
    }

    @Test
    fun requestAfterGoawayIsRemoteClosing() = h3Test {
        val (cq, sq) = quicPair()
        sq.openUni().send(controlWithSettings().frame(Frame.Goaway(0)))
        val (driver, send) = newClient(cq)
        launch { driver.run() }
        eventually { driver.isClosing }
        failsWith<StreamError.RemoteClosing> { request(send) }
    }

    // ---- malformed messages are stream errors (RFC 9114 §4.1.2) ----

    @Test
    fun malformedRequestIsStreamErrorOnly() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        val drive = async { incoming.run() }
        cq.openUni().send(controlWithSettings())
        // A request with a connection-specific field (⚖️ phase A check 3).
        val raw = cq.openBi()
        raw.send(Buffer().request(Request.get("http://localhost/").header("connection", "close").body(Unit)))
        val resolver = incoming.accept()!!
        assertEquals(Code.H3_MESSAGE_ERROR, failsWith<StreamError.Stream> { resolver.resolveRequest() }.code)
        assertEquals(Code.H3_MESSAGE_ERROR.value, raw.stopped())
        assertEquals(Code.H3_MESSAGE_ERROR.value, failsWith<neton.http.h3.quic.StreamErrorIncoming.StreamTerminated> { raw.read() }.errorCode)
        assertTrue(drive.isActive)
        assertNull(incoming.error)
    }

    @Test
    fun contentLengthMismatchIsMessageError() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val (driver, send) = newClient(cq)
        launch { driver.run() }
        val stream = send.sendRequest(Request.post("http://localhost/").header("content-length", "10").body(Unit))
        stream.sendData(bytes("short"))
        stream.finish()
        val (_, s) = getStreamBlocking(incoming)!!
        assertEquals("short", s.recvData()!!.decodeToString())
        assertEquals(Code.H3_MESSAGE_ERROR, failsWith<StreamError.Stream> { s.recvData() }.code)
        assertEquals(Code.H3_MESSAGE_ERROR, failsWith<StreamError.RemoteTerminate> { stream.recvResponse() }.code)
        assertNull(incoming.error)
    }

    @Test
    fun contentLengthExceededIsMessageError() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val (driver, send) = newClient(cq)
        launch { driver.run() }
        val stream = send.sendRequest(Request.post("http://localhost/").header("content-length", "2").body(Unit))
        stream.sendData(bytes("too long"))
        stream.finish()
        val (_, s) = getStreamBlocking(incoming)!!
        assertEquals(Code.H3_MESSAGE_ERROR, failsWith<StreamError.Stream> { s.recvData() }.code)
    }

    @Test
    fun pseudoHeaderInTrailersIsMessageError() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        launch { incoming.run() }
        cq.openUni().send(controlWithSettings())
        val raw = cq.openBi()
        val block = Buffer()
        neton.http.h3.qpack.Encoder().encodeStateless(block, listOf(neton.http.h3.qpack.HeaderField(":path", "/x")))
        raw.send(Buffer().request(Request.post("http://localhost/").body(Unit)).frameWithPayload(Frame.Headers(block.toBytes())))
        raw.finish()
        val (_, s) = getStreamBlocking(incoming)!!
        assertNull(s.recvData())
        assertEquals(Code.H3_MESSAGE_ERROR, failsWith<StreamError.Stream> { s.recvTrailers() }.code)
    }

    @Test
    fun emptyDataFrameDoesNotEndTheBody() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        launch { incoming.run() }
        cq.openUni().send(controlWithSettings())
        val raw = cq.openBi()
        raw.send(Buffer().request(Request.post("http://localhost/").body(Unit)).frameWithPayload(dataFrame("")).frameWithPayload(dataFrame("after")))
        raw.finish()
        val (_, s) = getStreamBlocking(incoming)!!
        assertEquals("after", s.recvData()!!.decodeToString())
        assertNull(s.recvData())
    }

    @Test
    fun splitStreamsWorkFromDifferentCoroutines() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val (driver, send) = newClient(cq)
        launch { driver.run() }
        val stream = send.sendRequest(Request.post("http://localhost/echo").body(Unit))
        val (reqSend, reqRecv) = stream.split()
        val server = launch {
            val (_, s) = getStreamBlocking(incoming)!!
            val (out, inp) = s.split()
            out.sendResponse(ok())
            while (true) out.sendData(inp.recvData() ?: break)
            out.finish()
        }
        val reader = async {
            assertEquals(StatusCode.OK, reqRecv.recvResponse().status)
            val sb = StringBuilder()
            while (true) sb.append((reqRecv.recvData() ?: break).decodeToString())
            sb.toString()
        }
        for (i in 0 until 5) reqSend.sendData(bytes("chunk$i;"))
        reqSend.finish()
        assertEquals("chunk0;chunk1;chunk2;chunk3;chunk4;", reader.await())
        server.join()
    }
}

/** [ConnectionLayerTest] on the in-memory QUIC double. */
class ConnectionLayerTestMemory : ConnectionLayerTest() {
    override val quic = MEMORY_QUIC
}

/** [ConnectionLayerTest] on neton.quic over loopback UDP (handshake: the TLS test double). */
class ConnectionLayerTestQuic : ConnectionLayerTest() {
    override val quic = NETON_QUIC
}
