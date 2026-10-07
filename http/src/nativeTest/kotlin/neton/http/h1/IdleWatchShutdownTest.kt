package neton.http.h1

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import neton.http.Body
import neton.http.FullBody
import neton.http.Response
import kotlin.test.Test

/**
 * A service that suspended leaves the read-side watch parked; once its response is out the connection waits for the
 * next request through that watch (awaitIdleRead). A graceful shutdown of the connection in that idle state must end
 * it, as it does for an idle connection waiting in the head read (hyper: an idle connection closes at once).
 */
class IdleWatchShutdownTest {
    private val suspendingHello = HttpService {
        delay(10)                                            // suspends: the watch is started
        Response.builder().body(FullBody(bytesOf(HELLO)) as Body)
    }

    @Test
    fun gracefulShutdownEndsAConnectionIdleInTheWatch() = hyperTest {
        val (listener, port) = listenLocal()
        val client = connectLocal(port)
        val server = Http1ServerConfig().serveConnection(listener.accept().closeAtEnd(), suspendingHello)
        val serving = spawn { server.serve() }
        client.writeAll("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
        client.readUntil { it.endsWith(HELLO) }
        delay(20)                                            // the connection is now idle, waiting for the next request
        server.gracefulShutdown()
        withTimeout(5_000) { serving.await() }
    }

    @Test
    fun gracefulShutdownEndsAConnectionIdleAfterABufferedPost() = hyperTest {
        val (listener, port) = listenLocal()
        val client = connectLocal(port)
        val server = Http1ServerConfig().serveConnection(listener.accept().closeAtEnd(), suspendingHello)
        val serving = spawn { server.serve() }
        client.writeAll("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nabc")
        client.readUntil { it.endsWith(HELLO) }
        delay(20)
        server.gracefulShutdown()
        withTimeout(5_000) { serving.await() }
    }
}
