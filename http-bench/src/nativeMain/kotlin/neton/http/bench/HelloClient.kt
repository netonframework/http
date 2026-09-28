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
import kotlinx.cinterop.toKString

/**
 * The client side of the hyper comparison (SPEC §8): [conns] keep-alive connections on one reactor, each sending
 * GET / back to back for [seconds] and reading every response body to its end (hyper's `client::conn::http1`, as the
 * Rust `hyper-client` bench does). Prints the number of completed requests.
 *
 * Arguments: host port conns seconds [streams]. Environment NETON_IO_DRIVER picks the driver; NETON_HTTP_H2=1 speaks
 * HTTP/2 over cleartext with prior knowledge, [streams] requests in flight per connection (hyper `http2::handshake`).
 */
fun clientMain(args: Array<String>) {
    val host = args.getOrElse(0) { "127.0.0.1" }
    val port = args.getOrElse(1) { "3000" }.toInt()
    val conns = args.getOrElse(2) { "10" }.toInt()
    val seconds = args.getOrElse(3) { "5" }.toLong()
    val streams = args.getOrElse(4) { "10" }.toInt()
    val h2 = platform.posix.getenv("NETON_HTTP_H2")?.toKString() == "1"
    var completed = 0L
    if (h2) {
        runReactor {
            val deadline = monotonicNanos() + seconds * 1_000_000_000L
            coroutineScope {
                repeat(conns) {
                    launch {
                        val stream = connect(host, port)
                        val (sender, connection) = neton.http.h2.http2Handshake(stream)
                        val driver = launch { runCatching { connection.run() } }
                        val uri = "http://$host:$port/"   // built once, as the hyper bench does
                        coroutineScope {
                            repeat(streams) {
                                val s = sender.clone()
                                launch {
                                    while (monotonicNanos() < deadline) {
                                        s.ready()
                                        val request = Request.builder().uri(uri).body(EmptyBody as Body)
                                        val response = s.sendRequest(request)
                                        while (response.body.nextFrame() != null) {}
                                        completed++
                                    }
                                }
                            }
                        }
                        driver.cancel()
                        stream.close()
                    }
                }
            }
        }
        println("requests $completed")
        return
    }
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
