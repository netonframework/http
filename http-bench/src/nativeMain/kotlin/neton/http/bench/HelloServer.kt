@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http.bench

import neton.http.Body
import neton.http.FullBody
import neton.http.Response
import neton.http.auto.AutoServerConfig
import neton.http.h1.Http1ServerConfig
import neton.http.h1.HttpService
import neton.http.h2.Http2ServerConfig
import neton.io.bytes.Bytes
import neton.io.net.serveTcp
import kotlinx.cinterop.toKString

/**
 * The hello-world HTTP/1 server of the hyper comparison (SPEC §8): every request gets `200` with `Hello, World!`
 * (hyper's `examples/hello.rs`), the date header on, default options.
 *
 * Arguments: host port [reactors] [pipelineFlush=0|1]. Environment NETON_IO_DRIVER picks the driver; NETON_HTTP_H2=1
 * serves HTTP/2 over cleartext with prior knowledge (hyper's `http2::Builder` defaults) instead of HTTP/1; NETON_HTTP_AUTO=1
 * serves both through the auto server (hyper-util `auto::Builder`, the preface decides), with the same HTTP/1 options.
 */
fun main(args: Array<String>) {
    val host = args.getOrElse(0) { "127.0.0.1" }
    val port = args.getOrElse(1) { "3000" }.toInt()
    val reactors = args.getOrElse(2) { "1" }.toInt()
    val pipelineFlush = args.getOrElse(3) { "0" } == "1"
    // NETON_HTTP_TIMEOUTS=0 turns the header / keep-alive timeouts off (to measure what they cost).
    val timeouts = platform.posix.getenv("NETON_HTTP_TIMEOUTS")?.toKString() != "0"
    // NETON_HTTP_WRITEV=0 selects the flatten write strategy (hyper `writev(false)`).
    val writev = platform.posix.getenv("NETON_HTTP_WRITEV")?.toKString()?.let { it != "0" }
    val config = if (timeouts) Http1ServerConfig(pipelineFlush = pipelineFlush, writev = writev)
    else Http1ServerConfig(pipelineFlush = pipelineFlush, writev = writev, headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0)
    val hello = Bytes.copyOf("Hello, World!".encodeToByteArray())   // shared, like hyper's static `Bytes`
    // The application decides process-wide GC settings (neton-io SPEC §26.8): NETON_IO_GC_MIN_HEAP_MB applies a heap floor.
    val minHeap = neton.io.net.GcTuning.fromEnvironment()
    platform.posix.getenv("NETON_IO_GC_THREAD_NICE")?.toKString()?.toIntOrNull()?.let { println("gc threads reniced: ${neton.io.net.GcTuning.lowerGcThreadPriority(it)}") }
    println("helloServer on $host:$port reactors=$reactors pipelineFlush=$pipelineFlush minHeapMb=$minHeap")
    // NETON_HTTP_GC_STATS=1: print the GC epoch and last pause once a second (to relate collections to latency).
    if (platform.posix.getenv("NETON_HTTP_GC_STATS")?.toKString() == "1") startGcStats()
    val h2 = platform.posix.getenv("NETON_HTTP_H2")?.toKString() == "1"
    if (h2) println("h2c")
    val auto = if (platform.posix.getenv("NETON_HTTP_AUTO")?.toKString() == "1") AutoServerConfig(config) else null
    if (auto != null) println("auto")
    val service = HttpService { Response<Body>(FullBody(hello)) }   // hyper: Response::new(Full::new(..))
    serveTcp(host, port, reactors = reactors, shutdownOnSignals = true) { stream ->
        runCatching {
            if (auto != null) auto.serveConnection(stream, service).serve()
            else if (h2) Http2ServerConfig().serveConnection(stream, service).serve()
            else config.serveConnection(stream, service).serve()
        }
    }
}

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class, kotlin.ExperimentalStdlibApi::class)
private fun startGcStats() {
    kotlin.native.concurrent.Worker.start(name = "gc-stats").executeAfter(0) {
        var last = 0L
        while (true) {
            platform.posix.sleep(1u)
            val info = kotlin.native.runtime.GC.lastGCInfo ?: continue
            val safepointUs = (info.firstPauseStartTimeNs - info.firstPauseRequestTimeNs) / 1000
            val pauseUs = (info.firstPauseEndTimeNs - info.firstPauseStartTimeNs) / 1000
            println("gc epoch=${info.epoch} perSecond=${info.epoch - last} lastTimeToSafepointUs=$safepointUs lastPauseUs=$pauseUs")
            platform.posix.fflush(platform.posix.stdout)
            last = info.epoch
        }
    }
}

