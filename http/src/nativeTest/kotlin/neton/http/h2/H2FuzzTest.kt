package neton.http.h2

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import neton.http.HttpException
import neton.http.Method
import neton.http.Request
import neton.http.h2.client.handshake
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.uri.Uri
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoException
import neton.io.core.IoStream
import neton.io.core.memoryStreamPair
import neton.io.net.runReactor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

// Ports of h2 0.4.19's `fuzz/fuzz_targets/fuzz_client.rs` and `fuzz_e2e.rs` (`fuzz_hpack` is in hpack/FuzzTest).
// The references run libFuzzer and require that nothing panics; here inputs come from fixed seeds (reproducible) and
// the check is that only the library's own errors come out (H2Error, the http crate's errors, I/O errors) and that
// every run ends: each script gets a deadline, so a connection that hangs fails the test.

class H2FuzzTest {
    private fun show(b: ByteArray) = b.joinToString("") { val c = it.toInt() and 0xff; if (c in 32..126) c.toChar().toString() else "\\x" + c.toString(16) }

    private val uris = listOf("https://example.com/", "http://a/b?c=d", "/", "*", "example.com:443", "https://u@h:1/p#f", "").map { it.encodeToByteArray() }
    private val names = listOf("content-type", "connection", "te", "x-custom", "Upgrade", "keep-alive", "accept").map { it.encodeToByteArray() }
    private val values = listOf("text/html", "trailers", "close", "gzip", "\u0000", "a\r\nb", "").map { it.encodeToByteArray() }

    private fun Random.mutate(seed: ByteArray): ByteArray {
        if (nextInt(3) != 0) return seed
        val b = seed.toMutableList()
        repeat(nextInt(1, 3)) {
            when (nextInt(3)) {
                0 -> if (b.isNotEmpty()) b[nextInt(b.size)] = nextInt(256).toByte()
                1 -> b.add(nextInt(b.size + 1), nextInt(256).toByte())
                else -> if (b.isNotEmpty()) b.removeAt(nextInt(b.size))
            }
        }
        return b.toByteArray()
    }

    /** `fuzz_client`: a request built from arbitrary URI and header bytes, sent on a fresh client connection. */
    @Test
    fun fuzz_client() {
        val rng = Random(21)
        var sent = 0
        var refused = 0
        repeat(3_000) { case ->
            val uri = rng.mutate(uris.random(rng))
            val name = rng.mutate(names.random(rng))
            val value = rng.mutate(values.random(rng))
            val what = "case $case uri `${show(uri)}` name `${show(name)}` value `${show(value)}`"
            val request = try {
                Request.builder().uri(Uri.fromBytes(uri)).header(HeaderName.fromBytes(name), HeaderValue.fromBytes(value)).body(Unit)
            } catch (e: HttpException) {
                return@repeat // the reference only goes on when the builder succeeds
            }
            runReactor {
                val (io, _) = memoryStreamPair()
                val (client, _) = handshake(io)
                try {
                    client.sendRequest(request, true)
                    sent++
                } catch (e: H2Error) {
                    refused++ // a user error: the URI is not absolute, a connection-specific header...
                } catch (e: Throwable) {
                    fail("$what threw $e")
                }
                io.close()
            }
        }
        println("fuzz_client: sent $sent refused $refused")
        // Keep the generator honest: both outcomes must be exercised.
        if (sent < 300 || refused < 300) fail("fuzz_client: only $sent sent and $refused refused of 3000")
    }

    /**
     * `fuzz_e2e`'s transport. In the reference's layout ([writes] null) every read and every write first takes a 16-bit
     * length from the script: a read gets that many script bytes (0: not ready yet), a write is accepted that many
     * bytes at a time (0 once the script is used up: broken pipe); a used-up script is EOF. ⚖️ With [writes] given, the
     * write allowances come from that list instead, so frames in the read script stay aligned: libFuzzer finds working
     * layouts of the shared script by coverage, a seeded generator does not.
     */
    private class ScriptIo(private val input: ByteArray, private val writes: IntArray? = null) : IoStream {
        private var pos = 0
        private var w = 0

        private fun nextByte(): Int = if (pos < input.size) input[pos++].toInt() and 0xff else 0
        private fun nextLen(): Int = (nextByte() shl 8) or nextByte()
        private fun nextWrite(): Int = if (writes == null) nextLen() else if (w < writes.size) writes[w++] else 0
        private val writesUsedUp: Boolean get() = if (writes == null) pos >= input.size else w >= writes.size

        override suspend fun read(dst: Buffer): Int {
            while (true) {
                var len = nextLen()
                if (pos >= input.size) return -1
                if (len == 0) { yield(); continue }
                len = minOf(len, input.size - pos)
                dst.writeBytes(input, pos, len)
                pos += len
                return len
            }
        }

        override suspend fun write(src: Buffer): Int {
            val total = src.readableBytes
            while (src.readableBytes > 0) {
                val len = minOf(nextWrite(), src.readableBytes)
                if (len == 0) {
                    if (writesUsedUp) throw IoException("broken pipe")
                    yield()
                    continue
                }
                src.skip(len)
            }
            return total
        }

        override suspend fun flush() {}
        override fun close() {}
    }

    /** A server frame: 9-byte header and payload. */
    private fun frame(type: Int, flags: Int, stream: Int, payload: ByteArray): ByteArray {
        val h = byteArrayOf(
            (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte(), type.toByte(), flags.toByte(),
            (stream ushr 24).toByte(), (stream ushr 16).toByte(), (stream ushr 8).toByte(), stream.toByte(),
        )
        return h + payload
    }

    private fun Random.serverFrame(): ByteArray {
        val stream = 1 + 2 * (if (nextInt(4) == 0) nextInt(0, 8) else nextInt(0, 3)) // mostly streams the client opened
        return when (nextInt(11)) {
            9, 10 -> frame(1, 4 or (if (nextBoolean()) 1 else 0), stream, byteArrayOf(0x88.toByte())) // more HEADERS
            0 -> frame(4, 0, 0, byteArrayOf(0, 4, 0, 0x10, 0, 0)) // SETTINGS initial window 1 MiB
            1 -> frame(4, 1, 0, ByteArray(0)) // SETTINGS ACK
            2 -> frame(1, 4 or (if (nextBoolean()) 1 else 0), stream, byteArrayOf(0x88.toByte())) // HEADERS :status 200
            3 -> frame(0, if (nextBoolean()) 1 else 0, stream, ByteArray(nextInt(0, 64)) { 'x'.code.toByte() }) // DATA
            4 -> frame(3, 0, stream, byteArrayOf(0, 0, 0, nextInt(0, 14).toByte())) // RST_STREAM
            5 -> frame(7, 0, 0, byteArrayOf(0, 0, 0, stream.toByte(), 0, 0, 0, nextInt(0, 14).toByte())) // GOAWAY
            6 -> frame(8, 0, if (nextBoolean()) 0 else stream, byteArrayOf(0, 1, 0, 0)) // WINDOW_UPDATE 65536
            7 -> frame(6, 0, 0, ByteArray(8)) // PING
            else -> ByteArray(nextInt(1, 24)) { nextInt(256).toByte() } // garbage
        }
    }

    /** A read script for the split layout: the server's SETTINGS, then pending turns and server frames, a few bytes mutated. */
    private fun Random.readScript(): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add((v ushr 8).toByte()); out.add(v.toByte()) }
        fun segment(f: ByteArray) { u16(f.size); f.forEach { out.add(it) } }
        segment(frame(4, 0, 0, ByteArray(0)))
        // Pending turns let the client run between frames (open its streams before the responses arrive).
        repeat(nextInt(1, 40)) { repeat(nextInt(0, 4)) { u16(0) }; segment(serverFrame()) }
        val b = out.toByteArray()
        repeat(nextInt(0, 3)) { if (b.size > 11) b[nextInt(11, b.size)] = nextInt(256).toByte() }
        return b
    }

    /** `fuzz_e2e`: a client keeps up to 128 POSTs of 32,769 bytes going against a scripted peer until the connection ends. */
    @Test
    fun fuzz_e2e() {
        val rng = Random(22)
        responses = 0
        repeat(1_500) { case ->
            // A quarter in the reference's shared layout with raw bytes, the rest split (see ScriptIo).
            val io = if (case % 4 == 0) ScriptIo(ByteArray(rng.nextInt(0, 400)) { rng.nextInt(256).toByte() })
            else ScriptIo(rng.readScript(), IntArray(rng.nextInt(0, 60)) { if (rng.nextInt(8) == 0) 0 else rng.nextInt(1, 70_000) })
            val what = "case $case"
            runReactor {
                try {
                    withTimeout(10_000) { e2e(io) }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    fail("$what did not end")
                } catch (e: H2Error) {
                } catch (e: IoException) {
                } catch (e: Throwable) {
                    fail("$what threw $e")
                }
            }
        }
        println("fuzz_e2e: $responses responses received")
        if (responses < 100) fail("fuzz_e2e: only $responses responses; the scripts no longer reach the response path")
    }

    private var responses = 0

    private suspend fun e2e(io: IoStream) = coroutineScope {
        val (client, connection) = handshake(io)
        val driver = async { connection.run() }
        val requests = launch {
            var inFlight = 0
            while (inFlight < 128) {
                client.ready()
                val request = Request.builder().method(Method.POST).uri("https://example.com/").body(Unit)
                val (response, send) = client.sendRequest(request, false)
                send.sendData(Bytes.copyOf(ByteArray(32769)), true)
                send.close()
                inFlight++
                launch { try { response.await(); responses++ } catch (e: H2Error) {} }
            }
        }
        try {
            driver.await()
        } finally {
            requests.cancel()
            client.close()
        }
    }
}
