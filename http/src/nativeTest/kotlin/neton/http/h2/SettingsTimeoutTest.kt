package neton.http.h2

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

// ⚖️ SPEC §4.3: the optional SETTINGS ACK timeout (RFC 9113 §6.5.3), which the reference does not have. Off by
// default, as the reference behaves.

class SettingsTimeoutTest {
    @Test
    fun settingsNotAcknowledgedInTimeIsSettingsTimeout() = h2Test {
        val (io, srv) = mockNew()
        val server = launch {
            srv.send(Settings())
            srv.readPreface()
            assertIs<Settings>(srv.next())
            // Our ACK for the client's SETTINGS never comes; theirs for ours is expected, then the GOAWAY.
            val ack = assertIs<Settings>(srv.next())
            assertTrue(ack.isAck)
            srv.recvFrame(Frames.goAway(0).reason(Reason.SETTINGS_TIMEOUT))
            srv.recvEof()
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.Builder().settingsAckTimeout(50.milliseconds).handshake(io)
        val err = assertFailsWith<H2Error> { conn.run() }
        assertEquals(Reason.SETTINGS_TIMEOUT, err.reason())
        assertTrue(err.isGoAway && err.isLibrary)
        client.close()
        server.join()
    }

    @Test
    fun settingsAcknowledgedInTimeKeepsTheConnection() = h2Test {
        val (io, client) = mockNew()
        val c = launch {
            client.assertServerHandshake()
            idleMs(100)
            client.pingPong(ByteArray(8) { 3 })
            client.close()
        }

        val srv = neton.http.h2.server.Builder().settingsAckTimeout(50.milliseconds).handshake(io)
        val running = async { srv.run() }
        kotlin.test.assertNull(srv.accept())
        running.await()
        c.join()
    }

    @Test
    fun noSettingsTimeoutByDefault() = h2Test {
        val (io, srv) = mockNew()
        val server = launch {
            srv.send(Settings())
            srv.readPreface()
            assertIs<Settings>(srv.next())
            assertTrue(assertIs<Settings>(srv.next()).isAck)
            // Never acknowledge the client's SETTINGS: nothing happens.
            idleMs(100)
            srv.pingPong(ByteArray(8) { 4 })
            srv.close()
        }

        val (client, conn) = neton.http.h2.client.handshake(io)
        conn.run()
        client.close()
        server.join()
    }
}
