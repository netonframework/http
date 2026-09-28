package neton.http.h3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import neton.http.Request
import neton.http.h3.proto.Frame
import neton.http.h3.proto.StreamId
import neton.http.h3.proto.StreamType
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import neton.http.h3.client.builder as clientBuilder
import neton.http.h3.client.newClient
import neton.http.h3.server.builder as serverBuilder
import neton.http.h3.server.newConnection

// Port of h3 0.0.8 `src/tests/connection.rs` (19 tests) onto the in-memory QUIC double (SPEC §5, layer 2). quinn's
// endpoints become `memoryQuicPair`; the reference's `tokio::join!` / `select!` become coroutines. Differences in the
// port, test by test, are noted where they occur; the ⚖️ GOAWAY boundary (SPEC §5) changes no assertion here.
class ConnectionTest {
    @Test
    fun connect() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val built = CompletableDeferred<Unit>()
        val client = async {
            val (drive, _) = newClient(cq)
            built.complete(Unit)
            assertRemote(Code.H3_NO_ERROR, drive.run())
        }
        val server = newConnection(sq)
        // Port: quinn delivers the close after the client is set up; the double closes at once, so wait for that.
        built.await()
        server.close() // the reference drops the server connection
        client.await()
    }

    @Test
    fun accept_request_end_on_client_close() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val accepted = CompletableDeferred<Unit>()
        val client = launch {
            val (driver, send) = newClient(cq)
            launch { driver.run() }
            // Wait for the server to accept the connection; closing the only sender sends H3_NO_ERROR.
            accepted.await()
            send.close()
        }
        val incoming = newConnection(sq)
        launch { incoming.run() }
        accepted.complete(Unit)
        assertRemote(Code.H3_NO_ERROR, failsWith<ConnectionError> { incoming.accept() })
        client.join()
    }

    @Test
    fun server_drop_close() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val sent = CompletableDeferred<Unit>()
        val server = launch {
            val s = newConnection(sq)
            // Port: in the reference the server connection is dropped while the request is in flight (quinn delivers
            // the close later); here the close is immediate, so the server waits for the request to be sent first.
            sent.await()
            s.close()
        }
        val (conn, send) = newClient(cq)
        val drive = async { conn.run() }
        val stream = send.sendRequest(Request.get("http://no.way").body(Unit))
        sent.complete(Unit)
        val err = failsWith<StreamError> { stream.recvResponse() }
        assertRemote(Code.H3_NO_ERROR, assertIs<StreamError.Connection>(err).error)
        assertRemote(Code.H3_NO_ERROR, drive.await())
        server.join()
    }

    // The client calls sendData() without finish(): the body stream stays open, and the server reads the data and
    // responds.
    @Test
    fun server_send_data_without_finish() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val client = launch {
            val (_, send) = newClient(cq)
            val req = send.sendRequest(Request.get("http://no.way").body(Unit))
            req.sendData(neton.io.bytes.Bytes.wrap(ByteArray(100)))
            req.recvResponse()
        }
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val (_, stream) = incoming.accept()!!.resolveRequest()
        val data = stream.recvData()!!
        assertEquals(100, data.size)
        response(stream)
        client.join()
    }

    @Test
    fun client_close_only_on_last_sender_drop() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val server = launch {
            val incoming = newConnection(sq)
            launch { incoming.run() }
            val (_, s1) = incoming.accept()!!.resolveRequest()
            s1.stopStream(Code.H3_REQUEST_CANCELLED)
            val (_, s2) = incoming.accept()!!.resolveRequest()
            s2.stopStream(Code.H3_REQUEST_CANCELLED)
            assertRemote(Code.H3_NO_ERROR, failsWith<ConnectionError> { incoming.accept() })
        }
        val (conn, send1) = newClient(cq)
        val send2 = send1.clone()
        val r1 = send1.sendRequest(Request.get("http://no.way").body(Unit))
        assertEquals(Code.H3_REQUEST_CANCELLED, failsWith<StreamError.RemoteTerminate> { r1.recvResponse() }.code)
        r1.finish()
        val r2 = send2.sendRequest(Request.get("http://no.way").body(Unit))
        assertEquals(Code.H3_REQUEST_CANCELLED, failsWith<StreamError.RemoteTerminate> { r2.recvResponse() }.code)
        r2.finish()
        send1.close()
        send2.close()
        assertLocal(Code.H3_NO_ERROR, conn.run())
        server.join()
    }

    @Test
    fun settings_exchange_client() = h3Test {
        // RFC 9114 §3.2: a SETTINGS frame is the first frame of each endpoint's control stream.
        val (cq, sq) = memoryQuicPair()
        val server = launch {
            val incoming = serverBuilder().maxFieldSectionSize(12).build(sq)
            launch { incoming.run() }
            incoming.accept()
        }
        val (conn, client) = newClient(cq)
        val drive = launch { conn.run() }
        eventually(message = "peer's max_field_section_size didn't change") { client.settings.maxFieldSectionSize == 12L }
        assertTrue(drive.isActive, "driver resolved first")
        server.cancel()
    }

    @Test
    fun settings_exchange_server() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val client = launch {
            val (conn, _) = clientBuilder().maxFieldSectionSize(12).build(cq)
            conn.run()
        }
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val accept = async { incoming.accept() }
        eventually(message = "peer's max_field_section_size didn't change") { incoming.peerSettings?.maxFieldSectionSize == 12L }
        assertTrue(accept.isActive, "server resolved first")
        accept.cancel()
        client.cancel()
    }

    @Test
    fun client_error_on_bidi_recv() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val server = launch {
            // A raw server opening a bidirectional stream.
            val s = sq.openBi()
            repeat(1000) {
                try {
                    s.write(bytes("I'm not really a server"))
                } catch (e: StreamErrorIncoming.ConnectionLost) {
                    val close = assertIs<ConnectionErrorIncoming.ApplicationClose>(e.connectionError)
                    assertEquals(Code.H3_STREAM_CREATION_ERROR.value, close.errorCode)
                    return@launch
                }
                delay(10)
            }
            fail("did not get the expected error")
        }
        val (conn, send) = newClient(cq)
        // RFC 9114 §6.1: a server-initiated bidirectional stream is H3_STREAM_CREATION_ERROR.
        val err = assertLocal(Code.H3_STREAM_CREATION_ERROR, conn.run())
        assertTrue((err.error as LocalError.Application).reason.startsWith("client received a server-initiated bidirectional stream"))
        val e = failsWith<StreamError> { send.sendRequest(Request.get("http://no.way").body(Unit)) }
        val local = assertLocal(Code.H3_STREAM_CREATION_ERROR, assertIs<StreamError.Connection>(e).error)
        assertTrue((local.error as LocalError.Application).reason.startsWith("client received a server-initiated bidirectional stream"))
        server.join()
    }

    @Test
    fun two_control_streams() = h3Test {
        // RFC 9114 §6.2.1: a second control stream is H3_STREAM_CREATION_ERROR.
        val (cq, sq) = memoryQuicPair()
        repeat(2) { cq.openUni().send(Buffer().varint(StreamType.CONTROL)) }
        val incoming = newConnection(sq)
        launch { incoming.run() }
        assertLocal(Code.H3_STREAM_CREATION_ERROR, failsWith<ConnectionError> { incoming.accept() })
    }

    @Test
    fun control_close_send_error() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val client = launch {
            val control = cq.openUni()
            control.send(Buffer().varint(StreamType.CONTROL))
            // RFC 9114 §6.2.1: a control stream closed at any point is H3_CLOSED_CRITICAL_STREAM.
            control.finish()
            val error = try {
                while (true) cq.acceptBi()
                @Suppress("UNREACHABLE_CODE")
                null
            } catch (e: ConnectionErrorIncoming) {
                e
            }
            assertEquals(ConnectionErrorIncoming.ApplicationClose(Code.H3_CLOSED_CRITICAL_STREAM.value), error)
        }
        val incoming = newConnection(sq)
        launch { incoming.run() }
        // The driver finds that the client's control stream was closed.
        val e1 = assertLocal(Code.H3_CLOSED_CRITICAL_STREAM, failsWith<ConnectionError> { incoming.accept() })
        assertTrue((e1.error as LocalError.Application).reason.startsWith("control stream was closed"))
        // Again: the stored error.
        val e2 = assertLocal(Code.H3_CLOSED_CRITICAL_STREAM, failsWith<ConnectionError> { incoming.accept() })
        assertTrue((e2.error as LocalError.Application).reason.startsWith("control stream was closed"))
        client.join()
    }

    @Test
    fun missing_settings() = h3Test {
        // RFC 9114 §6.2.1: another first frame on the control stream is H3_MISSING_SETTINGS.
        val (cq, sq) = memoryQuicPair()
        cq.openUni().send(Buffer().varint(StreamType.CONTROL).frame(Frame.CancelPush(0)))
        val incoming = newConnection(sq)
        launch { incoming.run() }
        assertLocal(Code.H3_MISSING_SETTINGS, failsWith<ConnectionError> { incoming.accept() })
    }

    @Test
    fun control_stream_frame_unexpected() = h3Test {
        // RFC 9114 §7.2.1: DATA on a control stream is H3_FRAME_UNEXPECTED.
        val (cq, sq) = memoryQuicPair()
        val control = cq.openUni()
        control.send(controlWithSettings())
        control.send(Buffer().frame(dataFrame("")))
        val incoming = newConnection(sq)
        launch { incoming.run() }
        assertLocal(Code.H3_FRAME_UNEXPECTED, failsWith<ConnectionError> { incoming.accept() })
    }

    @Test
    fun timeout_on_control_frame_read() = h3Test {
        // Port: the reference's 10 ms quinn idle timeout; 300 ms here (loaded test machines).
        val (cq, sq) = memoryQuicPair(this, idleTimeout = 300.milliseconds)
        val client = launch {
            val (driver, _) = newClient(cq)
            driver.run()
        }
        val incoming = newConnection(sq)
        launch { incoming.run() }
        assertIs<ConnectionError.Timeout>(failsWith<ConnectionError> { incoming.accept() })
        client.join()
    }

    @Test
    fun goaway_from_server_not_request_id() = h3Test {
        val (cq, sq) = memoryQuicPair()
        // A raw server: SETTINGS, then GOAWAY with a server-initiated unidirectional stream ID.
        // RFC 9114 §7.2.6: a GOAWAY with a stream ID of another type is H3_ID_ERROR for the client.
        val control = sq.openUni()
        // The reference's `0 << 2 | 0 << 1 | 1`: 1, a server-initiated stream ID.
        val id = 1L
        assertTrue(!StreamId(id).isRequest)
        control.send(controlWithSettings().frame(Frame.Goaway(id)))
        val (driver, _) = newClient(cq)
        assertLocal(Code.H3_ID_ERROR, driver.run())
    }

    @Test
    fun graceful_shutdown_server_rejects() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val done = CompletableDeferred<Unit>()
        val server = launch {
            val incoming = newConnection(sq)
            launch { incoming.run() }
            val (_, stream) = incoming.accept()!!.resolveRequest()
            response(stream)
            incoming.shutdown(0)
            assertNull(incoming.accept())
            // The reference waits for the endpoint to go idle: keep the connection until the client is done.
            done.await()
            incoming.close()
        }
        val (_, send) = newClient(cq)
        val first = send.sendRequest(Request.get("http://no.way").body(Unit))
        val rejected = send.sendRequest(Request.get("http://no.way").body(Unit))
        first.recvResponse()
        assertEquals(Code.H3_REQUEST_REJECTED, failsWith<StreamError.RemoteTerminate> { rejected.recvResponse() }.code)
        done.complete(Unit)
        server.join()
    }

    @Test
    fun graceful_shutdown_grace_interval() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val server = launch {
            val incoming = newConnection(sq)
            launch { incoming.run() }
            val (_, first) = getStreamBlocking(incoming)!!
            incoming.shutdown(1)
            val (_, inFlight) = getStreamBlocking(incoming)!!
            response(first)
            response(inFlight)
            while (true) {
                val (_, stream) = getStreamBlocking(incoming) ?: break
                response(stream)
            }
        }
        val (driver, send) = newClient(cq)
        // Sent while the connection is not shutting down.
        val first = send.sendRequest(Request.get("http://no.way").body(Unit))
        // Sent while the connection is shutting down, but before the GOAWAY is received.
        val inFlight = send.sendRequest(Request.get("http://no.way").body(Unit))
        first.recvResponse()
        inFlight.recvResponse()
        val drive = async { driver.run() }
        // Not sent: the client's driver already received the GOAWAY. Port: the reference sleeps 15 ms for that; here
        // the test waits until the driver has seen it.
        eventually { driver.isClosing }
        failsWith<StreamError.RemoteClosing> { request(send) }
        send.close()
        assertLocal(Code.H3_NO_ERROR, drive.await())
        server.join()
    }

    @Test
    fun graceful_shutdown_closes_when_idle() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val server = async {
            val incoming = newConnection(sq)
            launch { incoming.run() }
            var count = 0
            while (true) {
                val (_, stream) = getStreamBlocking(incoming) ?: break
                count++
                if (count == 4) incoming.shutdown(2)
                response(stream)
            }
            incoming.close()
            count
        }
        val (driver, send) = newClient(cq)
        // Continuous requests, ignoring the GOAWAY because the connection is not driven.
        while (true) {
            try {
                request(send)
            } catch (e: StreamError) {
                break
            }
            yield()
        }
        assertRemote(Code.H3_NO_ERROR, driver.run())
        // The reference bounds the server with 100 ms; the bound here is the test's (loaded machines).
        assertEquals(6, server.await())
    }

    @Test
    fun graceful_shutdown_client() = h3Test {
        val (cq, sq) = memoryQuicPair()
        val server = launch {
            val incoming = newConnection(sq)
            launch { incoming.run() }
            assertNull(incoming.accept())
            incoming.close()
        }
        val (driver, _) = newClient(cq)
        driver.shutdown(0)
        assertRemote(Code.H3_NO_ERROR, driver.run())
        server.join()
    }

    // The server still processes the connection while a request stream is started but sends nothing more.
    @Test
    fun server_not_blocking_on_idle_request() = h3Test(100.seconds) {
        val (cq, sq) = memoryQuicPair()
        val client = launch {
            val control = cq.openUni()
            control.send(controlWithSettings())
            val controlRecv = cq.acceptUni()
            // An idle request stream: a HEADERS frame header whose payload never comes.
            val request = cq.openBi()
            request.send(Buffer().frame(Frame.headers("test")))
            delay(10)
            // A wrong frame on the control stream.
            control.send(Buffer().frame(dataFrame("this frame should cause the server to respond with an error")))
            controlRecv.read()
            val err = failsWith<ConnectionErrorIncoming> { cq.acceptBi() }
            assertEquals(ConnectionErrorIncoming.ApplicationClose(Code.H3_FRAME_UNEXPECTED.value), err)
        }
        val incoming = newConnection(sq)
        launch { incoming.run() }
        val resolver = incoming.accept()!!
        val req1 = async { failsWith<StreamError> { resolver.resolveRequest() } }
        assertLocal(Code.H3_FRAME_UNEXPECTED, failsWith<ConnectionError> { incoming.accept() })
        assertNotNull(req1.await())
        client.join()
    }
}
