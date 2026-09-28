package neton.http.h1

import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import kotlin.test.assertEquals
import kotlin.test.fail

/** Bytes of an ASCII / Latin-1 string, one byte per char. */
internal fun ascii(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

internal fun bufferOf(s: String): Buffer = Buffer().also { it.writeBytes(ascii(s)) }

internal fun bufferOf(bytes: ByteArray): Buffer = Buffer().also { it.writeBytes(bytes) }

internal fun Buffer.text(): String = peekAll().let { b -> CharArray(b.size) { (b[it].toInt() and 0xFF).toChar() }.concatToString() }

/** One decoded frame, the shape of hyper's `Result<Frame<Bytes>, io::Error>` in the reference tests. */
internal sealed class TestFrame {
    class Data(val bytes: ByteArray) : TestFrame() {
        val text: String get() = bytes.decodeToString()
    }

    class Trailers(val map: HeaderMap<HeaderValue>) : TestFrame()

    /** hyper's empty data frame at the end of the body. */
    object End : TestFrame()

    class Err(val error: BodyDecodeError) : TestFrame()
}

/**
 * `decode_fut` over the reference's `&[u8]` / `Bytes` mock readers: everything is already in [buf] and the reader
 * then reports EOF, so the decoder is called with `eof = true`.
 */
internal fun BodyDecoder.decodeFrame(buf: Buffer, eof: Boolean = true): TestFrame = when (decode(buf, eof)) {
    DecodeResult.DATA -> TestFrame.Data(data.toByteArray())
    DecodeResult.TRAILERS -> TestFrame.Trailers(trailers!!)
    DecodeResult.END -> TestFrame.End
    DecodeResult.ERROR -> TestFrame.Err(error!!)
    DecodeResult.NEED_MORE -> fail("unexpected NEED_MORE with eof=$eof, decoder=$this")
}

internal fun TestFrame.data(): TestFrame.Data = this as? TestFrame.Data ?: fail("expected data, got $this")

internal fun TestFrame.err(): BodyDecodeError = (this as? TestFrame.Err)?.error ?: fail("expected error, got $this")

internal fun TestFrame.trailers(): HeaderMap<HeaderValue> = (this as? TestFrame.Trailers)?.map ?: fail("expected trailers, got $this")

internal fun assertEnd(f: TestFrame) = assertEquals(TestFrame.End, f)

/**
 * Drives [decoder] over [chunks] arriving one after another, the transport ending after the last one ([eofAtEnd]).
 * Returns the concatenated data. Fails on an error or on data after the end.
 */
internal fun decodeIncrementally(decoder: BodyDecoder, chunks: List<ByteArray>, eofAtEnd: Boolean = true): String {
    val buf = Buffer()
    val out = StringBuilder()
    var next = 0
    var eof = false
    while (true) {
        when (decoder.decode(buf, eof)) {
            DecodeResult.DATA -> out.append(decoder.data.decodeToString())
            DecodeResult.TRAILERS, DecodeResult.END -> return out.toString()
            DecodeResult.ERROR -> fail("unexpected decode error ${decoder.error} after ${out.length} bytes")
            DecodeResult.NEED_MORE -> {
                if (next == chunks.size) fail("decoder wants more after all input: $decoder")
                buf.writeBytes(chunks[next++])
                if (next == chunks.size && eofAtEnd) eof = true
            }
        }
    }
}
