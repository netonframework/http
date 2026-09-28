@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http.bench

import neton.http.Body
import neton.http.FullBody
import neton.http.Response
import neton.http.h1.Http1ServerConfig
import neton.io.bytes.Bytes
import neton.io.net.serveTcp
import kotlinx.cinterop.toKString

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
    // NETON_HTTP_TIMEOUTS=0 turns the header / keep-alive timeouts off (to measure what they cost).
    val timeouts = platform.posix.getenv("NETON_HTTP_TIMEOUTS")?.toKString() != "0"
    val config = if (timeouts) Http1ServerConfig(pipelineFlush = pipelineFlush)
    else Http1ServerConfig(pipelineFlush = pipelineFlush, headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0)
    val hello = Bytes.copyOf("Hello, World!".encodeToByteArray())   // shared, like hyper's static `Bytes`
    // The application decides process-wide GC settings (neton-io SPEC §26.8): NETON_IO_GC_MIN_HEAP_MB applies a heap floor.
    val minHeap = neton.io.net.GcTuning.fromEnvironment()
    platform.posix.getenv("NETON_IO_GC_THREAD_NICE")?.toKString()?.toIntOrNull()?.let { println("gc threads reniced: ${neton.io.net.GcTuning.lowerGcThreadPriority(it)}") }
    println("helloServer on $host:$port reactors=$reactors pipelineFlush=$pipelineFlush minHeapMb=$minHeap")
    // NETON_HTTP_GC_STATS=1: print the GC epoch and last pause once a second (to relate collections to latency).
    if (platform.posix.getenv("NETON_HTTP_GC_STATS")?.toKString() == "1") startGcStats()
    serveTcp(host, port, reactors = reactors, shutdownOnSignals = true) { stream ->
        runCatching {
            config.serveConnection(stream) { Response<Body>(FullBody(hello)) }.serve()   // hyper: Response::new(Full::new(..))
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

