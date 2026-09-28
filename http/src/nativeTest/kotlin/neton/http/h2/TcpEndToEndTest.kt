package neton.http.h2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import neton.http.Method
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.h2.frame.Reason
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.net.TcpListener
import neton.io.net.connect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// End-to-end tests over TCP loopback: this library's client against its server, on real sockets.

class TcpEndToEndTest {
    private fun pattern(n: Int, seed: Int): ByteArray = ByteArray(n) { ((it * 31 + seed) and 0xff).toByte() }

    /** Reads a whole body, releasing the capacity it used as the reference's examples do. */
    private suspend fun readBody(body: RecvStream): ByteArray {
        val out = ArrayList<ByteArray>()
        var total = 0
        while (true) {
            val chunk = body.data() ?: break
            out.add(chunk.toByteArray())
            total += chunk.size
            body.flowControl().releaseCapacity(chunk.size)
        }
        val all = ByteArray(total)
        var p = 0
        for (c in out) {
            c.copyInto(all, p)
            p += c.size
        }
        return all
    }

    /** Sends [data] with flow control: reserve, wait for capacity, send what fits. */
    private suspend fun sendBody(stream: SendStream, data: ByteArray) {
        var off = 0
        stream.reserveCapacity(data.size)
        while (off < data.size) {
            val cap = stream.capacity().takeIf { it > 0 } ?: assertNotNull(stream.awaitCapacity(), "stream closed")
            val n = minOf(cap, data.size - off)
            stream.sendData(Bytes.copyOf(data, off, off + n), off + n == data.size)
            off += n
        }
        if (data.isEmpty()) stream.sendData(Bytes.EMPTY, true)
    }

    /** A server echoing request bodies back, with the request path in `x-path`. */
    private fun CoroutineScope.echoServer(listener: TcpListener, builder: neton.http.h2.server.Builder = neton.http.h2.server.Builder()) =
        launch {
            while (true) {
                val socket = try {
                    listener.accept()
                } catch (e: Exception) {
                    break
                }
                launch { serveEcho(socket, builder) }
            }
        }

    private suspend fun serveEcho(socket: IoStream, builder: neton.http.h2.server.Builder) = coroutineScope {
        val conn = builder.handshake(socket)
        val running = launch { conn.run() }
        while (true) {
            val (req, respond) = conn.accept() ?: break
            launch {
                val body = readBody(req.body)
                val trailers = req.body.trailers()
                val response = Response.builder().status(StatusCode.OK).header("x-path", req.uri.path).body(Unit)
                val send = respond.sendResponse(response, false)
                sendBody(send, body)
                if (trailers != null) assertEquals("yes", trailers["x-trailer"]?.toStr())
                send.close()
                req.body.close()
                respond.close()
            }
        }
        running.join()
    }

    @Test
    fun sequentialRequestsOnOneConnection() = h2Test(30_000) {
        val listener = listenRetrying(24_201)
        val server = echoServer(listener)

        val (client, conn) = neton.http.h2.client.handshake(connect("127.0.0.1", 24_201))
        val connTask = async { conn.run() }
        repeat(20) { i ->
            val (response, stream) = client.sendRequest(request("POST", "http://localhost/r$i"), false)
            sendBody(stream, "hello $i".encodeToByteArray())
            stream.close()
            val res = response.await()
            assertEquals(StatusCode.OK, res.status)
            assertEquals("/r$i", res.headers["x-path"]!!.toStr())
            assertEquals("hello $i", readBody(res.body).decodeToString())
            res.body.close()
        }
        client.close()
        connTask.await()
        listener.close()
        server.join()
    }

    @Test
    fun concurrentStreamsWithFlowControl() = h2Test(60_000) {
        val listener = listenRetrying(24_202)
        val server = echoServer(listener)

        // Default windows (64 KiB): each body is larger than a stream window, and all together far larger than the
        // connection window.
        val (client, conn) = neton.http.h2.client.handshake(connect("127.0.0.1", 24_202))
        val connTask = async { conn.run() }
        val results = (0 until 40).map { i ->
            async {
                val data = pattern(100_000 + i * 1000, i)
                client.ready()
                val (response, stream) = client.sendRequest(request("POST", "http://localhost/s$i"), false)
                launch {
                    sendBody(stream, data)
                    stream.close()
                }
                val res = response.await()
                val echoed = readBody(res.body)
                res.body.close()
                assertTrue(data.contentEquals(echoed), "stream $i body")
                i
            }
        }.awaitAll()
        assertEquals((0 until 40).toList(), results)
        client.close()
        connTask.await()
        listener.close()
        server.join()
    }

    @Test
    fun largeBodiesWithSmallAndLargeWindows() = h2Test(60_000) {
        val listener = listenRetrying(24_203)
        val server = echoServer(
            listener,
            neton.http.h2.server.Builder().initialWindowSize(16_384).initialConnectionWindowSize(32_768).maxFrameSize(32_768),
        )

        val (client, conn) = neton.http.h2.client.Builder()
            .initialWindowSize(1 shl 20)
            .initialConnectionWindowSize(4 shl 20)
            .maxFrameSize(1 shl 20)
            .handshake(connect("127.0.0.1", 24_203))
        val connTask = async { conn.run() }
        val data = pattern(5 * 1024 * 1024, 7)
        val (response, stream) = client.sendRequest(request("PUT", "http://localhost/big"), false)
        launch {
            sendBody(stream, data)
            stream.close()
        }
        val res = response.await()
        val echoed = readBody(res.body)
        res.body.close()
        assertEquals(data.size, echoed.size)
        assertTrue(data.contentEquals(echoed))
        client.close()
        connTask.await()
        listener.close()
        server.join()
    }

    @Test
    fun trailersPushAndResetOverTcp() = h2Test(30_000) {
        val listener = listenRetrying(24_204)
        val server = launch {
            val socket = listener.accept()
            val conn = neton.http.h2.server.handshake(socket)
            val running = launch { conn.run() }
            while (true) {
                val (req, respond) = conn.accept() ?: break
                when (req.uri.path) {
                    "/push" -> {
                        val pushed = respond.pushRequest(Request.builder().method(Method.GET).uri("http://localhost/style.css").body(Unit))
                        val ps = pushed.sendResponse(Response.builder().status(200).body(Unit), false)
                        ps.sendData(Bytes.copyOf("body{}".encodeToByteArray()), true)
                        ps.close()
                        pushed.close()
                        val send = respond.sendResponse(Response.builder().status(200).body(Unit), false)
                        val trailers = neton.http.header.HeaderMap<HeaderValue>()
                        trailers.insert("grpc-status", HeaderValue.fromStr("0"))
                        send.sendData(Bytes.copyOf("page".encodeToByteArray()), false)
                        send.sendTrailers(trailers)
                        send.close()
                    }
                    else -> respond.sendReset(Reason.REFUSED_STREAM)
                }
                req.body.close()
                respond.close()
            }
            running.join()
        }

        val (client, conn) = neton.http.h2.client.handshake(connect("127.0.0.1", 24_204))
        val connTask = async { conn.run() }

        val (response, stream) = client.sendRequest(request("GET", "http://localhost/push"), true)
        stream.close()
        val pushes = response.pushPromises()
        val res = response.await()
        assertEquals("page", readBody(res.body).decodeToString())
        assertEquals("0", res.body.trailers()!!["grpc-status"]!!.toStr())
        res.body.close()
        val push = assertNotNull(pushes.pushPromise())
        assertEquals("/style.css", push.request.uri.path)
        val pushed = push.response.await()
        assertEquals("body{}", readBody(pushed.body).decodeToString())
        pushed.body.close()
        assertNull(pushes.pushPromise())
        pushes.close()

        val (refused, s2) = client.sendRequest(request("GET", "http://localhost/refuse"), true)
        s2.close()
        val err = assertFailsWith<H2Error> { refused.await() }
        assertEquals(Reason.REFUSED_STREAM, err.reason())
        assertTrue(err.isRemote)

        client.close()
        connTask.await()
        listener.close()
        server.join()
    }

    @Test
    fun gracefulShutdownAndPingOverTcp() = h2Test(30_000) {
        val listener = listenRetrying(24_205)
        val server = launch {
            val socket = listener.accept()
            val conn = neton.http.h2.server.handshake(socket)
            val running = async { conn.run() }
            val (req, respond) = assertNotNull(conn.accept())
            // A second request races the shutdown: once the GOAWAY with the last stream ID went out, it is refused.
            conn.gracefulShutdown()
            val body = readBody(req.body)
            val send = respond.sendResponse(Response.builder().status(200).body(Unit), false)
            send.sendData(Bytes.wrap(body), true)
            send.close()
            req.body.close()
            respond.close()
            assertNull(conn.accept())
            running.await()
        }

        val (client, conn) = neton.http.h2.client.handshake(connect("127.0.0.1", 24_205))
        val connTask = async { conn.run() }
        val pingPong = assertNotNull(conn.pingPong())
        pingPong.ping(Ping.opaque())

        val (response, stream) = client.sendRequest(request("POST", "http://localhost/last"), false)
        sendBody(stream, "bye".encodeToByteArray())
        stream.close()
        val res = response.await()
        assertEquals("bye", readBody(res.body).decodeToString())
        res.body.close()

        // The server's graceful shutdown closes the connection once its streams are done.
        connTask.await()
        assertFailsWith<H2Error> { client.sendRequest(request("GET", "http://localhost/late"), true) }
        client.close()
        server.join()
        listener.close()
    }
}
