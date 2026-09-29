package neton.http.h3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import neton.http.Request
import neton.http.StatusCode
import neton.http.h3.proto.Frame
import neton.http.h3.proto.FrameType
import neton.http.h3.proto.Settings as SettingsFrame
import neton.http.h3.quic.ConnectionErrorIncoming
import neton.http.h3.quic.StreamErrorIncoming
import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import neton.http.h3.client.builder as clientBuilder
import neton.http.h3.client.newClient
import neton.http.h3.server.builder as serverBuilder
import neton.http.h3.server.newConnection

// Port of h3 0.0.8 `src/tests/request.rs` (38 tests) onto the thin QUIC interface; it runs on the in-memory
// double (SPEC §5 layer 2, RequestTestMemory) and on neton.quic over loopback UDP (layer 3, RequestTestQuic). Differences
// in the port are noted test by test. The expected sizes in the header-limit tests are the reference's (name + value +
// 32 per line, RFC 9114 §4.2.2): `GET http://localhost/salut` is 179, its `:method` line alone 42.
abstract class RequestTest {
    /** The QUIC the scenarios run on: the in-memory double (layer 2) or neton.quic on loopback UDP (layer 3). */
    abstract val quic: QuicPairFactory

    private suspend fun CoroutineScope.quicPair(idleTimeout: Duration? = null) = with(quic) { pair(idleTimeout, null) }

    @Test
    fun get() = h3Test {
        val (cq, sq) = quicPair()
        val client = launch {
            val (driver, client) = newClient(cq)
            launch { driver.run() }
            val stream = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
            val response = stream.recvResponse()
            assertEquals(StatusCode.OK, response.status)
            assertEquals("wonderful hypertext", stream.recvData()!!.decodeToString())
            client.close()
        }
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val (_, stream) = getStreamBlocking(incoming)!!
        stream.sendResponse(ok())
        stream.sendData(bytes("wonderful hypertext"))
        stream.finish()
        assertRemote(Code.H3_NO_ERROR, failsWith<ConnectionError> { incoming.accept() })
        client.join()
    }

    @Test
    fun get_with_trailers_unknown_content_type() = h3Test {
        val (cq, sq) = quicPair()
        val client = launch {
            val (driver, client) = newClient(cq)
            launch { driver.run() }
            val stream = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
            stream.recvResponse()
            assertNotNull(stream.recvData())
            assertNull(stream.recvData())
            val trailers = stream.recvTrailers()!!
            assertTrue(trailers["trailer"]!!.contentEquals("value"))
            client.close()
        }
        serveWithTrailers(sq)
        client.join()
    }

    @Test
    fun get_with_trailers_known_content_type() = h3Test {
        val (cq, sq) = quicPair()
        val client = launch {
            val (driver, client) = newClient(cq)
            launch { driver.run() }
            val stream = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
            stream.recvResponse()
            assertNotNull(stream.recvData())
            // recvTrailers without recvData returning null first.
            val trailers = stream.recvTrailers()!!
            assertTrue(trailers["trailer"]!!.contentEquals("value"))
            client.close()
        }
        serveWithTrailers(sq)
        client.join()
    }

    private suspend fun CoroutineScope.serveWithTrailers(sq: neton.http.h3.quic.Connection) {
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val (_, stream) = getStreamBlocking(incoming)!!
        stream.sendResponse(ok())
        stream.sendData(bytes("wonderful hypertext"))
        stream.sendTrailers(headers("trailer" to "value"))
        stream.finish()
        assertRemote(Code.H3_NO_ERROR, failsWith<ConnectionError> { incoming.accept() })
    }

    @Test
    fun post() = h3Test {
        val (cq, sq) = quicPair()
        val client = launch {
            val (driver, client) = newClient(cq)
            launch { driver.run() }
            val stream = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
            stream.sendData(bytes("wonderful json"))
            stream.finish()
            stream.recvResponse()
            client.close()
        }
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val (_, stream) = getStreamBlocking(incoming)!!
        stream.sendResponse(ok())
        assertEquals("wonderful json", stream.recvData()!!.decodeToString())
        stream.finish()
        // Keep the connection until the client is finished.
        assertRemote(Code.H3_NO_ERROR, failsWith<ConnectionError> { incoming.accept() })
        client.join()
    }

    @Test
    fun header_too_big_response_from_server() = h3Test {
        val (cq, sq) = quicPair()
        val client = launch {
            val (driver, client) = newClient(cq)
            launch { driver.run() }
            val stream = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
            stream.finish()
            assertEquals(StatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE, stream.recvResponse().status)
            client.close()
        }
        // RFC 9114 §4.2.2: an implementation MAY limit the size of the message header it accepts.
        val incoming = serverBuilder().maxFieldSectionSize(12).build(sq)
        launch { incoming.run() }
        val resolver = incoming.accept()!!
        val err = failsWith<StreamError.HeaderTooBig> { resolver.resolveRequest() }
        assertEquals(42L, err.actualSize)
        assertEquals(12L, err.maxSize)
        // The connection ends without an error.
        assertRemote(Code.H3_NO_ERROR, failsWith<ConnectionError> { incoming.accept() })
        client.join()
    }

    @Test
    fun header_too_big_response_from_server_trailers() = h3Test {
        val (cq, sq) = quicPair()
        val client = launch {
            val (driver, client) = newClient(cq)
            launch { driver.run() }
            val stream = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
            stream.sendData(bytes("wonderful json"))
            stream.sendTrailers(headers("trailer" to "A".repeat(200)))
            stream.finish()
            runCatching { stream.recvResponse() }
            client.close()
        }
        val incoming = serverBuilder().maxFieldSectionSize(207).build(sq)
        launch { incoming.run() }
        val (_, stream) = getStreamBlocking(incoming)!!
        assertNotNull(stream.recvData())
        val err = failsWith<StreamError.HeaderTooBig> { stream.recvTrailers() }
        assertEquals(239L, err.actualSize)
        assertEquals(207L, err.maxSize)
        stream.close()
        client.join()
    }

    @Test
    fun header_too_big_client_error() = h3Test {
        val (cq, sq) = quicPair()
        val server = launch {
            val incoming = serverBuilder().maxFieldSectionSize(12).build(sq)
            launch { incoming.run() }
            val resolver = incoming.accept()!!
            // The client sends nothing on the stream: H3_REQUEST_INCOMPLETE.
            val err = failsWith<StreamError.Stream> { resolver.resolveRequest() }
            assertEquals(Code.H3_REQUEST_INCOMPLETE, err.code)
            // The reference's server future ends here, dropping the connection.
            incoming.close()
        }
        val (driver, client) = newClient(cq)
        val drive = async { driver.run() }
        // Pretend the client already received the server's settings.
        client.setPeerSettings(Settings(12))
        val err = failsWith<StreamError.HeaderTooBig> { client.sendRequest(Request.get("http://localhost/salut").body(Unit)) }
        assertEquals(179L, err.actualSize)
        assertEquals(12L, err.maxSize)
        server.join()
        assertRemote(Code.H3_NO_ERROR, drive.await())
    }

    @Test
    fun header_too_big_client_error_trailer() = h3Test {
        val (cq, sq) = quicPair()
        val received = CompletableDeferred<Unit>()
        val server = launch {
            val incoming = serverBuilder().maxFieldSectionSize(207).build(sq)
            launch { incoming.run() }
            val (_, stream) = getStreamBlocking(incoming)!!
            assertNotNull(stream.recvData())
            received.complete(Unit)
            runCatching { incoming.accept() }
        }
        val (driver, client) = newClient(cq)
        // Port: the reference asserts the driver ends by the QUIC idle timeout; the double has none by default, so
        // the driver is left to be cancelled at the end.
        launch { driver.run() }
        client.setPeerSettings(Settings(200))
        val stream = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
        stream.sendData(bytes("wonderful json"))
        val err = failsWith<StreamError.HeaderTooBig> { stream.sendTrailers(headers("trailer" to "A".repeat(200))) }
        assertEquals(239L, err.actualSize)
        assertEquals(200L, err.maxSize)
        stream.finish()
        // Port: the double runs the client to its end before the server starts; keep the connection until the server
        // has read the body.
        received.await()
        client.close()
        server.join()
    }

    @Test
    fun header_too_big_discard_from_client() = h3Test {
        val (cq, sq) = quicPair()
        val server = launch {
            val incoming = newConnection(sq)
            launch { incoming.run() }
            val (_, stream) = getStreamBlocking(incoming)!!
            stream.sendResponse(ok())
            // Keep sending until the client cancels the stream.
            var err: StreamError? = null
            for (i in 0 until 1000) {
                try {
                    stream.sendData(bytes("some data"))
                } catch (e: StreamError) {
                    err = e
                    break
                }
                delay(2)
            }
            assertEquals(Code.H3_REQUEST_CANCELLED, assertIs<StreamError.RemoteTerminate>(err).code)
            runCatching { incoming.accept() }
            // The reference's server future ends here, dropping the connection.
            incoming.close()
        }
        // RFC 9114 §4.2.2: SHOULD NOT send a header over the peer's limit. The client does not send its settings, so
        // the server does not know its limit.
        val (driver, client) = clientBuilder().maxFieldSectionSize(12).sendSettings(false).build(cq)
        launch { driver.run() }
        val stream = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
        stream.finish()
        val err = failsWith<StreamError.HeaderTooBig> { stream.recvResponse() }
        assertEquals(42L, err.actualSize)
        assertEquals(12L, err.maxSize)
        val second = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
        second.finish()
        failsWith<StreamError> { second.recvResponse() }
        server.join()
    }

    @Test
    fun header_too_big_discard_from_client_trailers() = h3Test {
        val (cq, sq) = quicPair()
        val server = launch {
            val incoming = newConnection(sq)
            launch { incoming.run() }
            val (_, stream) = getStreamBlocking(incoming)!!
            stream.sendResponse(ok())
            stream.sendData(bytes("wonderful hypertext"))
            stream.sendTrailers(headers("trailer" to "value".repeat(100)))
            stream.finish()
            runCatching { incoming.accept() }
        }
        val (driver, client) = clientBuilder().maxFieldSectionSize(200).sendSettings(false).build(cq)
        launch { driver.run() }
        val stream = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
        stream.recvResponse()
        stream.recvData()
        val err = failsWith<StreamError.HeaderTooBig> { stream.recvTrailers() }
        assertEquals(539L, err.actualSize)
        assertEquals(200L, err.maxSize)
        stream.finish()
        client.close()
        server.join()
    }

    @Test
    fun header_too_big_server_error() = h3Test {
        val (cq, sq) = quicPair()
        val client = launch {
            val (driver, client) = newClient(cq)
            launch { driver.run() }
            runCatching { client.sendRequest(Request.get("http://localhost/salut").body(Unit)).recvResponse() }
        }
        val incoming = newConnection(sq)
        // Pretend the server received a smaller limit (before its driver reads the client's SETTINGS).
        incoming.setPeerSettings(Settings(12))
        launch { incoming.run() }
        val (_, stream) = getStreamBlocking(incoming)!!
        // RFC 9114 §4.2.2: SHOULD NOT send a header over the peer's limit.
        val err = failsWith<StreamError.HeaderTooBig> { stream.sendResponse(ok()) }
        assertEquals(42L, err.actualSize)
        assertEquals(12L, err.maxSize)
        client.cancel()
    }

    @Test
    fun header_too_big_server_error_trailers() = h3Test {
        val (cq, sq) = quicPair()
        val client = launch {
            val (driver, client) = newClient(cq)
            launch { driver.run() }
            runCatching { client.sendRequest(Request.get("http://localhost/salut").body(Unit)).recvResponse() }
        }
        val incoming = newConnection(sq)
        // Pretend the server already received the client's settings.
        incoming.setPeerSettings(Settings(42))
        launch { incoming.run() }
        val (_, stream) = getStreamBlocking(incoming)!!
        stream.sendResponse(ok())
        stream.sendData(bytes("wonderful hypertext"))
        val err = failsWith<StreamError.HeaderTooBig> { stream.sendTrailers(headers("trailer" to "value".repeat(100))) }
        assertEquals(539L, err.actualSize)
        assertEquals(42L, err.maxSize)
        client.cancel()
    }

    // The timeout tests use the double's idle timeout for quinn's; the reference's 100–200 ms become 300 ms, and the
    // peer that stays silent waits for the connection to end instead of sleeping a fixed time (loaded machines).

    @Test
    fun get_timeout_client_recv_response() = h3Test {
        val (cq, sq) = quicPair(idleTimeout = 300.milliseconds)
        val server = launch {
            val incoming = newConnection(sq)
            launch { incoming.run() }
            // The request must not be dropped, else the connection would close.
            val req = incoming.accept()!!
            runCatching { incoming.accept() }
            req.close()
        }
        val (conn, client) = newClient(cq)
        val drive = async { conn.run() }
        val stream = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
        val err = failsWith<StreamError.Connection> { stream.recvResponse() }
        assertIs<ConnectionError.Timeout>(err.error)
        assertIs<ConnectionError.Timeout>(drive.await())
        server.join()
    }

    @Test
    fun get_timeout_client_recv_data() = h3Test {
        val (cq, sq) = quicPair(idleTimeout = 300.milliseconds)
        val server = launch {
            val incoming = newConnection(sq)
            launch { incoming.run() }
            val (_, stream) = getStreamBlocking(incoming)!!
            stream.sendResponse(ok())
            runCatching { incoming.accept() }
        }
        val (conn, client) = newClient(cq)
        val drive = async { conn.run() }
        val stream = client.sendRequest(Request.get("http://localhost/salut").body(Unit))
        stream.recvResponse()
        val err = failsWith<StreamError.Connection> { stream.recvData() }
        assertIs<ConnectionError.Timeout>(err.error)
        assertIs<ConnectionError.Timeout>(drive.await())
        server.join()
    }

    @Test
    fun get_timeout_server_accept() = h3Test {
        val (cq, sq) = quicPair(idleTimeout = 300.milliseconds)
        val client = launch {
            val (conn, _) = newClient(cq)
            assertIs<ConnectionError.Timeout>(conn.run())
        }
        val incoming = newConnection(sq)
        launch { incoming.run() }
        assertIs<ConnectionError.Timeout>(failsWith<ConnectionError> { incoming.accept() })
        client.join()
    }

    @Test
    fun post_timeout_server_recv_data() = h3Test {
        val (cq, sq) = quicPair(idleTimeout = 300.milliseconds)
        val client = launch {
            val (_, client) = newClient(cq)
            client.sendRequest(Request.post("http://localhost/salut").body(Unit))
            awaitCancellation()
        }
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val (_, stream) = getStreamBlocking(incoming)!!
        val err = failsWith<StreamError.Connection> { stream.recvData() }
        assertIs<ConnectionError.Timeout>(err.error)
        client.cancel()
    }

    // ---- RFC 9114 §4.1: frame sequences on a request stream ----
    // An HTTP message is a HEADERS frame, optionally DATA frames, optionally a trailer HEADERS frame; frames of unknown
    // types may come before, after or between them.

    private fun postRequest(): Request<Unit> = Request.post("http://localhost/salut").body(Unit)

    @Test
    fun request_valid_one_header() = requestSequenceOk { it.request(postRequest()) }

    @Test
    fun request_valid_header_data() = requestSequenceOk { it.request(postRequest()).frameWithPayload(dataFrame("fada")) }

    @Test
    fun request_valid_header_data_trailer() = requestSequenceOk {
        it.request(postRequest()).frameWithPayload(dataFrame("fada")).trailers(headers("trailer" to "value"))
    }

    @Test
    fun request_valid_header_multiple_data_trailer() = requestSequenceOk {
        it.request(postRequest()).frameWithPayload(dataFrame("fada")).frameWithPayload(dataFrame("fada")).frameWithPayload(dataFrame("fada"))
            .trailers(headers("trailer" to "value"))
    }

    @Test
    fun request_valid_header_trailer() = requestSequenceOk { it.request(postRequest()).trailers(headers("trailer" to "value")) }

    @Test
    fun request_valid_unknown_frame_before() = requestSequenceOk { it.unknownFrame().request(postRequest()) }

    @Test
    fun request_valid_unknown_frame_after_one_header() = requestSequenceOk { it.request(postRequest()).unknownFrame() }

    @Test
    fun request_valid_unknown_frame_interleaved_after_header() = requestSequenceOk {
        it.request(postRequest()).unknownFrame().frameWithPayload(dataFrame("fada"))
    }

    @Test
    fun request_valid_unknown_frame_interleaved_between_data() = requestSequenceOk {
        it.request(postRequest()).frameWithPayload(dataFrame("fada")).unknownFrame().frameWithPayload(dataFrame("fada"))
    }

    @Test
    fun request_valid_unknown_frame_interleaved_after_data() = requestSequenceOk {
        it.request(postRequest()).frameWithPayload(dataFrame("fada")).unknownFrame().frameWithPayload(dataFrame("fada"))
    }

    @Test
    fun request_valid_unknown_frame_interleaved_before_trailers() = requestSequenceOk {
        it.request(postRequest()).frameWithPayload(dataFrame("fada")).unknownFrame().trailers(headers("trailer" to "value"))
    }

    @Test
    fun request_valid_unknown_frame_after_trailers() = requestSequenceOk {
        it.request(postRequest()).frameWithPayload(dataFrame("fada")).trailers(headers("trailer" to "value")).unknownFrame()
    }

    /**
     * RFC 9114 §4.1: an invalid sequence of frames is H3_FRAME_UNEXPECTED; §7.2.3, §7.2.4, §7.2.6, §7.2.7: CANCEL_PUSH,
     * SETTINGS, GOAWAY and MAX_PUSH_ID on a request stream.
     */
    private fun invalidRequestFrames(): List<Frame> =
        listOf(Frame.CancelPush(0), SettingsFrame(), Frame.Goaway(1), Frame.MaxPushId(1))

    @Test
    fun request_invalid_frame_first() {
        for (frame in invalidRequestFrames()) requestSequenceUnexpected { it.frame(frame) }
    }

    @Test
    fun request_invalid_frame_after_header() {
        for (frame in invalidRequestFrames()) requestSequenceUnexpected { it.request(postRequest()).frame(frame) }
    }

    @Test
    fun request_invalid_frame_after_data() {
        for (frame in invalidRequestFrames()) {
            requestSequenceUnexpected { it.request(postRequest()).frameWithPayload(dataFrame("fada")).frame(frame) }
        }
    }

    @Test
    fun request_invalid_frame_after_trailers() {
        for (frame in invalidRequestFrames()) {
            requestSequenceUnexpected {
                it.request(postRequest()).frameWithPayload(dataFrame("fada")).trailers(headers("trailer" to "value")).frame(frame)
            }
        }
    }

    @Test
    fun request_invalid_data_after_trailers() = requestSequenceUnexpected {
        it.request(postRequest()).trailers(headers("trailer" to "value")).frameWithPayload(dataFrame("fada"))
    }

    @Test
    fun request_invalid_data_first() = requestSequenceUnexpected { it.frameWithPayload(dataFrame("fada")) }

    @Test
    fun request_invalid_two_trailers() = requestSequenceUnexpected {
        it.request(postRequest()).frameWithPayload(dataFrame("fada")).trailers(headers("trailer" to "value"))
            .trailers(headers("trailer" to "value"))
    }

    @Test
    fun request_invalid_trailing_byte() = requestSequenceFrameError {
        // RFC 9114 §7.1: a frame payload terminating before the end of the identified fields is H3_FRAME_ERROR.
        it.request(postRequest()).frameWithPayload(dataFrame("fada")).trailers(headers("trailer" to "value")).bytes(255)
    }

    @Test
    fun request_invalid_data_frame_length_too_large() = requestSequenceFrameError {
        it.request(postRequest()).varint(FrameType.DATA).varint(5).bytes(*"fada".encodeToByteArray().map { b -> b.toInt() }.toIntArray())
            .trailers(headers("trailer" to "value"))
    }

    @Test
    fun request_invalid_data_frame_length_too_short() = requestSequenceFrameError {
        it.request(postRequest()).varint(FrameType.DATA).varint(3).bytes(*"fada".encodeToByteArray().map { b -> b.toInt() }.toIntArray())
    }

    private fun requestSequenceOk(request: (Buffer) -> Unit) = requestSequenceCheck(null, request)

    private fun requestSequenceUnexpected(request: (Buffer) -> Unit) =
        requestSequenceCheck(Code.H3_FRAME_UNEXPECTED, request)

    private fun requestSequenceFrameError(request: (Buffer) -> Unit) = requestSequenceCheck(Code.H3_FRAME_ERROR, request)

    /**
     * `request_sequence_check`: an h3 client connection writes [request]'s bytes raw on a request stream and finishes
     * it; the h3 server reads the request (head, body, trailers). With [expected], the server closes the connection
     * with that code: its driver and its stream see the local error, the client's driver the peer's close. Without,
     * the exchange ends cleanly: the server stream reads everything, and closing the client's only sender closes the
     * connection with H3_NO_ERROR.
     *
     * Port: in the reference the server's request stream is dropped at the end, which quinn turns into a FIN; here the
     * server finishes its (empty) response explicitly. The reference's 100 ms pause before reading is not needed:
     * the raw client reads until the end of the stream or the connection.
     */
    private fun requestSequenceCheck(expected: Code?, request: (Buffer) -> Unit) = h3Test {
        val (cq, sq) = quicPair()
        val serverDone = CompletableDeferred<Unit>()
        val server = async {
            val incoming = newConnection(sq)
            launch { incoming.run() }
            val resolver = incoming.accept() ?: fail("request stream end unexpected")
            val driver = async { runCatching { incoming.accept() } }
            val stream = async {
                runCatching {
                    val (_, s) = resolver.resolveRequest()
                    while (s.recvData() != null) Unit
                    s.recvTrailers()
                    s.finish()
                }
            }
            val result = driver.await().exceptionOrNull() to stream.await().exceptionOrNull()
            serverDone.complete(Unit)
            result
        }
        val client = async {
            val (driver, send) = newClient(cq)
            val drive = async { driver.run() }
            val raw = cq.openBi()
            val buf = Buffer()
            request(buf)
            raw.send(buf)
            raw.finish()
            val read = runCatching { raw.drain() }
            // Close the only sender: no more requests. When the read failed the connection is gone: the driver ends
            // by itself, and closing first would race with it noticing the peer's close (the double closes
            // synchronously; over neton.quic the driver learns of the close on its next resumption).
            if (read.isSuccess) send.close()
            val result = read.exceptionOrNull() to drive.await()
            send.close()
            result
        }
        val (serverDriver, serverStream) = server.await()
        val (clientRead, clientDriver) = client.await()

        if (clientRead != null) {
            // Whether the stream reports the connection error is up to the QUIC layer, but it must be the expected one.
            val lost = assertIs<StreamErrorIncoming.ConnectionLost>(clientRead)
            assertEquals(ConnectionErrorIncoming.ApplicationClose(expected!!.value), lost.connectionError)
        }
        if (expected != null) {
            assertLocal(expected, serverDriver)
            assertRemote(expected, clientDriver)
            assertStreamLocal(expected, serverStream)
        } else {
            assertLocal(Code.H3_NO_ERROR, clientDriver)
            assertRemote(Code.H3_NO_ERROR, serverDriver)
            assertNull(serverStream, "the stream closes without error")
        }
    }
}

/** [RequestTest] on the in-memory QUIC double. */
class RequestTestMemory : RequestTest() {
    override val quic = MEMORY_QUIC
}

/** [RequestTest] on neton.quic over loopback UDP (handshake: the TLS test double). */
class RequestTestQuic : RequestTest() {
    override val quic = NETON_QUIC
}

/** [RequestTest] on neton.quic over loopback UDP with the real TLS 1.3 session. */
class RequestTestQuicTls : RequestTest() {
    override val quic = NETON_QUIC_TLS
}
