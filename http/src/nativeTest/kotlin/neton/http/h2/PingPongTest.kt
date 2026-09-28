package neton.http.h2

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import neton.http.Response
import neton.http.h2.frame.GoAway
import neton.http.h2.frame.Ping as PingFrame
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from h2 0.4.19 tests/h2-tests/tests/ping_pong.rs (5 tests).

class PingPongTest {
    @Test
    fun recvSinglePing() = h2Test {
        val (m, mock) = mockNew()

        // Create the handshake
        val h2 = launch {
            val (client, conn) = neton.http.h2.client.handshake(m)
            conn.run()
            client.close()
        }

        mock.assertClientHandshake()
        mock.send(PingFrame(0L))
        val pong = assertIs<PingFrame>(mock.next())

        // Payload is correct
        assertEquals(0L, pong.payload)

        // Is ACK
        assertTrue(pong.isAck)

        mock.close()
        h2.join()
    }

    @Test
    fun recvMultiplePings() = h2Test {
        val (io, client) = mockNew()

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.ping(ByteArray(8) { 1 }))
            client.sendFrame(Frames.ping(ByteArray(8) { 2 }))
            client.recvFrame(Frames.ping(ByteArray(8) { 1 }).pong())
            client.recvFrame(Frames.ping(ByteArray(8) { 2 }).pong())
            client.close()
        }

        val s = neton.http.h2.server.handshake(io)
        launch { s.run() }
        assertNull(s.accept())
        c.join()
    }

    @Test
    fun pongHasHighestPriority() = h2Test {
        val (io, client) = mockNew()

        val data = ByteArray(16_384)

        val c = launch {
            val settings = client.assertServerHandshake()
            assertFrameEq(settings, Settings())
            client.sendFrame(Frames.headers(1).request("POST", "https://http2.akamai.com/"))
            client.sendFrame(Frames.data(1, data).eos())
            client.sendFrame(Frames.ping(ByteArray(8) { 1 }))
            client.recvFrame(Frames.ping(ByteArray(8) { 1 }).pong())
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }

        val s = neton.http.h2.server.handshake(io)
        launch { s.run() }
        val (req, stream) = s.accept()!!
        assertEquals("POST", req.method.toString())
        val body = concat(req.body)
        assertEquals(data.size, body.size)
        val res = Response.builder().status(200).body(Unit)
        stream.sendResponse(res, true)
        assertNull(s.accept())
        c.join()
    }

    @Test
    fun userPingPong() = h2Test {
        val (io, srv) = mockNew()

        val server = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            srv.recvFrame(PingFrame(PingFrame.USER))
            srv.sendFrame(PingFrame.pong(PingFrame.USER))
            srv.recvFrame(GoAway(StreamId(0), Reason.NO_ERROR))
            srv.recvEof()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val connTask = async { conn.run() }
        // yield once so we can ack server settings
        yieldOnce()
        val pingPong = conn.pingPong()!!
        pingPong.sendPing(Ping.opaque())

        // multiple pings results in a user error...
        val err = assertFailsWith<H2Error>("ping 2") { pingPong.sendPing(Ping.opaque()) }
        assertEquals("user error: send_ping before received previous pong", err.message, "send_ping while ping pending is a user error")

        pingPong.awaitPong()
        client.close()
        connTask.await()
        server.join()
    }

    @Test
    fun userNotifiesWhenConnectionCloses() = h2Test {
        val (io, srv) = mockNew()
        val settingsDone = CompletableDeferred<Unit>()
        val server = launch {
            val settings = srv.assertClientHandshake()
            assertFrameEq(settings, Settings())
            settingsDone.complete(Unit)
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        val connTask = launch { conn.run() }
        // yield once so we can ack server settings
        settingsDone.await()
        server.join()

        val pingPong = conn.pingPong()!!

        // Park a coroutine waiting for the pong, then drop the connection and be sure the parked one is notified...
        val parked = async {
            assertFailsWith<H2Error>("poll_pong should error") { pingPong.awaitPong() }
        }

        // Sleep to let the ping task park...
        idleMs(50)
        // `drop(client)`: the connection future (here the coroutine running it) goes away.
        connTask.cancel()
        connTask.join()
        srv.close()

        parked.await()

        // Now that the connection is closed, also test `send_ping` errors...
        val err = assertFailsWith<H2Error>("send_ping") { pingPong.sendPing(Ping.opaque()) }
        assertEquals("broken pipe", err.message)
        client.close()
    }
}
