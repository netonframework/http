package neton.http.h2

import neton.http.Body
import neton.http.FullBody
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
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚖️ [Http2ServerConfig.maxRequestBodySize] (hyper and h2 have no limit; HTTP/1 has the same setting): a declared length
 * over it is answered 413 without calling the service; a body growing over it fails the read, and the service failing
 * with that gets 413. The connection carries on either way.
 */
class RequestBodyLimitTest {
    private var calls = 0

    /** Reads the whole body and answers its length; a failed read fails the service. */
    private val countingService = HttpService { req ->
        calls++
        var n = 0
        while (true) {
            val f = req.body.nextFrame() ?: break
            if (f is neton.http.Frame.Data) n += f.bytes.size
        }
        Response<Body>(FullBody(bytesOf(n.toString())))
    }

    private suspend fun HyperScope.client(port: Int): neton.http.h2.client.SendRequest {
        val (h2, connection) = neton.http.h2.client.Builder().handshake(connectLocal(port))
        spawn { runCatching { connection.run() }; Unit }
        h2.ready()
        return h2
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

    private fun post(port: Int, contentLength: Int? = null): Request<Unit> {
        val b = Request.post("http://127.0.0.1:$port/")
        if (contentLength != null) b.header("content-length", contentLength.toString())
        return b.body(Unit)
    }

    private fun bytes(n: Int) = Bytes.wrap(ByteArray(n) { 'x'.code.toByte() })

    /** Serves one connection with [limit]; returns the client. */
    private suspend fun HyperScope.serve(limit: Long?): Pair<neton.http.h2.client.SendRequest, Int> {
        val (listener, port) = listenLocal()
        val config = Http2ServerConfig().also { c -> limit?.let { c.maxRequestBodySize(it) } }
        spawn { runCatching { config.serveConnection(listener.accept().closeAtEnd(), countingService).serve() }; Unit }
        return client(port) to port
    }

    /** One request with [size] bytes of body (declared when [declare]); the status and the response body. */
    private suspend fun neton.http.h2.client.SendRequest.send(port: Int, size: Int, declare: Boolean = false): Pair<StatusCode, String> {
        val (response, send) = sendRequest(post(port, if (declare) size else null), false)
        var left = size
        while (left > 0) {
            val n = minOf(left, 600)
            left -= n
            runCatching { send.sendData(bytes(n), left == 0) }
        }
        val res = response.await()
        return res.status to readAll(res.body)
    }

    @Test
    fun aDeclaredLengthOverTheLimitIsRefusedWithoutCallingTheService() = hyperTest {
        val (h2, port) = serve(1000)
        val (response, _) = h2.sendRequest(post(port, contentLength = 5000), false)
        assertEquals(StatusCode.PAYLOAD_TOO_LARGE, response.await().status)
        assertEquals(0, calls, "the service was not called")
        assertEquals(StatusCode.OK to "1000", h2.send(port, 1000, declare = true), "the connection carries on")
    }

    @Test
    fun aBodyGrowingOverTheLimitIs413() = hyperTest {
        val (h2, port) = serve(1000)
        assertEquals(StatusCode.PAYLOAD_TOO_LARGE, h2.send(port, 1200).first)
        assertEquals(1, calls)
        assertEquals(StatusCode.OK to "7", h2.send(port, 7), "the connection carries on")
    }

    @Test
    fun aBodyExactlyAtTheLimitIsRead() = hyperTest {
        val (h2, port) = serve(1000)
        assertEquals(StatusCode.OK to "1000", h2.send(port, 1000))
    }

    @Test
    fun zeroMeansNoLimit() = hyperTest {
        val (h2, port) = serve(0)
        assertEquals(StatusCode.OK to "20000", h2.send(port, 20_000, declare = true))
    }

    @Test
    fun theDefaultIsTenMebibytes() = hyperTest {
        assertEquals(10L * 1024 * 1024, Http2ServerConfig().maxRequestBodySize)
        assertTrue(runCatching { Http2ServerConfig().maxRequestBodySize(-1) }.isFailure)
    }
}
