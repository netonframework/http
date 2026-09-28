@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http.bench

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import neton.http.Body
import neton.http.EmptyBody
import neton.http.Request
import neton.http.h1.http1Handshake
import neton.io.core.monotonicNanos
import neton.io.net.connect
import neton.io.net.runReactor

/**
 * The client side of the hyper comparison (SPEC §8): [conns] keep-alive connections on one reactor, each sending
 * GET / back to back for [seconds] and reading every response body to its end (hyper's `client::conn::http1`, as the
 * Rust `hyper-client` bench does). Prints the number of completed requests.
 *
 * Arguments: host port conns seconds. Environment NETON_IO_DRIVER picks the driver.
 */
fun clientMain(args: Array<String>) {
    val host = args.getOrElse(0) { "127.0.0.1" }
    val port = args.getOrElse(1) { "3000" }.toInt()
    val conns = args.getOrElse(2) { "10" }.toInt()
    val seconds = args.getOrElse(3) { "5" }.toLong()
    var completed = 0L
    runReactor {
        val deadline = monotonicNanos() + seconds * 1_000_000_000L
        coroutineScope {
            repeat(conns) {
                launch {
                    val stream = connect(host, port)
                    val (sender, connection) = http1Handshake(stream)
                    val driver = launch { runCatching { connection.run() } }
                    while (monotonicNanos() < deadline) {
                        sender.ready()
                        val request = Request.builder().uri("/").header("host", host).body(EmptyBody as Body)
                        val response = sender.sendRequest(request)
                        while (response.body.nextFrame() != null) {}
                        completed++
                    }
                    driver.cancel()
                    stream.close()
                }
            }
        }
    }
    println("requests $completed")
}
