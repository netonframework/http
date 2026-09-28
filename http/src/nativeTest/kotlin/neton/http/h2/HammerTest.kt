@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package neton.http.h2

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.net.TcpListener
import neton.io.net.connect
import neton.io.net.listen
import neton.io.net.runReactor
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals

// Ported from h2 0.4.19 tests/h2-tests/tests/hammer.rs (1 test): a TCP server on its own thread (its own reactor)
// and N sequential client connections, each sending a request whose body is only (empty) trailers.

/** Listens on [port], retrying while a previous run's socket still holds it. */
internal suspend fun listenRetrying(port: Int): TcpListener {
    var last: Throwable? = null
    repeat(50) {
        try {
            return listen("127.0.0.1", port)
        } catch (e: IllegalStateException) {
            last = e
            delay(100)
        }
    }
    throw last!!
}

class HammerTest {
    private val helloWorld = Bytes.copyOf("hello world!".encodeToByteArray())

    private suspend fun handleRequest(socket: IoStream, reqs: AtomicInt) = coroutineScope {
        val conn = neton.http.h2.server.handshake(socket)
        val running = launch { conn.run() }
        while (true) {
            val (req, respond) = conn.accept() ?: break
            req.body.close()
            reqs.incrementAndFetch()
            val response = Response.builder().status(StatusCode.OK).body(Unit)
            val send = respond.sendResponse(response, false)
            send.sendData(helloWorld, true)
            send.close()
            respond.close()
        }
        running.join()
    }

    @Test
    fun hammerClientConcurrency() {
        // This reproduces issue #326.
        val n = 5000
        val port = 24_190

        val reqs = AtomicInt(0)
        val ready = AtomicInt(0)
        val stop = AtomicInt(0)
        val worker = Worker.start(name = "h2-hammer-server")
        worker.executeAfter(0L) {
            runReactor {
                val listener = listenRetrying(port)
                ready.store(1)
                while (true) {
                    val socket = listener.accept()
                    if (stop.load() == 1) {
                        socket.close()
                        break
                    }
                    launch {
                        try {
                            handleRequest(socket, reqs)
                        } catch (e: H2Error) {
                            println("serve conn error: $e")
                        }
                    }
                }
                listener.close()
                coroutineContext.cancelChildren()
            }
            ready.store(2)
        }

        var rsps = 0
        runReactor {
            while (ready.load() == 0) delay(5)
            repeat(n) {
                val tcp = connect("127.0.0.1", port)
                val (client, h2) = neton.http.h2.client.handshake(tcp)
                val request = Request.builder().uri("https://http2.akamai.com/").body(Unit)

                val (response, stream) = client.sendRequest(request, false)
                stream.sendTrailers(HeaderMap<HeaderValue>())

                val conn = launch { h2.run() }

                val body = response.await().body
                while (body.data() != null) {
                    // drain
                }
                body.trailers()
                rsps++

                // The reference drops everything with the iteration's runtime.
                body.close()
                stream.close()
                client.close()
                conn.join()
            }

            stop.store(1)
            connect("127.0.0.1", port).close()
        }
        while (ready.load() != 2) kotlin.native.concurrent.Worker.current.park(1_000, process = true)
        worker.requestTermination().result

        assertEquals(n, rsps)
        assertEquals(n, reqs.load())
    }
}
