package neton.http.h2

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import neton.http.Response
import neton.http.StatusCode
import neton.http.h2.frame.Settings
import neton.io.core.memoryStreamPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class H2SmokeTest {
    @Test
    fun clientAgainstMockServer() = h2Test {
        val (io, srv) = mockNew()
        val server = launch {
            val settings = srv.assertClientHandshake()
            assertEquals(Settings(), settings)
            srv.recvFrame(Frames.headers(1).request("GET", "https://http2.akamai.com/").eos())
            srv.sendFrame(Frames.headers(1).response(200).eos())
        }
        val (client, conn) = neton.http.h2.client.handshake(io)
        val driver = launch { conn.run() }
        val (response, stream) = client.sendRequest(request("GET", "https://http2.akamai.com/"), true)
        stream.close()
        val res = response.await()
        assertEquals(StatusCode.OK, res.status)
        res.body.close()
        client.close()
        server.join()
        driver.join()
    }

    @Test
    fun serverAgainstMockClient() = h2Test {
        val (io, client) = mockNew()
        val c = launch {
            client.assertServerHandshake()
            client.sendFrame(Frames.headers(1).request("GET", "https://example.com/").eos())
            client.recvFrame(Frames.headers(1).response(200).eos())
            client.close()
        }
        val srv = neton.http.h2.server.handshake(io)
        val driver = async { srv.run() }
        val (req, respond) = srv.accept()!!
        assertEquals("GET", req.method.toString())
        respond.sendResponse(Response.builder().status(200).body(Unit), true).close()
        respond.close()
        req.body.close()
        assertNull(srv.accept())
        driver.await()
        c.join()
    }

    @Test
    fun clientServerEndToEndInMemory() = h2Test {
        val (a, b) = memoryStreamPair()
        val server = launch {
            val srv = neton.http.h2.server.handshake(a)
            launch { srv.run() }
            while (true) {
                val (req, respond) = srv.accept() ?: break
                launch {
                    val body = concat(req.body)
                    val send = respond.sendResponse(Response.builder().status(200).body(Unit), false)
                    send.sendData(bytes("echo:" + body.decodeToString()), true)
                    send.close()
                    respond.close()
                    req.body.close()
                }
            }
        }
        val (client, conn) = neton.http.h2.client.handshake(b)
        val driver = launch { conn.run() }
        repeat(3) { i ->
            val (response, stream) = client.sendRequest(request("POST", "https://example.com/x"), false)
            stream.sendData(bytes("hello$i"), true)
            stream.close()
            val res = response.await()
            assertEquals("echo:hello$i", concat(res.body).decodeToString())
            res.body.close()
        }
        client.close()
        driver.join()
        server.join()
    }
}
