package neton.http.h1

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import neton.http.Body
import neton.http.Frame
import neton.http.FullBody
import neton.http.Incoming
import neton.http.Response
import neton.io.bytes.Buffer
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fscanf
import platform.posix.getpagesize
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPEC §6: a 100 MB upload streamed through a real TCP connection with `maxRequestBodySize` raised to 200 MiB. The
 * handler reads it frame by frame and checks length and checksum; the process's resident set may grow by at most
 * 16 MiB while it runs (the body is never accumulated: one read buffer plus one chunk, §3.9).
 */
@OptIn(ExperimentalForeignApi::class, kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
class UploadTest {
    private fun residentBytes(): Long = memScoped {
        val f = fopen("/proc/self/statm", "r") ?: return 0
        val size = alloc<LongVar>()
        val resident = alloc<LongVar>()
        fscanf(f, "%ld %ld", size.ptr, resident.ptr)
        fclose(f)
        resident.value * getpagesize().convert<Long>()
    }

    // FNV-1a over the bytes, identical on both sides.
    private fun fnv(h: Long, b: ByteArray, from: Int, to: Int): Long {
        var x = h
        for (i in from until to) { x = (x xor (b[i].toLong() and 0xff)) * 0x100000001b3L }
        return x
    }

    @Test
    fun hundredMegabyteUploadIsStreamedInBoundedMemory() = hyperTest(timeoutMillis = 120_000) {
        val total = 100L * 1000 * 1000
        val chunk = ByteArray(64 * 1024) { (it * 31 + 7).toByte() }
        val cfg = Http1ServerConfig(maxRequestBodySize = 200L * 1024 * 1024)
        val (listener, port) = listenLocal()
        var peakGrowth = 0L
        val baseline = residentBytes()
        val server = spawn {
            val stream = listener.accept().closeAtEnd()
            cfg.serveConnection(stream) { req ->
                val body = req.body as Incoming
                var n = 0L
                var h = 0xcbf29ce484222325uL.toLong()
                var sinceSample = 0L
                while (true) {
                    val f = body.nextFrame() ?: break
                    if (f !is Frame.Data) continue
                    val bytes = f.bytes
                    for (i in 0 until bytes.size) h = (h xor (bytes[i].toLong() and 0xff)) * 0x100000001b3L
                    n += bytes.size
                    sinceSample += bytes.size
                    if (sinceSample >= 1 shl 20) {
                        sinceSample = 0
                        peakGrowth = maxOf(peakGrowth, residentBytes() - baseline)
                    }
                }
                Response.builder().body(FullBody(bytesOf("$n $h")) as Body)
            }.serve()
        }
        val client = connectLocal(port)
        client.writeAll("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Length: $total\r\nConnection: close\r\n\r\n")
        val buf = Buffer(chunk.size)
        var sent = 0L
        var h = 0xcbf29ce484222325uL.toLong()
        while (sent < total) {
            val len = minOf(chunk.size.toLong(), total - sent).toInt()
            h = fnv(h, chunk, 0, len)
            buf.writeBytes(chunk, 0, len)
            client.write(buf)
            sent += len
        }
        val acc = Buffer()
        while (client.read(acc) >= 0) {}
        val reply = acc.readAll().decodeToString()
        server.await()
        assertTrue(reply.startsWith("HTTP/1.1 200 OK\r\n"), reply)
        assertEquals("$total $h", reply.substringAfter("\r\n\r\n"))
        val gc = kotlin.native.runtime.GC.lastGCInfo
        println("upload: resident growth peak ${peakGrowth / 1024} KiB, end ${(residentBytes() - baseline) / 1024} KiB, gc #${gc?.epoch} heap after " +
            "${gc?.memoryUsageAfter?.values?.sumOf { it.totalObjectsSizeBytes }?.div(1024)} KiB, before ${gc?.memoryUsageBefore?.values?.sumOf { it.totalObjectsSizeBytes }?.div(1024)} KiB")
        assertTrue(peakGrowth <= 16L * 1024 * 1024, "resident set grew by ${peakGrowth / 1024} KiB")
    }
}
