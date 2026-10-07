package neton.http.h2

import kotlinx.coroutines.delay
import neton.http.Body
import neton.http.EmptyBody
import neton.http.FullBody
import neton.http.HttpError
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.h1.HttpService
import neton.http.h1.HyperScope
import neton.http.h1.bytesOf
import neton.http.h1.connectLocal
import neton.http.h1.hyperTest
import neton.http.h1.listenLocal
import neton.http.h1.s
import neton.io.core.monotonicNanos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * ⚖️ The HTTP/2 server timeouts (hyper and h2 have none): [Http2ServerConfig.handshakeTimeout], [Http2ServerConfig.idleTimeout]
 * and [Http2ServerConfig.bodyReadTimeout]. Timers need a socket, so these run over loopback TCP.
 */
class ServerTimeoutsTest {
    /** Reads the whole body; 200 with its length, or 408 when the read timed out. */
    private val countingService = HttpService { req ->
        val result = try {
            var n = 0
            while (true) {
                val f = req.body.nextFrame() ?: break
                if (f is neton.http.Frame.Data) n += f.bytes.size
            }
            n.toString()
        } catch (e: HttpError) {
            if (e.isTimeout()) null else throw e
        }
        if (result == null) Response<Body>(FullBody(bytesOf("timeout"))).also { it.parts.status = StatusCode.REQUEST_TIMEOUT }
        else Response<Body>(FullBody(bytesOf(result)))
    }

    private suspend fun HyperScope.client(port: Int): Pair<neton.http.h2.client.SendRequest, kotlinx.coroutines.Deferred<Unit>> {
        val (h2, connection) = neton.http.h2.client.Builder().handshake(connectLocal(port))
        val run = spawn { runCatching { connection.run() }; Unit }
        h2.ready()
        return h2 to run
    }

    private suspend fun readAll(body: RecvStream): String {
        val out = StringBuilder()
        while (true) {
            val b = body.data() ?: break
            out.append(s(b.toByteArray()))
            body.flowControl().releaseCapacity(b.size)
        }
        return out.toString()
    }

    private fun post(port: Int): Request<Unit> = Request.post("http://127.0.0.1:$port/").body(Unit)

    private fun ms(sinceNanos: Long) = (monotonicNanos() - sinceNanos) / 1_000_000

    // ---- handshake ----------------------------------------------------------------------------------------------

    @Test
    fun aConnectionThatSendsNoPrefaceIsClosedWithATimeout() = hyperTest {
        val (listener, port) = listenLocal()
        val client = connectLocal(port)
        val server = listener.accept().closeAtEnd()
        val started = monotonicNanos()
        val e = try {
            Http2ServerConfig().handshakeTimeout(200.milliseconds).serveConnection(server, countingService).serve()
            fail("serve returned without the client's preface")
        } catch (e: HttpError) {
            e
        }
        val took = ms(started)
        assertTrue(e.isTimeout(), "$e")
        assertTrue(took in 200..2000, "the handshake timed out after $took ms")
        // The server's own preface (its SETTINGS) went out at the start; after it, the end of the stream.
        val buf = neton.io.bytes.Buffer(256)
        while (client.read(buf) >= 0) buf.clear()
        client.close()
    }

    @Test
    fun theHandshakeTimeoutDoesNotLimitAnEstablishedConnection() = hyperTest {
        val (listener, port) = listenLocal()
        val serving = spawn {
            Http2ServerConfig().handshakeTimeout(100.milliseconds).serveConnection(listener.accept().closeAtEnd(), countingService).serve()
        }
        val (h2, _) = client(port)
        delay(300)
        val (response, send) = h2.sendRequest(post(port), false)
        send.sendData(bytesOf("abc"), true)
        val res = response.await()
        assertEquals(StatusCode.OK, res.status)
        assertEquals("3", readAll(res.body))
        serving.cancel()
    }

    // ---- idle ---------------------------------------------------------------------------------------------------

    @Test
    fun anIdleConnectionIsShutDownGracefully() = hyperTest {
        val (listener, port) = listenLocal()
        val serving = spawn {
            Http2ServerConfig().idleTimeout(200.milliseconds).serveConnection(listener.accept().closeAtEnd(), countingService).serve()
        }
        val (h2, clientRun) = client(port)
        val (response, send) = h2.sendRequest(post(port), false)
        send.sendData(bytesOf("abcd"), true)
        assertEquals("4", readAll(response.await().body))
        val idleFrom = monotonicNanos()
        serving.await()                                       // returns normally: GOAWAY, then the connection ended
        val took = ms(idleFrom)
        assertTrue(took in 200..1000, "an idle connection was shut down after $took ms (limit 200, checked every 50)")
        clientRun.await()
    }

    @Test
    fun aConnectionWithStreamsKeepsGoing() = hyperTest {
        val (listener, port) = listenLocal()
        val slow = HttpService { req ->
            if (req.uri.path == "/slow") delay(700)              // one stream open for 3.5 idle limits
            Response<Body>(FullBody(bytesOf("ok")))
        }
        val serving = spawn { Http2ServerConfig().idleTimeout(200.milliseconds).serveConnection(listener.accept().closeAtEnd(), slow).serve() }
        val (h2, _) = client(port)
        // Short requests every 80 ms for 800 ms: never 200 ms without a stream.
        repeat(10) {
            val (response, _) = h2.sendRequest(Request.get("http://127.0.0.1:$port/").body(Unit), true)
            assertEquals("ok", readAll(response.await().body))
            delay(80)
        }
        val (slowResponse, _) = h2.sendRequest(Request.get("http://127.0.0.1:$port/slow").body(Unit), true)
        assertEquals("ok", readAll(slowResponse.await().body))
        assertTrue(serving.isActive, "the connection was shut down while it had streams")
        serving.await()                                       // idle after the last one: shut down
    }

    // ---- request body -------------------------------------------------------------------------------------------

    @Test
    fun aBodyThatStopsArrivingTimesOutAndTheServiceAnswers() = hyperTest {
        val (listener, port) = listenLocal()
        val serving = spawn {
            Http2ServerConfig().bodyReadTimeout(200.milliseconds).serveConnection(listener.accept().closeAtEnd(), countingService).serve()
        }
        val (h2, _) = client(port)
        val (response, send) = h2.sendRequest(post(port), false)
        val started = monotonicNanos()
        send.sendData(bytesOf("abc"), false)                  // then nothing
        val res = response.await()
        val took = ms(started)
        assertEquals(StatusCode.REQUEST_TIMEOUT, res.status)
        assertEquals("timeout", readAll(res.body))
        assertTrue(took in 200..2000, "the body timed out after $took ms")
        // The connection itself is fine: another request on it.
        val (second, send2) = h2.sendRequest(post(port), false)
        send2.sendData(bytesOf("xy"), true)
        assertEquals("2", readAll(second.await().body))
        serving.cancel()
    }

    @Test
    fun aBodyAlreadyReceivedNeverArmsTheTimer() = hyperTest {
        val (listener, port) = listenLocal()
        val slow = HttpService { req ->
            delay(400)                                        // twice the limit before the first read
            var n = 0
            while (true) {
                val f = req.body.nextFrame() ?: break
                if (f is neton.http.Frame.Data) n += f.bytes.size
            }
            Response<Body>(FullBody(bytesOf(n.toString())))
        }
        val serving = spawn { Http2ServerConfig().bodyReadTimeout(200.milliseconds).serveConnection(listener.accept().closeAtEnd(), slow).serve() }
        val (h2, _) = client(port)
        val (response, send) = h2.sendRequest(post(port), false)
        send.sendData(bytesOf("hello"), true)
        assertEquals("5", readAll(response.await().body))
        serving.cancel()
    }

    @Test
    fun aBodyArrivingInPiecesWithinTheLimitIsRead() = hyperTest {
        val (listener, port) = listenLocal()
        val serving = spawn {
            Http2ServerConfig().bodyReadTimeout(300.milliseconds).serveConnection(listener.accept().closeAtEnd(), countingService).serve()
        }
        val (h2, _) = client(port)
        val (response, send) = h2.sendRequest(post(port), false)
        repeat(3) {
            send.sendData(bytesOf("ab"), false)
            delay(50)
        }
        send.sendData(bytesOf("c"), true)
        assertEquals("7", readAll(response.await().body))
        serving.cancel()
    }

    @Test
    fun timeoutsMustBePositive() {
        for (bad in listOf(0.milliseconds, (-1).milliseconds)) {
            assertTrue(runCatching { Http2ServerConfig().handshakeTimeout(bad) }.isFailure)
            assertTrue(runCatching { Http2ServerConfig().idleTimeout(bad) }.isFailure)
            assertTrue(runCatching { Http2ServerConfig().bodyReadTimeout(bad) }.isFailure)
        }
        Http2ServerConfig().handshakeTimeout(null).idleTimeout(null).bodyReadTimeout(null)
    }
}
