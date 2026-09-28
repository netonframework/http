package neton.http.bench

import neton.http.Body
import neton.http.FullBody
import neton.http.Response
import neton.http.h1.Http1ServerConfig
import neton.io.bytes.Bytes
import neton.io.net.serveTcp

/**
 * The hello-world HTTP/1 server of the hyper comparison (SPEC §8): every request gets `200` with `Hello, World!`
 * (hyper's `examples/hello.rs`), the date header on, default options.
 *
 * Arguments: host port [reactors] [pipelineFlush=0|1]. Environment NETON_IO_DRIVER picks the driver.
 */
fun main(args: Array<String>) {
    val host = args.getOrElse(0) { "127.0.0.1" }
    val port = args.getOrElse(1) { "3000" }.toInt()
    val reactors = args.getOrElse(2) { "1" }.toInt()
    val pipelineFlush = args.getOrElse(3) { "0" } == "1"
    val config = Http1ServerConfig(pipelineFlush = pipelineFlush)
    val hello = Bytes.copyOf("Hello, World!".encodeToByteArray())   // shared, like hyper's static `Bytes`
    println("helloServer on $host:$port reactors=$reactors pipelineFlush=$pipelineFlush")
    serveTcp(host, port, reactors = reactors, shutdownOnSignals = true) { stream ->
        runCatching {
            config.serveConnection(stream) { Response<Body>(FullBody(hello)) }.serve()   // hyper: Response::new(Full::new(..))
        }
    }
}
